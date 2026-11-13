package ai.nova.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaVoiceTimelineTest {
    private val rate = NovaVoiceTimeline.OUTPUT_RATE
    private val anchors = listOf(0, 5, 10, 15, 20, 25)

    @Test fun shortPhrasesStartSlightlyBeforeSceneEdgeWithoutOverlap() {
        val lengths = List(6) { 3 * rate }
        val starts = NovaVoiceTimeline.plan(anchors, lengths, 30 * rate)
        assertEquals(0, starts[0])
        assertEquals(5 * rate - rate * 3 / 5, starts[1])
        assertTrue(starts.last() + lengths.last() <= 30 * rate)
        for (i in 1 until starts.size)
            assertTrue(starts[i] >= starts[i - 1] + lengths[i - 1] + rate / 4)
    }

    @Test fun sentenceMayContinueAcrossNextSceneWithoutBeingClipped() {
        val lengths = listOf(3, 3, 3, 3, 3, 7).map { it * rate }
        val starts = NovaVoiceTimeline.plan(anchors, lengths, 30 * rate)
        assertTrue(starts.last() < 25 * rate)
        assertTrue(starts.last() + lengths.last() <= 30 * rate - rate / 10)
        for (i in 1 until starts.size)
            assertTrue(starts[i] >= starts[i - 1] + lengths[i - 1] + rate / 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesOverflowInsteadOfCuttingWords() {
        NovaVoiceTimeline.plan(anchors, List(6) { 6 * rate }, 30 * rate)
    }

    @Test fun keepsOneVoiceAtItsRequestedStart() {
        val starts = NovaVoiceTimeline.plan(listOf(0), listOf(4 * rate), 5 * rate)
        assertEquals(0, starts[0])
    }
}
