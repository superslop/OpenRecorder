// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

/** Enforces a full-frame gap when a vendor encoder repeats presentation timestamps. */
class VideoTimestampNormalizer private constructor() {
    companion object {
        @JvmStatic
        fun frameIntervalUs(frameRate: Int): Long {
            if (frameRate <= 0) {
                throw IllegalArgumentException("Frame rate must be positive")
            }
            return Math.max(1L, 1_000_000L / frameRate.toLong())
        }

        @JvmStatic
        fun ensureFrameSpacing(candidateUs: Long, previousUs: Long, frameRate: Int): Long {
            if (previousUs < 0L || candidateUs > previousUs) {
                return candidateUs
            }
            val intervalUs: Long = frameIntervalUs(frameRate)
            if (previousUs > Long.MAX_VALUE - intervalUs) {
                return Long.MAX_VALUE
            }
            return previousUs + intervalUs
        }
    }
}
