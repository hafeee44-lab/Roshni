package io.github.hafeee44lab.solargrid.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification buttons that must work even when the monitor itself is not running. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_ALARM -> {
                AlarmPlayer.stop()
                Notifier.cancelAlarm(context)
            }
        }
    }

    companion object {
        const val ACTION_STOP_ALARM = "io.github.hafeee44lab.solargrid.STOP_ALARM"

        fun stopAlarm(context: Context, request: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, ActionReceiver::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
