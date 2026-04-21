package org.androidgradletools.adbrelayandroid

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** OkHttp [toHttpUrlOrNull] does not accept ws/wss; validate via http/https stand-in. */
object RelayUrl {
    fun isValid(trimmed: String): Boolean {
        val httpLike = toHttpLikeForParse(trimmed) ?: return false
        return httpLike.toHttpUrlOrNull() != null
    }

    internal fun toHttpLikeForParse(trimmed: String): String? {
        val t = trimmed.trim()
        return when {
            t.length >= 6 && t.regionMatches(0, "wss://", 0, 6, ignoreCase = true) ->
                "https://" + t.substring(6)
            t.length >= 5 && t.regionMatches(0, "ws://", 0, 5, ignoreCase = true) ->
                "http://" + t.substring(5)
            else -> null
        }
    }
}
