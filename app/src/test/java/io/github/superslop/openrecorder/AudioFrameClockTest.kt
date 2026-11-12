// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFrameClockTest {
    @Test
    fun mapsCapturedFramesToTheMonotonicTimestamp() {
        val timestampNanos = 3_600_012_000_000L
        val timestampFrame = 172_800_000L

        assertEquals(
            timestampNanos - 85_333_333L,
            AudioFrameClock.frameTimeNanos(
                timestampFrame,
                timestampNanos,
                timestampFrame - 4_096L,
                48_000,
            ),
        )
    }

    @Test
    fun handlesNegativeAndPositiveFrameDeltas() {
        assertEquals(1_000_000_000L, AudioFrameClock.framesToNanos(48_000L, 48_000))
        assertEquals(-1_000_000_000L, AudioFrameClock.framesToNanos(-48_000L, 48_000))
        assertEquals(10_000_000L, AudioFrameClock.framesToNanos(441L, 44_100))
    }

    @Test
    fun roundsPauseBoundariesUpToTheNextCompleteSample() {
        assertEquals(1L, AudioFrameClock.nanosToFramesCeil(1L, 48_000))
        assertEquals(480L, AudioFrameClock.nanosToFramesCeil(10_000_000L, 48_000))
        assertEquals(481L, AudioFrameClock.nanosToFramesCeil(10_000_001L, 48_000))
    }
}
