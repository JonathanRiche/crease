package app.crease.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class DialMathTest {
    @Test fun flapsAlwaysTurnForwards() {
        assertEquals(1, flapSteps('a', 'b'))
        assertEquals(0, flapSteps('g', 'g'))
        assertEquals(FLAP_CHARS.length - 1, flapSteps('b', 'a'))
        assertEquals('c', flapChar('a', 2))
        assertEquals('a', flapChar('a', FLAP_CHARS.length))
    }

    @Test fun boardTextFitsTheFlaps() {
        assertEquals("maps  ", boardText("maps", 6))
        assertEquals("youtu.", boardText("youtube", 6))
    }

    @Test fun dragsMoveOneItemPerStepAndStopAtTheEnds() {
        assertEquals(2, dragIndex(0, 90f, 44f, 5))
        assertEquals(0, dragIndex(1, -200f, 44f, 5))
        assertEquals(4, dragIndex(3, 900f, 44f, 5))
        assertEquals(0, dragIndex(0, 50f, 44f, 0))
    }

    @Test fun nearestCentreWins() {
        assertEquals(1, nearestIndex(48f, listOf(0f, 50f, 120f)))
        assertEquals(2, nearestIndex(500f, listOf(0f, 50f, 120f)))
    }

    @Test fun ticksAreShortAndNeverClip() {
        TickKind.values().forEach { kind ->
            val samples = tickSamples(kind, 44100) { 1.0 }
            assert(samples.size in 400..2500) { "$kind is ${samples.size} samples" }
            assert(samples.all { it in -Short.MAX_VALUE..Short.MAX_VALUE })
            // Each click fades out rather than stopping abruptly.
            val tail = samples.takeLast(20).maxOf { kotlin.math.abs(it.toInt()) }
            assert(tail < Short.MAX_VALUE / 8) { "$kind ends at $tail" }
        }
    }
}
