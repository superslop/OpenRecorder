// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTimelineTest {
    @Test
    fun preservesAudioAndVideoStartOffsetsOnOneClock() {
        val timeline = RecordingTimeline(1_000_000_000L)

        assertEquals(8_000L, timeline.toPresentationTimeUs(1_008_000_000L))
        assertEquals(27_000L, timeline.toPresentationTimeUs(1_027_000_000L))
    }

    @Test
    fun removesTheSamePauseFromEveryTrack() {
        val timeline = RecordingTimeline(1_000_000_000L)
        timeline.pause(3_000_000_000L)
        timeline.resume(5_000_000_000L)

        assertFalse(timeline.shouldInclude(4_000_000_000L))
        assertTrue(timeline.shouldInclude(6_000_000_000L))
        assertEquals(3_000_000L, timeline.toPresentationTimeUs(6_000_000_000L))
    }

    @Test
    fun rejectsSamplesPastTheStopBoundary() {
        val timeline = RecordingTimeline(1_000_000_000L)
        timeline.stop(6_000_000_000L)

        assertTrue(timeline.shouldInclude(6_000_000_000L))
        assertFalse(timeline.shouldInclude(6_000_000_001L))
        assertEquals(5_000_000L, timeline.toPresentationTimeUs(7_000_000_000L))
    }

    @Test
    fun findsOnlyAudioRangesOutsideCompletedPauses() {
        val timeline = RecordingTimeline(1_000_000_000L)
        timeline.pause(1_025_000_000L)
        timeline.resume(1_060_000_000L)

        val beforePause = timeline.findNextIncludedRange(
            1_000_000_000L,
            1_080_000_000L,
        ) ?: error("expected an included range before the pause")
        assertEquals(1_000_000_000L, beforePause.startNanos)
        assertEquals(1_025_000_000L, beforePause.endNanos)

        val afterPause = timeline.findNextIncludedRange(
            beforePause.endNanos,
            1_080_000_000L,
        ) ?: error("expected an included range after the pause")
        assertEquals(1_060_000_000L, afterPause.startNanos)
        assertEquals(1_080_000_000L, afterPause.endNanos)
    }

    @Test
    fun excludesTheTailOfABufferCapturedAtAnActivePauseBoundary() {
        val timeline = RecordingTimeline(1_000_000_000L)
        timeline.pause(1_025_000_000L)

        val included = timeline.findNextIncludedRange(
            1_010_000_000L,
            1_050_000_000L,
        ) ?: error("expected an included range")
        assertEquals(1_010_000_000L, included.startNanos)
        assertEquals(1_025_000_000L, included.endNanos)
        assertEquals(
            null,
            timeline.findNextIncludedRange(
                included.endNanos,
                1_050_000_000L,
            ),
        )
    }
}
