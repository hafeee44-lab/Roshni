package io.github.hafeee44lab.solargrid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.HistoryImport
import io.github.hafeee44lab.solargrid.domain.AlertKind
import io.github.hafeee44lab.solargrid.domain.PollPolicy
import io.github.hafeee44lab.solargrid.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps watch. A foreground service so Android leaves it alone, woken by an alarm chain rather
 * than a timer so it survives deep sleep, and holding a wake lock for exactly as long as each
 * poll takes — without one the CPU can doze off halfway through a network call.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_POLL = "io.github.hafeee44lab.solargrid.POLL"
        const val ACTION_REFRESH = "io.github.hafeee44lab.solargrid.REFRESH"
        const val ACTION_STOP_ALARM = "io.github.hafeee44lab.solargrid.SERVICE_STOP_ALARM"
        const val ACTION_WATCHDOG = "io.github.hafeee44lab.solargrid.WATCHDOG"

        /** A manual refresh within this long of the last call shows what we have instead of asking again. */
        private const val REFRESH_MIN_GAP_MS = 20_000L
        private const val WATCHDOG_GRACE_MS = 2 * 60_000L
        private const val IMPORT_WAKE_MS = 3 * 60_000L

        private const val REQUEST_POLL = 7
        private const val REQUEST_WATCHDOG = 8
        private const val WAKE_TIMEOUT_MS = 90_000L

        private val _busy = MutableStateFlow(false)

        /** True while a poll is in flight — drives the refresh spinner. */
        val busy: StateFlow<Boolean> = _busy

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_REFRESH)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
            cancelSchedule(context)
        }

        private fun pending(context: Context, request: Int): PendingIntent {
            val action = if (request == REQUEST_WATCHDOG) ACTION_WATCHDOG else ACTION_POLL
            val intent = Intent(context, MonitorService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, request, intent, flags)
            } else {
                PendingIntent.getService(context, request, intent, flags)
            }
        }

        fun cancelSchedule(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.let {
                it.cancel(pending(context, REQUEST_POLL))
                it.cancel(pending(context, REQUEST_WATCHDOG))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogArmed = false
    private lateinit var graph: AppGraph
    private lateinit var engine: MonitorEngine

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        engine = MonitorEngine(graph)
        Notifier.createChannels(this)

        val first = graph.state().snapshot.takeIf { it.hasData }
            ?.let { Notifier.status(this, it) }
            ?: Notifier.starting(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.STATUS_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.STATUS_ID, first)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            AlarmPlayer.stop()
            Notifier.cancelAlarm(this)
        }

        // A late alarm or a notification button can wake us after monitoring was switched off.
        if (!graph.settings.load().monitoring) {
            cancelSchedule(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Every call counts against the Solarman allowance, so wake-ups that can't learn anything
        // new (a healthy chain, a second tap on refresh) don't ask again.
        val sinceLastPoll = System.currentTimeMillis() - graph.state().lastPollAt
        when (intent?.action) {
            ACTION_STOP_ALARM -> Unit
            ACTION_WATCHDOG -> {
                val expected = computeDelay()
                if (sinceLastPoll > expected + WATCHDOG_GRACE_MS) runPoll()
                else scheduleNext((expected - sinceLastPoll).coerceAtLeast(5_000L))
            }
            ACTION_REFRESH -> if (sinceLastPoll in 0 until REFRESH_MIN_GAP_MS) Widgets.refresh(this) else runPoll()
            else -> runPoll()
        }
        return START_STICKY
    }

    // ---------------- scheduling ----------------

    private fun runPoll() {
        armWatchdog()
        if (pollJob?.isActive == true) return

        // Armed before any work. If this poll crashes or the process dies mid-flight, the chain
        // still has its next link; the call at the end replaces it.
        scheduleNext(computeDelay())

        val wake = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarGrid:poll")
            ?.apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }

        pollJob = scope.launch {
            _busy.value = true
            try {
                val demo = graph.settings.load().demoMode
                if (!demo && !hasUsableNetwork()) {
                    awaitNetwork()
                    scheduleNext(PollPolicy.OFFLINE_FALLBACK_MS)
                    return@launch
                }

                val outcome = engine.pollOnce()
                deliver(outcome)
                scheduleNext(computeDelay())
                if (!demo && !outcome.failed) importHistoryIfDue()
            } catch (e: Exception) {
                e.printStackTrace()
                scheduleNext(computeDelay())
            } finally {
                _busy.value = false
                if (wake?.isHeld == true) wake.release()
            }
        }
    }

    private suspend fun deliver(outcome: PollOutcome) {
        val settings = graph.settings.load()
        val plan = outcome.plan

        if (plan.stopAlarm) {
            AlarmPlayer.stop()
            Notifier.cancelAlarm(this)
            Notifier.cancel(this, AlertKind.LOW_BATTERY, AlertKind.CRITICAL_BATTERY, AlertKind.OVERLOAD, AlertKind.OUTAGE)
        }
        if (plan.outageBegan != null) Notifier.cancel(this, AlertKind.RESTORED, AlertKind.HEADS_UP)

        for (alert in plan.alerts) {
            if (alert.ring) AlarmPlayer.start(this, settings.alerts.ringInSilentMode, settings.alerts.ringSeconds * 1000L)
            Notifier.post(this, alert)
        }

        Notifier.updateStatus(this, Notifier.status(this, outcome.snapshot))
        Widgets.refresh(this)
        StatusTile.refresh(this)

        val relayPrefs = graph.state().prefs
        for (alert in plan.alerts) {
            val text = alert.relay ?: continue
            try {
                Relay.send(settings.relay, relayPrefs, text, "${alert.kind.name.lowercase()}-${System.currentTimeMillis()}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (plan.alerts.none { it.relay != null }) {
            try {
                Relay.drain(settings.relay, relayPrefs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** First real poll on an account: pull the last fortnight from Solarman, once. */
    private fun importHistoryIfDue() {
        scope.launch {
            val wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SolarGrid:import")
                ?.apply { setReferenceCounted(false); acquire(IMPORT_WAKE_MS) }
            try {
                HistoryImport(graph).runIfDue()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (wake?.isHeld == true) wake.release()
            }
        }
    }

    private fun computeDelay(): Long {
        val state = graph.state()
        val monitor = state.monitor
        return PollPolicy.nextDelayMs(
            gridDown = monitor.outage.committedDown,
            msSinceStateChange = if (monitor.stateChangedAt > 0L) System.currentTimeMillis() - monitor.stateChangedAt else Long.MAX_VALUE,
            errorStreak = state.errorStreak,
            learnedUploadMs = state.uploadIntervalMs,
            msSinceInverterUpload = state.snapshot.inverterAgeMillis(),
            quotaExhausted = state.quotaHitAt > 0L
        )
    }

    private fun scheduleNext(delayMs: Long) {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + delayMs
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(this, REQUEST_POLL))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(this, REQUEST_POLL))
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(this, REQUEST_POLL))
        }
    }

    private fun armWatchdog() {
        if (watchdogArmed) return
        getSystemService(AlarmManager::class.java)?.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pending(this, REQUEST_WATCHDOG)
        )
        watchdogArmed = true
    }

    // ---------------- network ----------------

    private fun connectivity() = getSystemService(ConnectivityManager::class.java)

    private fun hasUsableNetwork(): Boolean {
        val manager = connectivity() ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun awaitNetwork() {
        if (networkCallback != null) return
        val manager = connectivity() ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                releaseNetworkCallback()
                runPoll()
            }
        }
        networkCallback = callback

        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    private fun releaseNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivity()?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        releaseNetworkCallback()
        pollJob?.cancel()
        scope.cancel()
        _busy.value = false
        if (!graph.settings.load().monitoring) cancelSchedule(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
