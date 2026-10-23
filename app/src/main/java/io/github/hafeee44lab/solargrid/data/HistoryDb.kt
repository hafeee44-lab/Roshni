package io.github.hafeee44lab.solargrid.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.hafeee44lab.solargrid.domain.DailyEnergy
import io.github.hafeee44lab.solargrid.domain.Event
import io.github.hafeee44lab.solargrid.domain.EventKind
import io.github.hafeee44lab.solargrid.domain.OutageRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One point on the day chart, recorded each time the inverter uploads. */
data class Sample(
    val at: Long,
    val soc: Double?,
    val solarW: Double?,
    val loadW: Double?,
    val gridW: Double?,
    val batteryW: Double?,
    val gridDown: Boolean?
)

data class DayEnergy(val dayStart: Long, val energy: DailyEnergy)

/**
 * Local history: plain SQLite, no annotation processing, nothing to go wrong in the build.
 * Each [Profile] gets its own file so demo data can never leak into real history.
 */
class HistoryDb(context: Context, profile: Profile) :
    SQLiteOpenHelper(context, "history_${profile.suffix}.db", null, VERSION) {

    private val version = MutableStateFlow(0L)

    /** Bumps after every write so screens can re-query. */
    val changes: StateFlow<Long> = version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (t INTEGER PRIMARY KEY, soc REAL, solar REAL, load REAL, " +
                "grid REAL, battery REAL, down INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE outages (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, " +
                "ended INTEGER, start_soc REAL, end_soc REAL)"
        )
        db.execSQL("CREATE INDEX outages_started ON outages(started)")
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, " +
                "kind TEXT NOT NULL, text TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX events_t ON events(t)")
        db.execSQL(
            "CREATE TABLE days (day INTEGER PRIMARY KEY, produced REAL, consumed REAL, bought REAL, " +
                "sold REAL, charged REAL, discharged REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun touched() {
        version.value = version.value + 1
    }

    // ---------------- samples ----------------

    fun addSample(sample: Sample) {
        writableDatabase.insertWithOnConflict("samples", null, sample.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        touched()
    }

    fun addSamples(samples: List<Sample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            samples.forEach { db.insertWithOnConflict("samples", null, it.toValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touched()
    }

    fun samples(from: Long, to: Long = Long.MAX_VALUE): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT t, soc, solar, load, grid, battery, down FROM samples WHERE t >= ? AND t <= ? ORDER BY t",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Sample(
                            at = c.getLong(0),
                            soc = c.doubleOrNull(1),
                            solarW = c.doubleOrNull(2),
                            loadW = c.doubleOrNull(3),
                            gridW = c.doubleOrNull(4),
                            batteryW = c.doubleOrNull(5),
                            gridDown = if (c.isNull(6)) null else c.getInt(6) == 1
                        )
                    )
                }
            }
        }

    private fun Sample.toValues() = ContentValues().apply {
        put("t", at)
        put("soc", soc)
        put("solar", solarW)
        put("load", loadW)
        put("grid", gridW)
        put("battery", batteryW)
        put("down", gridDown?.let { if (it) 1 else 0 })
    }

    // ---------------- outages ----------------

    fun beginOutage(at: Long, soc: Double?) {
        if (openOutage() != null) return
        writableDatabase.insert("outages", null, ContentValues().apply {
            put("started", at)
            put("start_soc", soc)
        })
        touched()
    }

    fun endOutage(at: Long, soc: Double?) {
        val open = openOutage() ?: return
        writableDatabase.update(
            "outages",
            ContentValues().apply {
                put("ended", maxOf(at, open.startedAt))
                put("end_soc", soc)
            },
            "id = ?",
            arrayOf(open.id.toString())
        )
        touched()
    }

    fun addOutages(records: List<OutageRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach {
                db.insert("outages", null, ContentValues().apply {
                    put("started", it.startedAt)
                    put("ended", it.endedAt)
                    put("start_soc", it.startSoc)
                    put("end_soc", it.endSoc)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touched()
    }

    fun openOutage(): OutageRecord? =
        queryOutages("SELECT id, started, ended, start_soc, end_soc FROM outages WHERE ended IS NULL ORDER BY started DESC LIMIT 1")
            .firstOrNull()

    /** Newest first. */
    fun outages(since: Long = 0L, limit: Int = 2000): List<OutageRecord> =
        queryOutages(
            "SELECT id, started, ended, start_soc, end_soc FROM outages " +
                "WHERE started >= ? OR ended IS NULL OR ended >= ? ORDER BY started DESC LIMIT $limit",
            arrayOf(since.toString(), since.toString())
        )

    private fun queryOutages(sql: String, args: Array<String> = emptyArray()): List<OutageRecord> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        OutageRecord(
                            id = c.getLong(0),
                            startedAt = c.getLong(1),
                            endedAt = if (c.isNull(2)) null else c.getLong(2),
                            startSoc = c.doubleOrNull(3),
                            endSoc = c.doubleOrNull(4)
                        )
                    )
                }
            }
        }

    // ---------------- events ----------------

    fun addEvents(events: List<Event>) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            events.forEach {
                db.insert("events", null, ContentValues().apply {
                    put("t", it.at)
                    put("kind", it.kind.name)
                    put("text", it.text)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touched()
    }

    fun events(limit: Int = 100): List<Event> =
        readableDatabase.rawQuery("SELECT t, kind, text FROM events ORDER BY t DESC, id DESC LIMIT $limit", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val kind = EventKind.entries.firstOrNull { it.name == c.getString(1) } ?: EventKind.INFO
                        add(Event(kind, c.getLong(0), c.getString(2)))
                    }
                }
            }

    // ---------------- daily energy ----------------

    /** Day counters only ever grow, so a later reading overwrites, but never with a gap. */
    fun recordDay(dayStart: Long, energy: DailyEnergy) {
        if (!energy.hasAny) return
        val current = days(dayStart, dayStart).firstOrNull()?.energy ?: DailyEnergy()

        fun pick(new: Double?, old: Double?): Double? = when {
            new == null -> old
            old == null -> new
            else -> maxOf(new, old)
        }

        val merged = DailyEnergy(
            producedKwh = pick(energy.producedKwh, current.producedKwh),
            consumedKwh = pick(energy.consumedKwh, current.consumedKwh),
            boughtKwh = pick(energy.boughtKwh, current.boughtKwh),
            soldKwh = pick(energy.soldKwh, current.soldKwh),
            chargedKwh = pick(energy.chargedKwh, current.chargedKwh),
            dischargedKwh = pick(energy.dischargedKwh, current.dischargedKwh)
        )
        writableDatabase.insertWithOnConflict("days", null, dayValues(dayStart, merged), SQLiteDatabase.CONFLICT_REPLACE)
        touched()
    }

    fun addDays(days: List<DayEnergy>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            days.forEach { db.insertWithOnConflict("days", null, dayValues(it.dayStart, it.energy), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touched()
    }

    private fun dayValues(dayStart: Long, e: DailyEnergy) = ContentValues().apply {
        put("day", dayStart)
        put("produced", e.producedKwh)
        put("consumed", e.consumedKwh)
        put("bought", e.boughtKwh)
        put("sold", e.soldKwh)
        put("charged", e.chargedKwh)
        put("discharged", e.dischargedKwh)
    }

    fun days(from: Long, to: Long = Long.MAX_VALUE): List<DayEnergy> =
        readableDatabase.rawQuery(
            "SELECT day, produced, consumed, bought, sold, charged, discharged FROM days " +
                "WHERE day >= ? AND day <= ? ORDER BY day",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DayEnergy(
                            c.getLong(0),
                            DailyEnergy(
                                producedKwh = c.doubleOrNull(1),
                                consumedKwh = c.doubleOrNull(2),
                                boughtKwh = c.doubleOrNull(3),
                                soldKwh = c.doubleOrNull(4),
                                chargedKwh = c.doubleOrNull(5),
                                dischargedKwh = c.doubleOrNull(6)
                            )
                        )
                    )
                }
            }
        }

    // ---------------- housekeeping ----------------

    /** Stretches with no readings longer than [minGap], as (last reading before, first reading after). */
    fun gaps(since: Long, minGap: Long): List<Pair<Long, Long>> =
        readableDatabase.rawQuery("SELECT t FROM samples WHERE t >= ? ORDER BY t", arrayOf(since.toString())).use { c ->
            buildList {
                var previous = -1L
                while (c.moveToNext()) {
                    val t = c.getLong(0)
                    if (previous > 0 && t - previous > minGap) add(previous to t)
                    previous = t
                }
            }
        }

    fun lastSampleAt(): Long? =
        readableDatabase.rawQuery("SELECT MAX(t) FROM samples", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /** The earliest moment we have any record of, i.e. when watching began. */
    fun observedSince(): Long? {
        val db = readableDatabase
        val sample = db.rawQuery("SELECT MIN(t) FROM samples", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        val outage = db.rawQuery("SELECT MIN(started) FROM outages", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        return listOfNotNull(sample, outage).minOrNull()
    }

    fun prune(keepSamplesSince: Long) {
        writableDatabase.delete("samples", "t < ?", arrayOf(keepSamplesSince.toString()))
        writableDatabase.delete("events", "t < ?", arrayOf((keepSamplesSince - 60L * 24 * 3_600_000L).toString()))
    }

    fun clear() {
        val db = writableDatabase
        listOf("samples", "outages", "events", "days").forEach { db.delete(it, null, null) }
        touched()
    }

    private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private companion object {
        const val VERSION = 1
    }
}
