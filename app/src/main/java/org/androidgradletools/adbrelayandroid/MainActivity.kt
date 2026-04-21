package org.androidgradletools.adbrelayandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.androidgradletools.adbrelayandroid.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusPollRunnable = Runnable { refreshStatus() }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Snackbar.make(
                binding.rootCoordinator,
                getString(R.string.notification_permission_denied),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        applyWindowInsets()
        syncSystemBarContrast()

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val cfg = Prefs.load(this)
        binding.inputRelayUrl.setText(cfg.relayUrl)
        binding.inputToken.setText(cfg.token)
        binding.inputAdbdHost.setText(cfg.adbdHost)
        binding.inputAdbdPort.setText(cfg.adbdPort.toString())
        binding.textAdbdGuide.text = getString(
            R.string.adbd_guide_steps,
            Prefs.DEFAULT_ADBD_HOST,
            Prefs.DEFAULT_ADBD_PORT,
        )
        binding.buttonOpenWirelessDebugging.setOnClickListener {
            openWirelessDebuggingSettings()
        }

        binding.buttonConnect.setOnClickListener {
            if (ProxyRuntime.connecting || ProxyRuntime.disconnecting || ProxyRuntime.connected) {
                return@setOnClickListener
            }
            val c = validateAndBuildConfig()
            if (c == null) {
                Snackbar.make(
                    binding.rootCoordinator,
                    getString(R.string.error_connect_fix_fields),
                    Snackbar.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            Prefs.save(this, c)
            ProxyRuntime.connecting = true
            ProxyRuntime.lastError = null
            refreshStatus()
            scheduleStatusPollIfNeeded()
            AdbProxyService.start(this)
        }
        binding.buttonDisconnect.setOnClickListener {
            if (ProxyRuntime.disconnecting) {
                return@setOnClickListener
            }
            // Allow cancel while handshake is still in progress (connected was false → button did nothing).
            if (!ProxyRuntime.connecting && !ProxyRuntime.connected) {
                return@setOnClickListener
            }
            ProxyRuntime.disconnecting = true
            refreshStatus()
            scheduleStatusPollIfNeeded()
            AdbProxyService.stop(this)
        }

        refreshStatus()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(statusPollRunnable)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        syncSystemBarContrast()
        refreshStatus()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = status.top, left = cut.left, right = cut.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollMain) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = cut.left,
                right = cut.right,
                bottom = max(nav.bottom, ime.bottom),
            )
            insets
        }
    }

    private fun syncSystemBarContrast() {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    private fun themeColor(attr: Int): Int =
        MaterialColors.getColor(binding.cardStatus, attr)

    private fun openWirelessDebuggingSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Snackbar.make(
                binding.rootCoordinator,
                getString(R.string.error_open_wireless_settings),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun clearConnectFieldErrors() {
        binding.inputLayoutRelayUrl.error = null
        binding.inputLayoutToken.error = null
        binding.inputLayoutAdbdHost.error = null
        binding.inputLayoutAdbdPort.error = null
    }

    /** Returns config when valid; sets TextInputLayout errors otherwise. */
    private fun validateAndBuildConfig(): Prefs.Config? {
        clearConnectFieldErrors()
        var ok = true

        val relayUrl = binding.inputRelayUrl.text?.toString()?.trim().orEmpty()
        if (relayUrl.isEmpty()) {
            binding.inputLayoutRelayUrl.error = getString(R.string.error_relay_required)
            ok = false
        } else if (!RelayUrl.isValid(relayUrl)) {
            binding.inputLayoutRelayUrl.error = getString(R.string.error_relay_invalid)
            ok = false
        }

        val token = binding.inputToken.text?.toString().orEmpty()
        if (token.isBlank()) {
            binding.inputLayoutToken.error = getString(R.string.error_token_required)
            ok = false
        }

        val adbdHost = binding.inputAdbdHost.text?.toString()?.trim().orEmpty()
        if (adbdHost.isEmpty()) {
            binding.inputLayoutAdbdHost.error = getString(R.string.error_adbd_host_required)
            ok = false
        }

        val portStr = binding.inputAdbdPort.text?.toString()?.trim().orEmpty()
        val port = if (portStr.isEmpty()) {
            binding.inputLayoutAdbdPort.error = getString(R.string.error_adbd_port_required)
            ok = false
            Prefs.DEFAULT_ADBD_PORT
        } else {
            val p = portStr.toIntOrNull()
            if (p == null || p !in 1..65535) {
                binding.inputLayoutAdbdPort.error = getString(R.string.error_port_invalid)
                ok = false
                Prefs.DEFAULT_ADBD_PORT
            } else {
                p
            }
        }

        if (!ok) return null

        return Prefs.Config(
            relayUrl = relayUrl,
            token = token,
            adbdHost = adbdHost,
            adbdPort = port,
        )
    }

    private fun scheduleStatusPollIfNeeded() {
        binding.root.removeCallbacks(statusPollRunnable)
        if (ProxyRuntime.connecting || ProxyRuntime.disconnecting) {
            binding.root.postDelayed(statusPollRunnable, 200)
        }
    }

    private fun refreshStatus() {
        val disconnecting = ProxyRuntime.disconnecting
        val connecting = ProxyRuntime.connecting
        val connected = ProxyRuntime.connected
        val err = ProxyRuntime.lastError?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val hairline = resources.getDimensionPixelSize(R.dimen.hairline)

        val busy = connecting || disconnecting
        binding.progressStatus.visibility = if (busy) View.VISIBLE else View.GONE
        binding.imageStatus.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        binding.buttonConnect.isEnabled = !busy && !connected
        // Stop is allowed while the handshake runs (connecting); `busy` would wrongly disable it.
        binding.buttonDisconnect.isEnabled = !disconnecting && (connecting || connected)

        val fieldsEnabled = !busy && !connected
        binding.inputLayoutRelayUrl.isEnabled = fieldsEnabled
        binding.inputLayoutToken.isEnabled = fieldsEnabled
        binding.inputLayoutAdbdHost.isEnabled = fieldsEnabled
        binding.inputLayoutAdbdPort.isEnabled = fieldsEnabled
        binding.buttonOpenWirelessDebugging.isEnabled = fieldsEnabled

        if (busy) {
            binding.progressStatus.indeterminateTintList = ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorSecondary),
            )
        }

        binding.textStatusTitle.alpha = 1f
        binding.textStatusSubtitle.alpha = 1f

        when {
            disconnecting -> {
                binding.textPcHint.visibility = View.GONE
                binding.progressStatus.contentDescription = getString(R.string.status_disconnecting_title)
                binding.imageStatus.contentDescription = getString(R.string.status_disconnecting_title)
                ImageViewCompat.setImageTintList(
                    binding.imageStatus,
                    ColorStateList.valueOf(
                        themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                    ),
                )
                binding.textStatusTitle.text = getString(R.string.status_disconnecting_title)
                binding.textStatusSubtitle.text = getString(R.string.status_disconnecting_subtitle)
                binding.textStatusTitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                )
                binding.textStatusSubtitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                )
                binding.textStatusSubtitle.alpha = 0.9f
                binding.cardStatus.setCardBackgroundColor(
                    themeColor(com.google.android.material.R.attr.colorSecondaryContainer),
                )
                binding.cardStatus.strokeWidth = 0
            }
            connecting -> {
                binding.textPcHint.visibility = View.GONE
                binding.progressStatus.contentDescription = getString(R.string.status_connecting_title)
                binding.imageStatus.contentDescription = getString(R.string.status_connecting_title)
                ImageViewCompat.setImageTintList(
                    binding.imageStatus,
                    ColorStateList.valueOf(
                        themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                    ),
                )
                binding.textStatusTitle.text = getString(R.string.status_connecting_title)
                binding.textStatusSubtitle.text = getString(R.string.status_connecting_subtitle)
                binding.textStatusTitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                )
                binding.textStatusSubtitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer),
                )
                binding.textStatusSubtitle.alpha = 0.9f
                binding.cardStatus.setCardBackgroundColor(
                    themeColor(com.google.android.material.R.attr.colorSecondaryContainer),
                )
                binding.cardStatus.strokeWidth = 0
            }
            connected -> {
                binding.textPcHint.visibility = View.VISIBLE
                binding.imageStatus.setImageResource(R.drawable.ic_status_connected)
                binding.imageStatus.contentDescription = getString(R.string.status_connected_title)
                ImageViewCompat.setImageTintList(
                    binding.imageStatus,
                    ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorPrimary)),
                )
                binding.textStatusTitle.text = getString(R.string.status_connected_title)
                binding.textStatusSubtitle.text = getString(R.string.status_connected_subtitle)
                binding.textStatusTitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer),
                )
                binding.textStatusSubtitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer),
                )
                binding.textStatusSubtitle.alpha = 0.88f
                binding.cardStatus.setCardBackgroundColor(
                    themeColor(com.google.android.material.R.attr.colorPrimaryContainer),
                )
                binding.cardStatus.strokeWidth = 0
            }
            err != null -> {
                binding.textPcHint.visibility = View.GONE
                binding.imageStatus.setImageResource(R.drawable.ic_status_error)
                binding.imageStatus.contentDescription = getString(R.string.status_problem_title)
                ImageViewCompat.setImageTintList(
                    binding.imageStatus,
                    ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorError)),
                )
                binding.textStatusTitle.text = getString(R.string.status_problem_title)
                binding.textStatusSubtitle.text =
                    getString(R.string.status_problem_subtitle_prefix) + " " + err
                binding.textStatusTitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnErrorContainer),
                )
                binding.textStatusSubtitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnErrorContainer),
                )
                binding.textStatusSubtitle.alpha = 0.92f
                binding.cardStatus.setCardBackgroundColor(
                    themeColor(com.google.android.material.R.attr.colorErrorContainer),
                )
                binding.cardStatus.strokeWidth = 0
            }
            else -> {
                binding.textPcHint.visibility = View.GONE
                binding.imageStatus.setImageResource(R.drawable.ic_status_standby)
                binding.imageStatus.contentDescription = getString(R.string.status_standby_title)
                ImageViewCompat.setImageTintList(
                    binding.imageStatus,
                    ColorStateList.valueOf(
                        themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                    ),
                )
                binding.textStatusTitle.text = getString(R.string.status_standby_title)
                binding.textStatusSubtitle.text = getString(R.string.status_standby_subtitle)
                binding.textStatusTitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSurface),
                )
                binding.textStatusSubtitle.setTextColor(
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                binding.cardStatus.setCardBackgroundColor(
                    themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                )
                binding.cardStatus.strokeColor =
                    themeColor(com.google.android.material.R.attr.colorOutlineVariant)
                binding.cardStatus.strokeWidth = hairline
            }
        }
        scheduleStatusPollIfNeeded()
    }
}
