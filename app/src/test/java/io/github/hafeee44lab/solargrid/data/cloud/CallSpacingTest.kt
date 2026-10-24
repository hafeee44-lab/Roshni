package io.github.hafeee44lab.solargrid.data.cloud

import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.ReadingSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CallSpacingTest {

    private class Counting : ReadingSource {
        var calls = 0
        override suspend fun fetch(): FetchResult = FetchResult.Failed("call ${++calls}")
    }

    @Test
    fun `a second fetch inside the gap gets the last answer without a call`() = runBlocking {
        var now = 0L
        val inner = Counting()
        val source = SpacedSource(inner, CallSpacing(5 * 60_000L) { now })

        val first = source.fetch()
        now += 60_000L
        assertSame(first, source.fetch())
        assertEquals(1, inner.calls)

        now += 4 * 60_000L
        source.fetch()
        assertEquals(2, inner.calls)
    }

    @Test
    fun `a clock that jumps back does not block calls forever`() = runBlocking {
        var now = 10 * 60_000L
        val inner = Counting()
        val source = SpacedSource(inner, CallSpacing(5 * 60_000L) { now })
        source.fetch()
        now = 0L
        source.fetch()
        assertEquals(2, inner.calls)
    }
}
