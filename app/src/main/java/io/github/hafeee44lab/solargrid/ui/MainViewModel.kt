package io.github.hafeee44lab.solargrid.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.Account
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.AppSettings
import io.github.hafeee44lab.solargrid.data.DataSource
import io.github.hafeee44lab.solargrid.data.DayEnergy
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.HistoryImport
import io.github.hafeee44lab.solargrid.data.Profile
import io.github.hafeee44lab.solargrid.data.Sample
import io.github.hafeee44lab.solargrid.data.SolarSnapshot
import io.github.hafeee44lab.solargrid.data.SolarmanSource
import io.github.hafeee44lab.solargrid.data.changes
import io.github.hafeee44lab.solargrid.data.local.FoundLogger
import io.github.hafeee44lab.solargrid.data.local.Detection
import io.github.hafeee44lab.solargrid.data.local.LocalLogger
import io.github.hafeee44lab.solargrid.data.local.ProfileCatalog
import io.github.hafeee44lab.solargrid.data.local.LocalSource
import io.github.hafeee44lab.solargrid.data.local.LoggerDiscovery
import io.github.hafeee44lab.solargrid.data.phrases
import io.github.hafeee44lab.solargrid.domain.Event
import io.github.hafeee44lab.solargrid.domain.HomeUi
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutagePredictor
import io.github.hafeee44lab.solargrid.domain.OutageRecord
import io.github.hafeee44lab.solargrid.domain.OutageSlot
import io.github.hafeee44lab.solargrid.domain.Prediction
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import io.github.hafeee44lab.solargrid.domain.Stretch
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.service.AlarmPlayer
import io.github.hafeee44lab.solargrid.service.MonitorEngine
import io.github.hafeee44lab.solargrid.service.MonitorService
import io.github.hafeee44lab.solargrid.service.Notifier
import io.github.hafeee44lab.solargrid.service.Relay
import io.github.hafeee44lab.solargrid.service.StatusTile
import io.github.hafeee44lab.solargrid.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Permissions(
    val notifications: Boolean = true,
    val fullScreen: Boolean = true,
    val unrestricted: Boolean = true,
    val exactAlarms: Boolean = true
) {
    /** The ones that can stop an outage alarm reaching you at all. */
    val blocking: Int get() = listOf(notifications, fullScreen, unrestricted).count { !it }
}

data class UiState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val account: Account? = null,
    val logger: LocalLogger? = null,
    val snapshot: SolarSnapshot = SolarSnapshot(),
    val home: HomeUi = buildHomeUi(SolarSnapshot(), false),
    val dump: String = "",
    val outages: List<OutageRecord> = emptyList(),
    val events: List<Event> = emptyList(),
    val todaySamples: List<Sample> = emptyList(),
    val days: List<DayEnergy> = emptyList(),
    val observedSince: Long? = null,
    val schedule: List<OutageSlot> = emptyList(),
    val prediction: Prediction? = null,
    val now: Long = System.currentTimeMillis(),
    val apiCallsToday: Int = 0,
    val apiCallsYesterday: Int? = null,
    val quotaExhausted: Boolean = false,
    /** Runtime at the household's usual low load, when cutting back would buy real time. */
    val stretch: Stretch? = null,
    /** "14 days imported, 9 cuts", or why the last import failed; null before any import. */
    val importNote: String? = null
)

private data class ProfileData(
    val snapshot: SolarSnapshot,
    val dump: String,
    val outages: List<OutageRecord>,
    val events: List<Event>,
    val todaySamples: List<Sample>,
    val days: List<DayEnergy>,
    val observedSince: Long?,
    val apiCallsToday: Int,
    val apiCallsYesterday: Int?,
    val quotaExhausted: Boolean,
    val typicalLowLoadW: Double?,
    val importNote: String?
)

private fun importNote(prefs: android.content.SharedPreferences, res: android.content.res.Resources): String? {
    val failed = prefs.getString(HistoryImport.FAILED_MESSAGE, null)
    if (failed != null) return res.getString(R.string.import_last_failed, failed)
    if (prefs.getLong(HistoryImport.DONE_AT, 0L) == 0L) return null
    val days = prefs.getInt(HistoryImport.DONE_DAYS, 0)
    return if (days == 0) res.getString(R.string.import_already_covered) else res.getString(R.string.import_done_note, days, prefs.getInt(HistoryImport.DONE_CUTS, 0))
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)
    private val engine = MonitorEngine(graph)

    private val _permissions = MutableStateFlow(Permissions())
    val permissions: StateFlow<Permissions> = _permissions

    val busy: StateFlow<Boolean> = MonitorService.busy
    val ringing: StateFlow<Boolean> = AlarmPlayer.ringing

    private val localBusy = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = combine(busy, localBusy) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private data class Sources(val settings: AppSettings, val account: Account?, val logger: LocalLogger?)

    private val settingsFlow: Flow<Sources> =
        combine(
            graph.settings.prefs.changes(),
            graph.app.getSharedPreferences("account", 0).changes(),
            graph.loggers.prefs.changes()
        ) { _, _, _ ->
            Sources(graph.settings.load(), graph.accounts.load(), graph.loggers.load())
        }.flowOn(Dispatchers.IO)

    private fun profileData(profile: Profile): Flow<ProfileData> {
        val state = graph.state(profile)
        val db = graph.history(profile)
        return combine(state.prefs.changes(), db.changes) { _, _ -> Unit }
            .map {
                val now = System.currentTimeMillis()
                val today = OutageLog.startOfDay(now)
                ProfileData(
                    snapshot = state.snapshot,
                    dump = state.dump,
                    outages = db.outages(since = today - 35 * OutageLog.DAY_MS),
                    events = db.events(80),
                    todaySamples = db.samples(today, today + OutageLog.DAY_MS),
                    days = db.days(today - 29 * OutageLog.DAY_MS),
                    observedSince = db.observedSince(),
                    apiCallsToday = state.apiCallsToday(now),
                    apiCallsYesterday = state.apiCallsYesterday(now),
                    quotaExhausted = state.quotaHitAt > 0L,
                    importNote = importNote(state.prefs, graph.app.resources),
                    typicalLowLoadW = RuntimeEstimator.typicalLowLoad(db.samples(now - 7 * OutageLog.DAY_MS).map { it.loadW })
                )
            }
            .flowOn(Dispatchers.IO)
    }

    val state: StateFlow<UiState> = settingsFlow
        .flatMapLatest { (settings, account, logger) ->
            combine(profileData(settings.profile), ticker) { data, _ ->
                // Read the clock here, not from the ticker: a reading newer than the last tick
                // would otherwise come out with a negative age.
                val now = System.currentTimeMillis()
                val since = data.observedSince ?: now
                UiState(
                    loaded = true,
                    settings = settings,
                    account = account,
                    logger = logger,
                    snapshot = data.snapshot,
                    home = buildHomeUi(data.snapshot, settings.monitoring, now, graph.app.phrases()),
                    dump = data.dump,
                    outages = data.outages,
                    events = data.events,
                    todaySamples = data.todaySamples,
                    days = data.days,
                    observedSince = data.observedSince,
                    schedule = OutagePredictor.schedule(data.outages, now, since),
                    prediction = OutagePredictor.next(data.outages, now, since),
                    now = now,
                    apiCallsToday = data.apiCallsToday,
                    apiCallsYesterday = data.apiCallsYesterday,
                    quotaExhausted = data.quotaExhausted,
                    importNote = data.importNote,
                    stretch = RuntimeEstimator.stretch(
                        soc = data.snapshot.soc,
                        capacityWh = data.snapshot.capacityWh,
                        currentLoadW = data.snapshot.runtimeLoadW ?: data.snapshot.loadPowerW,
                        lowLoadW = data.typicalLowLoadW,
                        reservePercent = settings.alerts.batteryReservePercent.toDouble()
                    )
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // ---------------- actions ----------------

    fun refresh() {
        val settings = graph.settings.load()
        if (settings.monitoring) {
            MonitorService.refresh(graph.app)
            return
        }
        // Not monitoring: still allow a one-off check so the screen is never stuck,
        // but not a second call to Solarman within seconds of the last one.
        if (System.currentTimeMillis() - graph.state(settings.profile).lastPollAt < 20_000L) return
        viewModelScope.launch(Dispatchers.IO) {
            localBusy.value = true
            try {
                engine.pollOnce()
                Widgets.refresh(graph.app)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                localBusy.value = false
            }
        }
    }

    fun setMonitoring(on: Boolean) {
        graph.settings.update { it.copy(monitoring = on) }
        if (on) MonitorService.start(graph.app) else MonitorService.stop(graph.app)
        Widgets.refresh(graph.app)
        StatusTile.refresh(graph.app)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch(Dispatchers.IO) { graph.settings.update(transform) }
    }

    fun setDemo(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (on) graph.seedDemoIfEmpty()
            graph.settings.update { it.copy(demoMode = on) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun simulateOutage(minutes: Int = 40) {
        graph.forceDemoOutage(minutes)
        refresh()
    }

    fun endSimulatedOutage() {
        graph.endDemoOutage()
        refresh()
    }

    val demoOutageActive: Boolean get() = graph.demoOutageActive

    fun testAlarm() {
        graph.settings.load().alerts.let { Notifier.testAlert(graph.app, it.ringInSilentMode, it.ringSeconds) }
    }

    fun stopAlarm() {
        AlarmPlayer.stop()
        Notifier.cancelAlarm(graph.app)
    }

    suspend fun discover(account: Account): Result<List<DiscoveredDevice>> =
        withContext(Dispatchers.IO) { SolarmanSource.discover(account) }

    fun saveAccount(account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.accounts.save(account)
            graph.state(Profile.LIVE).token = null
            graph.settings.update { it.copy(demoMode = false, source = DataSource.CLOUD) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.accounts.clear()
            graph.state(Profile.LIVE).clear()
            withContext(Dispatchers.Main) { setMonitoring(false) }
        }
    }

    /** One trial read, so setup can say straight away whether the logger answers. */
    suspend fun testLogger(logger: LocalLogger): FetchResult =
        withContext(Dispatchers.IO) { LocalSource(logger, graph.inverterProfiles).fetch() }

    /** Reads Deye's device-type register so the model picker can pre-select a profile. */
    suspend fun detectInverter(logger: LocalLogger): Detection =
        withContext(Dispatchers.IO) { LocalSource(logger, graph.inverterProfiles).detect() }

    val inverterProfiles: ProfileCatalog get() = graph.inverterProfiles

    suspend fun scanForLoggers(): List<FoundLogger> =
        try {
            LoggerDiscovery.scan(graph.app)
        } catch (e: Exception) {
            emptyList()
        }

    fun saveLogger(logger: LocalLogger) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.loggers.save(logger)
            graph.settings.update { it.copy(demoMode = false, source = DataSource.LOCAL) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    /** Switch between sources that are both already set up. */
    fun useSource(source: DataSource) {
        if (graph.settings.load().source == source) return
        viewModelScope.launch(Dispatchers.IO) {
            graph.settings.update { it.copy(demoMode = false, source = source) }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun forgetLogger() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.loggers.clear()
            val wasLocal = graph.settings.load().source == DataSource.LOCAL
            graph.settings.update { it.copy(source = DataSource.CLOUD) }
            if (wasLocal) withContext(Dispatchers.Main) { setMonitoring(false) }
        }
    }

    fun finishOnboarding(startMonitoring: Boolean) {
        graph.settings.update { it.copy(onboarded = true) }
        if (startMonitoring) setMonitoring(true) else refresh()
    }

    /** (days done, total) while history is being imported from Solarman. */
    val importProgress: StateFlow<Pair<Int, Int>?> = HistoryImport.progress

    fun importHistory(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = when (val outcome = HistoryImport(graph).runIfDue(force = true)) {
                is HistoryImport.Outcome.Imported -> graph.app.getString(R.string.import_done, outcome.days, outcome.cuts)
                is HistoryImport.Outcome.Failed -> outcome.message
                HistoryImport.Outcome.NothingToDo -> graph.app.getString(R.string.import_covers)
                HistoryImport.Outcome.Skipped -> graph.app.getString(R.string.import_busy)
            }
            withContext(Dispatchers.Main) { onDone(message) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = graph.settings.load().profile
            graph.history(profile).clear()
        }
    }

    suspend fun testRelay(): Boolean = withContext(Dispatchers.IO) {
        try {
            Relay.sendTest(graph.settings.load().relay, graph.app.getString(R.string.relay_test))
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadSamples(from: Long, to: Long): List<Sample> =
        withContext(Dispatchers.IO) { graph.history().samples(from, to) }

    /** Writes every outage to a CSV in the cache and returns a share intent for it. */
    suspend fun exportIntent(): Intent? = withContext(Dispatchers.IO) {
        val outages = graph.history().outages(limit = 100_000)
        if (outages.isEmpty()) return@withContext null

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val csv = buildString {
            appendLine("started,ended,duration_minutes,battery_start_percent,battery_end_percent")
            outages.sortedBy { it.startedAt }.forEach {
                append(format.format(Date(it.startedAt))).append(',')
                append(it.endedAt?.let { e -> format.format(Date(e)) } ?: "").append(',')
                append(it.durationMs() / 60_000L).append(',')
                append(it.startSoc?.let { s -> "%.0f".format(Locale.US, s) } ?: "").append(',')
                appendLine(it.endSoc?.let { s -> "%.0f".format(Locale.US, s) } ?: "")
            }
        }

        val dir = File(graph.app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "outages-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv")
        file.writeText(csv)

        val uri = FileProvider.getUriForFile(graph.app, "${graph.app.packageName}.files", file)
        Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, graph.app.getString(R.string.export_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun refreshPermissions() {
        val app = graph.app
        _permissions.value = Permissions(
            notifications = Notifier.canPost(app),
            fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false,
            unrestricted = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) != false,
            exactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false
        )
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(graph.app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
