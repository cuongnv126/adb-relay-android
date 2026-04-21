package org.androidgradletools.adbrelayandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AdbProxyService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val io: ExecutorService = Executors.newCachedThreadPool()
    /** Serializes mux parse + frame handling so DATA writes stay in order (thread pool was reordering). */
    private val muxExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "adb-proxy-mux") }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webSocket: WebSocket? = null

    /** True while user tunnel should stay up (including between WebSocket retries). */
    private val running = AtomicBoolean(false)

    /** Prevents double handling when both onFailure and onClosed fire. */
    private val socketDeathHandling = AtomicBoolean(false)

    private val muxStream = java.io.ByteArrayOutputStream()
    private val channels = ConcurrentHashMap<Int, Socket>()
    /** DATA can arrive in the same WS batch before OPEN’s TCP connect finishes; buffer until then. */
    private val pendingWrites = ConcurrentHashMap<Int, ConcurrentLinkedQueue<ByteArray>>()

    private var reconnectAttempt = 0

    private val reconnectRunnable = Runnable {
        if (!running.get()) {
            return@Runnable
        }
        connectWebSocket()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) {
            return START_STICKY
        }
        val cfg = Prefs.load(this)
        createNotificationChannel()
        val notification = buildNotification(
            cfg.relayUrl.trim().ifEmpty { getString(R.string.notification_title) },
            reconnecting = false,
        )
        // Avoid android:foregroundServiceType="connectedDevice" unless the app matches Android 14’s
        // checks for that type (USB/BT/NFC hardware paths). This tunnel is network-only; wrong type → crash.
        startForeground(NOTIFICATION_ID, notification)
        if (cfg.relayUrl.isBlank() || cfg.token.isBlank()) {
            toast(getString(R.string.error_service_missing_required))
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        running.set(true)
        reconnectAttempt = 0
        ProxyRuntime.connected = false
        ProxyRuntime.lastError = null
        ProxyRuntime.connecting = true
        connectWebSocket()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        running.set(false)
        mainHandler.removeCallbacks(reconnectRunnable)
        socketDeathHandling.set(false)
        webSocket?.close(1000, "stop")
        webSocket = null
        muxExecutor.shutdownNow()
        muxStream.reset()
        for (s in channels.values) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        channels.clear()
        pendingWrites.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        mainHandler.post {
            ProxyRuntime.connected = false
            ProxyRuntime.connecting = false
            ProxyRuntime.disconnecting = false
        }
    }

    private fun connectWebSocket() {
        if (!running.get()) {
            return
        }
        val cfg = Prefs.load(this)
        if (cfg.relayUrl.isBlank() || cfg.token.isBlank()) {
            mainHandler.post {
                ProxyRuntime.connecting = false
                ProxyRuntime.connected = false
                ProxyRuntime.lastError = getString(R.string.error_service_missing_required)
            }
            running.set(false)
            stopSelf()
            return
        }
        Log.i(
            TAG,
            "connectWebSocket attempt=${reconnectAttempt + 1} relay=${cfg.relayUrl.trim().substringBefore("?")} adbd=${cfg.resolvedAdbdHost()}:${cfg.resolvedAdbdPort()}",
        )
        val req = Request.Builder().url(cfg.relayUrl.trim()).build()
        try {
            webSocket = client.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        reconnectAttempt = 0
                        val hello = JSONObject()
                            .put("v", 1)
                            .put("role", "device")
                            .put("token", cfg.token)
                        webSocket.send(hello.toString())
                        Log.i(TAG, "WebSocket onOpen: hello sent (role=device, token len=${cfg.token.length})")
                        mainHandler.post {
                            ProxyRuntime.connecting = false
                            ProxyRuntime.connected = true
                            ProxyRuntime.lastError = null
                            refreshTunnelNotification(cfg.relayUrl.trim(), reconnecting = false)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Handshake is the only text from relay in normal flow; ignore after open.
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        if (!running.get()) {
                            return
                        }
                        val chunk = bytes.toByteArray()
                        muxExecutor.execute {
                            if (!running.get()) {
                                return@execute
                            }
                            muxStream.write(chunk)
                            try {
                                val snapshot = muxStream.toByteArray()
                                val (consumed, frames) = MuxCodec.parse(snapshot, 0, snapshot.size)
                                muxStream.reset()
                                if (consumed < snapshot.size) {
                                    muxStream.write(snapshot, consumed, snapshot.size - consumed)
                                }
                                for (frame in frames) {
                                    handleMuxFrame(cfg, webSocket, frame)
                                }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    val msg = WsErrorMessages.userMessage(this@AdbProxyService, e)
                                    ProxyRuntime.lastError = msg
                                    toast("Mux error: $msg")
                                }
                                webSocket.close(1011, "mux error")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "WebSocket onFailure", t)
                        handleWebSocketDeath(cfg, t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "WebSocket onClosed code=$code reason=$reason")
                        handleWebSocketDeath(cfg, null)
                    }
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "newWebSocket failed", t)
            handleWebSocketDeath(cfg, t)
        }
    }

    /**
     * Single path for socket teardown: reset mux/adbd state, then either stop the service (user
     * disconnect) or schedule reconnect with exponential backoff (capped).
     */
    private fun handleWebSocketDeath(cfg: Prefs.Config, cause: Throwable?) {
        if (!running.get()) {
            return
        }
        if (!socketDeathHandling.compareAndSet(false, true)) {
            return
        }
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket = null

        try {
            muxExecutor.execute {
                try {
                    muxStream.reset()
                    val ids = channels.keys.toList()
                    for (id in ids) {
                        channels.remove(id)?.let { s ->
                            try {
                                s.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    pendingWrites.clear()
                } finally {
                    mainHandler.post {
                        socketDeathHandling.set(false)
                        if (!running.get()) {
                            ProxyRuntime.connecting = false
                            ProxyRuntime.connected = false
                            stopSelf()
                            return@post
                        }
                        reconnectAttempt++
                        val delayMs = reconnectDelayMs(reconnectAttempt)
                        Log.w(
                            TAG,
                            "Scheduling WebSocket reconnect in ${delayMs}ms (attempt $reconnectAttempt) cause=${cause?.message}",
                        )
                        mainHandler.postDelayed(reconnectRunnable, delayMs)
                        ProxyRuntime.connected = false
                        ProxyRuntime.connecting = true
                        ProxyRuntime.lastError = getString(R.string.tunnel_relay_reconnecting)
                        refreshTunnelNotification(cfg.relayUrl.trim(), reconnecting = true)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            socketDeathHandling.set(false)
        }
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val jitter = (Math.random() * 2500L).toLong()
        if (attempt <= 1) {
            return INITIAL_RECONNECT_DELAY_MS + jitter
        }
        val shift = (attempt - 2).coerceIn(0, 15)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_CAP_MS) + jitter
    }

    private fun refreshTunnelNotification(relayUrlTrimmed: String, reconnecting: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val url = relayUrlTrimmed.ifEmpty { getString(R.string.notification_title) }
        mgr.notify(NOTIFICATION_ID, buildNotification(url, reconnecting))
    }

    private fun handleMuxFrame(cfg: Prefs.Config, ws: WebSocket, frame: MuxCodec.Frame) {
        when (frame.type) {
            MuxCodec.TYPE_OPEN -> {
                val id = frame.channelId
                if (id == 0) {
                    return
                }
                pendingWrites.remove(id)
                channels.remove(id)?.let { old ->
                    try {
                        old.close()
                    } catch (_: Exception) {
                    }
                }
                val queue = ConcurrentLinkedQueue<ByteArray>()
                pendingWrites[id] = queue
                io.execute {
                    if (!running.get()) {
                        pendingWrites.remove(id)
                        return@execute
                    }
                    try {
                        val sock = Socket()
                        sock.tcpNoDelay = true
                        sock.connect(
                            InetSocketAddress(cfg.resolvedAdbdHost(), cfg.resolvedAdbdPort()),
                            20_000,
                        )
                        channels[id] = sock
                        synchronized(sock) {
                            val out = sock.getOutputStream()
                            while (true) {
                                val b = queue.poll() ?: break
                                out.write(b)
                            }
                            out.flush()
                        }
                        pendingWrites.remove(id)
                        val input = sock.getInputStream()
                        val buf = ByteArray(16 * 1024)
                        readLoop@ while (running.get() && !sock.isClosed) {
                            val n = input.read(buf)
                            if (n < 0) {
                                break
                            }
                            if (n == 0) {
                                continue
                            }
                            val payload = if (n == buf.size) buf else buf.copyOf(n)
                            val encoded = MuxCodec.encode(MuxCodec.TYPE_DATA, id, payload)
                            val sent = synchronized(ws) {
                                ws.send(encoded.toByteString())
                            }
                            if (!sent) {
                                break@readLoop
                            }
                        }
                    } catch (e: Exception) {
                        pendingWrites.remove(id)
                        mainHandler.post {
                            val p = cfg.resolvedAdbdPort()
                            val h = cfg.resolvedAdbdHost()
                            toast(
                                getString(
                                    R.string.error_adbd_connect,
                                    h,
                                    p,
                                    e.message ?: e.javaClass.simpleName,
                                ),
                            )
                        }
                    } finally {
                        pendingWrites.remove(id)
                        channels.remove(id)?.let { s ->
                            try {
                                s.close()
                            } catch (_: Exception) {
                            }
                        }
                        if (running.get()) {
                            val closeFrame = MuxCodec.encode(MuxCodec.TYPE_CLOSE, id)
                            synchronized(ws) {
                                ws.send(closeFrame.toByteString())
                            }
                        }
                    }
                }
            }
            MuxCodec.TYPE_DATA -> {
                val cid = frame.channelId
                if (frame.payload.isEmpty()) {
                    return
                }
                val sock = channels[cid]
                if (sock != null && !sock.isClosed) {
                    try {
                        synchronized(sock) {
                            sock.getOutputStream().write(frame.payload)
                        }
                    } catch (_: Exception) {
                        channels.remove(cid)?.let { s ->
                            try {
                                s.close()
                            } catch (_: Exception) {
                            }
                        }
                        val closeFrame = MuxCodec.encode(MuxCodec.TYPE_CLOSE, cid)
                        synchronized(ws) {
                            ws.send(closeFrame.toByteString())
                        }
                    }
                } else {
                    pendingWrites[cid]?.offer(frame.payload.copyOf())
                }
            }
            MuxCodec.TYPE_CLOSE -> {
                pendingWrites.remove(frame.channelId)
                val sock = channels.remove(frame.channelId)
                if (sock != null) {
                    io.execute {
                        try {
                            sock.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    /** Same text as Toast — capture with `adb logcat -s AdbProxy:I`. */
    private fun toast(msg: String) {
        Log.i(TAG, msg)
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(url: String, reconnecting: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = Intent(this, AdbProxyStopReceiver::class.java)
        val stopPi = PendingIntent.getBroadcast(
            this,
            1,
            stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (reconnecting) {
            getString(R.string.notification_reconnecting, reconnectAttempt)
        } else {
            getString(R.string.notification_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(body)
            .setSubText(url)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), stopPi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "org.androidgradletools.adbrelayandroid.action.STOP"
        private const val TAG = "AdbProxy"
        private const val CHANNEL_ID = "adb_proxy_tunnel"
        private const val NOTIFICATION_ID = 1001

        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val PING_INTERVAL_SEC = 15L

        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_CAP_MS = 60_000L

        fun start(context: Context) {
            val i = Intent(context, AdbProxyService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Tears down the running service; prefer this over `startService(ACTION_STOP)` (more reliable across OEMs). */
        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, AdbProxyService::class.java),
            )
        }
    }
}
