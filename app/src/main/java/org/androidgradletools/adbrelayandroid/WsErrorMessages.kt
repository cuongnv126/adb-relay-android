package org.androidgradletools.adbrelayandroid

import android.content.Context

/** Maps common OkHttp/WebSocket errors to short, actionable copy. */
object WsErrorMessages {
    fun userMessage(context: Context, cause: Throwable?): String {
        val raw = cause?.message?.trim().orEmpty()
            .ifEmpty { cause?.javaClass?.simpleName ?: "error" }
        return userMessage(context, raw)
    }

    fun userMessage(context: Context, rawMessage: String): String {
        val raw = rawMessage.trim()
        return when {
            raw.contains("Unable to parse TLS packet header", ignoreCase = true) ->
                context.getString(R.string.error_ws_tls_mismatch_plain_relay)
            raw.contains("CLEARTEXT communication not permitted", ignoreCase = true) ->
                context.getString(R.string.error_ws_cleartext_blocked)
            else -> raw
        }
    }
}
