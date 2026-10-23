package io.github.hafeee44lab.solargrid.domain

/** One historical reading after replay, with the mains state the live monitor would have held. */
data class ReplayPoint(val at: Long, val reading: Reading, val gridDown: Boolean?)

data class ReplayResult(
    val points: List<ReplayPoint>,
    val outages: List<OutageRecord>,
    /** The last daily totals seen for each day, keyed by the day's start. */
    val days: Map<Long, DailyEnergy>
)

/**
 * Rebuilds past cuts from a run of historical inverter readings, using the same verdicts and
 * confirmation rules as the live monitor, so imported history and live history agree.
 */
object HistoryReplay {

    /** [frames] are (collection time, reading); anything at or after [cutoff] is left to live history. */
    fun replay(frames: List<Pair<Long, Reading>>, cutoff: Long): ReplayResult {
        var state = OutageState()
        // When the run that is waiting for confirmation began: an inferred cut is confirmed on
        // its second reading, but it started at the first.
        var runStart: Long? = null
        var open: OutageRecord? = null
        var lastAt = 0L

        val points = mutableListOf<ReplayPoint>()
        val outages = mutableListOf<OutageRecord>()
        val days = linkedMapOf<Long, DailyEnergy>()

        for ((at, reading) in frames.sortedBy { it.first }) {
            if (at >= cutoff) break
            if (at == lastAt) continue
            lastAt = at
            days[OutageLog.startOfDay(at)] = reading.today

            val verdict = GridAnalyzer.evaluate(reading)
            if (verdict != null) {
                if (state.pendingDown != verdict.gridDown || state.pendingCount == 0) runStart = at
                val transition = OutageStateMachine.apply(state, verdict)
                state = transition.state

                val down = when (transition) {
                    is Transition.Announce -> transition.down
                    is Transition.Baseline -> transition.down
                    is Transition.Hold -> null
                }
                val since = runStart ?: at
                if (down == true && open == null) {
                    open = OutageRecord(startedAt = since, startSoc = reading.soc)
                } else if (down == false && open != null) {
                    outages += open.copy(endedAt = since, endSoc = reading.soc)
                    open = null
                }
            }
            points += ReplayPoint(at, reading, state.committedDown)
        }

        // Still off when the imported stretch ends: close it at the last reading we have, and
        // let live monitoring take it from there.
        open?.let { o ->
            val last = points.lastOrNull()
            if (last != null && last.at > o.startedAt) outages += o.copy(endedAt = last.at, endSoc = last.reading.soc)
        }

        return ReplayResult(points, outages, days)
    }
}
