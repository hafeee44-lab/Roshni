package io.github.hafeee44lab.solargrid.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hafeee44lab.solargrid.MainActivity
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.SolarSnapshot
import io.github.hafeee44lab.solargrid.data.phrases
import io.github.hafeee44lab.solargrid.domain.Alert
import io.github.hafeee44lab.solargrid.domain.AlertKind
import io.github.hafeee44lab.solargrid.domain.HomeUi
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.domain.kw
import io.github.hafeee44lab.solargrid.ui.alarm.AlarmActivity
import java.util.Locale

/**
 * Every notification the app posts. Each kind of alert has its own channel, so Android's own
 * settings let people silence "battery full" without silencing outages.
 */
object Notifier {

    const val STATUS_ID = 1
    const val ALARM_ID = 2

    const val CH_STATUS = "status"
    const val CH_OUTAGE = "outage_alarm"
    const val CH_RESTORED = "grid_restored"
    const val CH_BATTERY = "battery"
    const val CH_HEALTH = "system_health"
    const val CH_INSIGHTS = "insights"

    private const val GROUP_ALERTS = "alerts"
    private const val GROUP_INFO = "info"

    private const val COLOR_UP = 0xFF1B8A4E.toInt()
    private const val COLOR_DOWN = 0xFFC62828.toInt()
    private const val COLOR_SOLAR = 0xFFF2A007.toInt()

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannelGroups(
            listOf(
                NotificationChannelGroup(GROUP_ALERTS, context.getString(R.string.ch_group_alerts)),
                NotificationChannelGroup(GROUP_INFO, context.getString(R.string.ch_group_info))
            )
        )

        fun channel(id: String, name: Int, importance: Int, group: String, desc: Int, block: NotificationChannel.() -> Unit = {}) =
            NotificationChannel(id, context.getString(name), importance).apply {
                description = context.getString(desc)
                this.group = group
                block()
            }

        manager.createNotificationChannels(
            listOf(
                channel(CH_OUTAGE, R.string.outage_alarm, NotificationManager.IMPORTANCE_HIGH, GROUP_ALERTS,
                    R.string.ch_outage_desc) {
                    // The alarm loop plays its own sound; a channel sound on top would double it.
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(true)
                    lightColor = Color.RED
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                channel(CH_RESTORED, R.string.mains_back_on, NotificationManager.IMPORTANCE_HIGH, GROUP_ALERTS,
                    R.string.ch_restored_desc),
                channel(CH_BATTERY, R.string.battery, NotificationManager.IMPORTANCE_HIGH, GROUP_ALERTS,
                    R.string.ch_battery_desc) {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                channel(CH_HEALTH, R.string.system_health, NotificationManager.IMPORTANCE_DEFAULT, GROUP_ALERTS,
                    R.string.ch_health_desc),
                channel(CH_INSIGHTS, R.string.ch_insights, NotificationManager.IMPORTANCE_DEFAULT, GROUP_INFO,
                    R.string.ch_insights_desc),
                channel(CH_STATUS, R.string.ch_status, NotificationManager.IMPORTANCE_LOW, GROUP_INFO,
                    R.string.ch_status_desc) {
                    setShowBadge(false)
                }
            )
        )

        // Channels from v1, before alerts were split up.
        listOf("solar_status_v2", "solar_alerts_v2").forEach { manager.deleteNotificationChannel(it) }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---------------- ongoing status ----------------

    fun status(context: Context, snapshot: SolarSnapshot, running: Boolean = true): Notification {
        val ui = buildHomeUi(snapshot, running, p = context.phrases())
        val down = ui.heroRole == StateRole.DOWN

        if (Build.VERSION.SDK_INT >= 36 && down) {
            liveUpdate(context, ui)?.let { return it }
        }

        val builder = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(if (down) R.drawable.ic_stat_home_off else R.drawable.ic_stat_home)
            .setContentTitle(statusTitle(context, ui))
            .setContentText(statusText(context, ui))
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText(context, ui)))
            .setColor(colorFor(ui))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.refresh), serviceAction(context, MonitorService.ACTION_REFRESH, 20))

        if (ui.soc != null && ui.trusted) {
            builder.setProgress(100, ui.soc.toInt(), false)
        }

        if (down && ui.outageSince != null) {
            builder.setWhen(ui.outageSince).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        return builder.build()
    }

    fun starting(context: Context): Notification =
        NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_home)
            .setContentTitle(context.getString(R.string.starting_monitor))
            .setContentText(context.getString(R.string.connecting_inverter))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /**
     * Android 16 promotes this to a Live Update: a status-bar chip with the battery level and a
     * segmented progress bar, so you can watch the outage without opening anything.
     */
    private fun liveUpdate(context: Context, ui: HomeUi): Notification? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val soc = ui.soc?.toInt()?.coerceIn(0, 100) ?: return null
            val style = Notification.ProgressStyle()
                .setProgress(soc)
                .setStyledByProgress(true)
                .setProgressSegments(
                    listOf(
                        Notification.ProgressStyle.Segment(20).setColor(COLOR_DOWN),
                        Notification.ProgressStyle.Segment(30).setColor(COLOR_SOLAR),
                        Notification.ProgressStyle.Segment(50).setColor(COLOR_UP)
                    )
                )

            Notification.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_home_off)
                .setContentTitle(statusTitle(context, ui))
                .setContentText(statusText(context, ui))
                .setStyle(style)
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .apply {
                    // Promotion to a Live Update arrived in Android 16 QPR2 (36.1).
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) setRequestPromotedOngoing(true)
                }
                .setShortCriticalText("$soc%")
                .apply {
                    ui.outageSince?.let { setWhen(it).setUsesChronometer(true).setShowWhen(true) }
                }
                .build()
        } catch (e: Throwable) {
            null
        }
    }

    private fun statusTitle(context: Context, ui: HomeUi): String = when (ui.heroRole) {
        StateRole.DOWN -> context.getString(R.string.status_down, ui.socText)
        StateRole.UP -> context.getString(R.string.status_up, ui.socText)
        StateRole.NEUTRAL -> context.getString(R.string.status_other, ui.heroState, ui.socText)
    }

    private fun statusText(context: Context, ui: HomeUi): String = when {
        !ui.trusted -> ui.heroSub
        ui.heroRole == StateRole.DOWN -> listOfNotNull(
            ui.runtime?.let { context.getString(R.string.lasts_about, it) },
            context.getString(R.string.home_load, ui.loadLabel)
        ).joinToString(" · ")
        else -> listOfNotNull(
            ui.powers.solarW?.takeIf { it > 50 }?.let { context.getString(R.string.solar_kw, kw(it)) },
            context.getString(R.string.home_load, ui.loadLabel),
            ui.fullIn?.let { context.getString(R.string.widget_full_in, it) }
        ).joinToString(" · ")
    }

    private fun colorFor(ui: HomeUi): Int = when (ui.heroRole) {
        StateRole.DOWN -> COLOR_DOWN
        StateRole.UP -> COLOR_UP
        StateRole.NEUTRAL -> COLOR_SOLAR
    }

    // ---------------- alerts ----------------

    fun post(context: Context, alert: Alert, test: Boolean = false) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val ringing = alert.ring
        val channel = when (alert.kind) {
            AlertKind.OUTAGE -> CH_OUTAGE
            AlertKind.RESTORED -> CH_RESTORED
            AlertKind.LOW_BATTERY, AlertKind.CRITICAL_BATTERY, AlertKind.BATTERY_FULL, AlertKind.OVERLOAD -> CH_BATTERY
            AlertKind.INVERTER_SILENT, AlertKind.INVERTER_FAULT, AlertKind.INVERTER_HOT -> CH_HEALTH
            AlertKind.HEADS_UP, AlertKind.SUMMARY -> CH_INSIGHTS
        }

        // An outage that is not ringing (quiet hours, alarm off) still needs to make a sound,
        // so it goes out on the battery channel rather than the silent alarm channel.
        val effectiveChannel = if (alert.kind == AlertKind.OUTAGE && !ringing) CH_BATTERY else channel

        val icon = when (alert.kind) {
            AlertKind.OUTAGE, AlertKind.INVERTER_SILENT -> R.drawable.ic_stat_home_off
            AlertKind.LOW_BATTERY, AlertKind.CRITICAL_BATTERY, AlertKind.OVERLOAD, AlertKind.BATTERY_FULL -> R.drawable.ic_stat_battery
            AlertKind.HEADS_UP, AlertKind.SUMMARY -> R.drawable.ic_stat_sun
            else -> R.drawable.ic_stat_home
        }

        val color = when (alert.kind) {
            AlertKind.RESTORED, AlertKind.BATTERY_FULL -> COLOR_UP
            AlertKind.HEADS_UP, AlertKind.SUMMARY, AlertKind.INVERTER_HOT -> COLOR_SOLAR
            else -> COLOR_DOWN
        }

        val builder = NotificationCompat.Builder(context, effectiveChannel)
            .setSmallIcon(icon)
            .setColor(color)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                when (alert.kind) {
                    AlertKind.OUTAGE, AlertKind.CRITICAL_BATTERY -> NotificationCompat.CATEGORY_ALARM
                    AlertKind.HEADS_UP -> NotificationCompat.CATEGORY_REMINDER
                    else -> NotificationCompat.CATEGORY_STATUS
                }
            )

        if (ringing) {
            val fullScreen = PendingIntent.getActivity(
                context,
                REQUEST_ALARM_SCREEN,
                Intent(context, AlarmActivity::class.java)
                    .putExtra(AlarmActivity.EXTRA_KIND, alert.kind.name)
                    .putExtra(AlarmActivity.EXTRA_TEST, test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(0, context.getString(R.string.stop_alarm), ActionReceiver.stopAlarm(context, 21))
                .setDeleteIntent(ActionReceiver.stopAlarm(context, 22))
            manager.notify(ALARM_ID, builder.build())
        } else {
            manager.notify(ID_BASE + alert.kind.ordinal, builder.build())
        }
    }

    fun cancelAlarm(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ALARM_ID)
    }

    /** Clears alerts that no longer apply — "battery low" once the grid is back, for instance. */
    fun cancel(context: Context, vararg kinds: AlertKind) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        kinds.forEach { manager.cancel(ID_BASE + it.ordinal) }
    }

    fun updateStatus(context: Context, notification: Notification) {
        if (!canPost(context)) return
        context.getSystemService(NotificationManager::class.java)?.notify(STATUS_ID, notification)
    }

    fun testAlert(context: Context, ringInSilentMode: Boolean, ringSeconds: Int) {
        // A test never rings forever, even when the real alarm is set to.
        AlarmPlayer.start(context, ringInSilentMode, (if (ringSeconds > 0) ringSeconds else 90) * 1000L)
        post(
            context,
            Alert(AlertKind.OUTAGE, context.getString(R.string.test_alarm_title), context.getString(R.string.alarm_test_body), ring = true),
            test = true
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(context: Context, action: String, request: Int): PendingIntent {
        val intent = Intent(context, MonitorService::class.java).setAction(action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getService(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    fun runtimeText(minutes: Int?): String? =
        minutes?.takeIf { it > 0 }?.let { RuntimeEstimator.describe(it) }

    fun percent(value: Double?): String = value?.let { String.format(Locale.US, "%.0f", it) } ?: "--"

    private const val ID_BASE = 100
    private const val REQUEST_OPEN = 1
    private const val REQUEST_ALARM_SCREEN = 2
}
