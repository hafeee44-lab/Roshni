package io.github.hafeee44lab.solargrid.domain

/**
 * Decides when a run of readings has earned a change of state.
 *
 * Every field here is persisted rather than held in memory: the monitoring service is
 * deliberately killable between polls, so a counter kept in a field silently resets on
 * every restart and a run of two can never complete.
 */
data class OutageState(
    val committedDown: Boolean? = null,
    val pendingDown: Boolean? = null,
    val pendingCount: Int = 0
)

sealed interface Transition {
    val state: OutageState

    /** The state changed and the change is worth telling someone about. */
    data class Announce(val down: Boolean, override val state: OutageState) : Transition

    /** First reading after install. Establishes a baseline without shouting about it. */
    data class Baseline(val down: Boolean, override val state: OutageState) : Transition

    data class Hold(override val state: OutageState) : Transition
}

object OutageStateMachine {

    const val INFERRED_CONFIRMATIONS = 2

    fun confirmationsRequired(confidence: Confidence): Int =
        if (confidence == Confidence.DEFINITIVE) 1 else INFERRED_CONFIRMATIONS

    fun apply(state: OutageState, verdict: Verdict): Transition {
        val down = verdict.gridDown

        val counted = if (state.pendingDown == down) {
            state.copy(pendingCount = state.pendingCount + 1)
        } else {
            state.copy(pendingDown = down, pendingCount = 1)
        }

        if (down == state.committedDown) {
            return Transition.Hold(counted)
        }

        if (counted.pendingCount < confirmationsRequired(verdict.confidence)) {
            return Transition.Hold(counted)
        }

        val committed = counted.copy(committedDown = down)

        // Coming up for the first time is just the normal state of the world.
        // Coming up *down* for the first time is worth an alarm.
        return if (state.committedDown == null && !down) {
            Transition.Baseline(false, committed)
        } else {
            Transition.Announce(down, committed)
        }
    }
}
