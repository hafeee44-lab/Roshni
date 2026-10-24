package io.github.hafeee44lab.solargrid.data

import android.content.Context
import android.content.SharedPreferences
import io.github.hafeee44lab.solargrid.data.cloud.BrandAccountStore
import io.github.hafeee44lab.solargrid.data.cloud.CallSpacing
import io.github.hafeee44lab.solargrid.data.cloud.CloudBrand
import io.github.hafeee44lab.solargrid.data.cloud.CloudSources
import io.github.hafeee44lab.solargrid.data.cloud.SpacedSource
import io.github.hafeee44lab.solargrid.data.cloud.StateSessionCache
import io.github.hafeee44lab.solargrid.data.demo.DemoInverter
import io.github.hafeee44lab.solargrid.data.local.LocalSource
import io.github.hafeee44lab.solargrid.data.local.LoggerStore
import io.github.hafeee44lab.solargrid.data.local.ProfileCatalog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The app's single source of truth. Everything — service, screens, widget, tile — reads and
 * writes through here, and screens observe changes instead of listening for broadcasts.
 */
class AppGraph private constructor(context: Context) {

    val app: Context = context.applicationContext

    val settings = SettingsStore(app)
    val accounts = AccountStore(app)
    val loggers = LoggerStore(app)
    val brandAccounts = BrandAccountStore(app)

    /** The bundled inverter register maps for the local-network source. */
    val inverterProfiles: ProfileCatalog by lazy { ProfileCatalog.fromAssets(app.assets) }

    private val states = HashMap<Profile, StateStore>()
    private val histories = HashMap<Profile, HistoryDb>()

    @Synchronized
    fun state(profile: Profile = settings.load().profile): StateStore =
        states.getOrPut(profile) { StateStore(app, profile) }

    @Synchronized
    fun history(profile: Profile = settings.load().profile): HistoryDb =
        histories.getOrPut(profile) { HistoryDb(app, profile) }

    val demo: DemoInverter by lazy {
        DemoInverter(
            forcedOutage = {
                val until = settings.prefs.getLong(DEMO_FORCED_UNTIL, 0L)
                val from = settings.prefs.getLong(DEMO_FORCED_FROM, 0L)
                if (until > System.currentTimeMillis()) from until until else null
            },
            changedAt = { settings.prefs.getLong(DEMO_CHANGED_AT, 0L) }
        )
    }

    /** Demo mode: make the simulated grid fail now, for [minutes], to try the alarm end to end. */
    fun forceDemoOutage(minutes: Int) {
        val now = System.currentTimeMillis()
        settings.prefs.edit()
            .putLong(DEMO_FORCED_FROM, now - 1_000L)
            .putLong(DEMO_FORCED_UNTIL, now + minutes * 60_000L)
            .putLong(DEMO_CHANGED_AT, now)
            .commit()
    }

    fun endDemoOutage() {
        settings.prefs.edit()
            .remove(DEMO_FORCED_FROM)
            .remove(DEMO_FORCED_UNTIL)
            .putLong(DEMO_CHANGED_AT, System.currentTimeMillis())
            .commit()
    }

    val demoOutageActive: Boolean
        get() = settings.prefs.getLong(DEMO_FORCED_UNTIL, 0L) > System.currentTimeMillis()

    /** Fill the demo database with two weeks of believable history, once. */
    fun seedDemoIfEmpty() {
        val db = history(Profile.DEMO)
        if (db.observedSince() != null) return
        val (samples, outages, days) = demo.history(days = 16, now = System.currentTimeMillis())
        db.addSamples(samples)
        db.addOutages(outages)
        db.addDays(days)
    }

    /**
     * Demo mode should look like a system that never stopped. If the app was not running for a
     * while, fill the gap with what the simulated inverter would have done in the meantime.
     */
    fun backfillDemo(now: Long = System.currentTimeMillis()) {
        val db = history(Profile.DEMO)
        val last = db.lastSampleAt() ?: return
        val minGap = 3 * DemoInverter.STEP_MS

        val windows = mutableListOf<Pair<Long, Long>>()
        if (!demoGapsScanned) {
            demoGapsScanned = true
            windows += db.gaps(since = now - 16 * DAY_MS, minGap = minGap)
        }
        if (now - last >= minGap) windows += last to now

        val open = db.openOutage()
        for ((from, to) in windows) {
            val (samples, outages, days) = demo.history(from = from, now = to - DemoInverter.STEP_MS)
            db.addSamples(samples)
            db.addOutages(outages.filter { o -> open == null || o.endedAt!! < open.startedAt })
            db.addDays(days)
        }
    }

    @Volatile
    private var demoGapsScanned = false

    fun readingSource(): ReadingSource? {
        val s = settings.load()
        if (s.demoMode) return DemoSource(demo)
        return when (s.source) {
            DataSource.LOCAL -> loggers.load()?.let { LocalSource(it, inverterProfiles) }
            DataSource.CLOUD -> cloudSource(s.cloudBrand)
        }
    }

    private val spacings = HashMap<String, CallSpacing>()

    private fun cloudSource(brand: CloudBrand): ReadingSource? {
        if (brand == CloudBrand.SOLARMAN) return accounts.load()?.let { SolarmanSource(it, state(Profile.LIVE)) }
        val account = brandAccounts.load()?.takeIf { it.brand == brand } ?: return null
        val source = CloudSources.create(account, StateSessionCache(state(Profile.LIVE), brand)) ?: return null
        // Sources are made fresh for every poll, so the spacing that guards the brand's rate limit lives here.
        val spacing = synchronized(spacings) {
            spacings.getOrPut("${brand.name}|${account.device}") { CallSpacing(brand.minPollMs) }
        }
        return SpacedSource(source, spacing)
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val DEMO_FORCED_FROM = "demo_forced_from"
        private const val DEMO_FORCED_UNTIL = "demo_forced_until"
        private const val DEMO_CHANGED_AT = "demo_changed_at"

        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}

/** Emits once immediately and again whenever any key in these prefs changes. */
fun SharedPreferences.changes(): Flow<Unit> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
    registerOnSharedPreferenceChangeListener(listener)
    trySend(Unit)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.conflate()
