package io.github.hafeee44lab.solargrid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.hafeee44lab.solargrid.data.AppGraph

/** Picks monitoring back up after a reboot or an app update, if it was on before. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return

        if (!AppGraph.get(context).settings.load().monitoring) return

        try {
            MonitorService.start(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Could not restart the monitor", e)
        }
    }
}
