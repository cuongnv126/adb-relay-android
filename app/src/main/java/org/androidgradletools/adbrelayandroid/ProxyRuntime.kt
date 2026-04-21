package org.androidgradletools.adbrelayandroid

object ProxyRuntime {
    @Volatile
    var connected: Boolean = false

    @Volatile
    var lastError: String? = null

    /** WebSocket handshake / tunnel not ready yet. */
    @Volatile
    var connecting: Boolean = false

    /** Stop requested; service tearing down. */
    @Volatile
    var disconnecting: Boolean = false
}
