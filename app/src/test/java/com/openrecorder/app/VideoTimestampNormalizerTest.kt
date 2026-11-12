// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTimestampNormalizerTest {
    @Test
    fun repeatedTimestampAdvancesByACompleteFrame() {
        assertEquals(16_666L, VideoTimestampNormalizer.frameIntervalUs(60))
        assertEquals(
            1_016_666L,
            VideoTimestampNormalizer.ensureFrameSpacing(1_000_000L, 1_000_000L, 60),
        )
    }

    @Test
    fun naturallyIncreasingTimestampIsPreserved() {
        assertEquals(
            1_010_000L,
            VideoTimestampNormalizer.ensureFrameSpacing(1_010_000L, 1_000_000L, 60),
        )
    }
}
