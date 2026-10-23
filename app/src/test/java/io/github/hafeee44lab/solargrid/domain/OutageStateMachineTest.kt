package io.github.hafeee44lab.solargrid.domain

import io.github.hafeee44lab.solargrid.data.api.SolarMetric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutageStateMachineTest {

    private fun definite(down: Boolean) = Verdict(down, "grid relay", Confidence.DEFINITIVE)
    private fun inferred(down: Boolean) = Verdict(down, "power balance", Confidence.INFERRED)

    @Test
    fun aDefinitiveOutageIsAnnouncedOnTheFirstReading() {
        val start = OutageState(committedDown = false)
        val result = OutageStateMachine.apply(start, definite(true))

        assertTrue(result is Transition.Announce)
        assertTrue((result as Transition.Announce).down)
        assertEquals(true, result.state.committedDown)
    }

    @Test
    fun anInferredOutageWaitsForASecondReading() {
        val start = OutageState(committedDown = false)

        val first = OutageStateMachine.apply(start, inferred(true))
        assertTrue(first is Transition.Hold)
        assertEquals(false, first.state.committedDown)

        val second = OutageStateMachine.apply(first.state, inferred(true))
        assertTrue(second is Transition.Announce)
        assertEquals(true, second.state.committedDown)
    }

    /**
     * The bug this class exists to prevent: the service is killed between polls, so the
     * run counter must survive in the state we hand back, not in a field.
     */
    @Test
    fun aRunSurvivesTheServiceBeingRestartedBetweenPolls() {
        var persisted = OutageState(committedDown = false)

        repeat(2) {
            // Each iteration stands for a fresh process that has only the stored state.
            val restarted = persisted.copy()
            persisted = OutageStateMachine.apply(restarted, inferred(true)).state
        }

        assertEquals(true, persisted.committedDown)
    }

    @Test
    fun flappingReadingsNeverReachAConfirmation() {
        var state = OutageState(committedDown = false)

        listOf(true, false, true, false, true).forEach {
            val result = OutageStateMachine.apply(state, inferred(it))
            assertTrue(result is Transition.Hold)
            state = result.state
        }

        assertEquals(false, state.committedDown)
    }

    @Test
    fun aHealthyFirstReadingSetsTheBaselineQuietly() {
        val result = OutageStateMachine.apply(OutageState(), definite(false))

        assertTrue(result is Transition.Baseline)
        assertEquals(false, result.state.committedDown)
    }

    @Test
    fun installingDuringAnOutageStillRaisesTheAlarm() {
        val result = OutageStateMachine.apply(OutageState(), definite(true))

        assertTrue(result is Transition.Announce)
        assertEquals(true, result.state.committedDown)
    }

    @Test
    fun anUnchangedStateIsNeverReAnnounced() {
        var state = OutageState(committedDown = true)

        repeat(5) {
            val result = OutageStateMachine.apply(state, definite(true))
            assertTrue(result is Transition.Hold)
            state = result.state
        }
    }

    @Test
    fun restorationIsAnnouncedToo() {
        val result = OutageStateMachine.apply(OutageState(committedDown = true), definite(false))

        assertTrue(result is Transition.Announce)
        assertEquals(false, (result as Transition.Announce).down)
    }

    @Test
    fun theRelayFromTheRealDumpAnnouncesImmediately() {
        val metrics = listOf(
            SolarMetric("GRID_RELAY_ST1", "Grid Relay Status", "Break", ""),
            SolarMetric("AV1", "AC Voltage R/U/A", "229.90", "V"),
            SolarMetric("A_Fo1", "AC Output Frequency R", "50.00", "Hz")
        )

        val verdict = GridAnalyzer.evaluate(GridAnalyzer.read(metrics))!!
        assertEquals(Confidence.DEFINITIVE, verdict.confidence)

        val result = OutageStateMachine.apply(OutageState(committedDown = false), verdict)
        assertTrue(result is Transition.Announce)
    }
}
