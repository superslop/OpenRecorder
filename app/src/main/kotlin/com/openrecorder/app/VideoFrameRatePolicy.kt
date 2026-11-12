// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

/** Resolves user frame-rate choices into safe encoder fallback candidates. */
class VideoFrameRatePolicy private constructor() {
    companion object {
        private const val DISPLAY_RATE_TOLERANCE_FPS = 1.0f
        private val SUPPORTED_RATES_DESCENDING: IntArray = intArrayOf(
            RecordingOptions.VIDEO_FRAME_RATE_120_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_90_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_60_FPS,
            RecordingOptions.VIDEO_FRAME_RATE_30_FPS,
        )

        @JvmStatic
        fun resolvePreferredFrameRate(requestedFrameRate: Int, sourceRefreshRate: Float): Int {
            val normalized: Int = RecordingOptions.normalizeVideoFrameRate(requestedFrameRate)
            if (normalized != RecordingOptions.VIDEO_FRAME_RATE_AUTO) {
                return normalized
            }
            if (!java.lang.Float.isFinite(sourceRefreshRate) || sourceRefreshRate <= 0f) {
                return RecordingOptions.VIDEO_FRAME_RATE_30_FPS
            }
            for (frameRate in SUPPORTED_RATES_DESCENDING) {
                if (sourceRefreshRate + DISPLAY_RATE_TOLERANCE_FPS >= frameRate.toFloat()) {
                    return frameRate
                }
            }
            return RecordingOptions.VIDEO_FRAME_RATE_30_FPS
        }

        @JvmStatic
        fun fallbackCandidates(requestedFrameRate: Int, sourceRefreshRate: Float): IntArray {
            val preferredFrameRate: Int =
                resolvePreferredFrameRate(requestedFrameRate, sourceRefreshRate)
            var candidateCount = 0
            for (frameRate in SUPPORTED_RATES_DESCENDING) {
                if (frameRate <= preferredFrameRate) {
                    candidateCount++
                }
            }

            val candidates = IntArray(candidateCount)
            var index = 0
            for (frameRate in SUPPORTED_RATES_DESCENDING) {
                if (frameRate <= preferredFrameRate) {
                    candidates[index++] = frameRate
                }
            }
            return candidates
        }
    }
}
