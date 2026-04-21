package org.androidgradletools.adbrelayandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stops [AdbProxyService] reliably (same path as in-app Disconnect). */
class AdbProxyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, AdbProxyService::class.java),
        )
    }
}
