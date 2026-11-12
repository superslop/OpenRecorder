// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameRatePolicyTest {
    @Test
    fun everySupportedPreferenceIsPreserved() {
        val supportedFrameRates = intArrayOf(
            RecordingOptions.VIDEO_FRAME_RATE_AUTO,
            RecordingOptions.VIDEO_FRAME_RATE_120_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_90_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_60_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_30_FPS,
        )

        for (frameRate in supportedFrameRates) {
            assertEquals(frameRate, RecordingOptions.normalizeVideoFrameRate(frameRate))
        }
    }

    @Test
    fun invalidPreferenceFallsBackToAuto() {
        assertEquals(
            RecordingOptions.VIDEO_FRAME_RATE_AUTO,
            RecordingOptions.normalizeVideoFrameRate(Int.MAX_VALUE),
        )
    }

    @Test
    fun automaticModeTracksTheDisplayUsingKnownProfiles() {
        assertEquals(120, VideoFrameRatePolicy.resolvePreferredFrameRate(0, 144f))
        assertEquals(120, VideoFrameRatePolicy.resolvePreferredFrameRate(0, 119.94f))
        assertEquals(90, VideoFrameRatePolicy.resolvePreferredFrameRate(0, 90f))
        assertEquals(60, VideoFrameRatePolicy.resolvePreferredFrameRate(0, 75f))
        assertEquals(30, VideoFrameRatePolicy.resolvePreferredFrameRate(0, 24f))
        assertEquals(30, VideoFrameRatePolicy.resolvePreferredFrameRate(0, Float.NaN))
    }

    @Test
    fun fixedRateFallsBackWithoutExceedingTheRequest() {
        assertArrayEquals(
            intArrayOf(120, 90, 60, 30),
            VideoFrameRatePolicy.fallbackCandidates(120, 60f),
        )
        assertArrayEquals(
            intArrayOf(90, 60, 30),
            VideoFrameRatePolicy.fallbackCandidates(90, 120f),
        )
        assertArrayEquals(
            intArrayOf(30),
            VideoFrameRatePolicy.fallbackCandidates(30, 120f),
        )
    }

    @Test
    fun automaticFallbacksStartAtTheDisplayRate() {
        assertArrayEquals(
            intArrayOf(60, 30),
            VideoFrameRatePolicy.fallbackCandidates(0, 60f),
        )
    }
}
