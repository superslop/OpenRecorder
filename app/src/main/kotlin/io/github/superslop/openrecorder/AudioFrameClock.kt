// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

/** Converts AudioRecord frame positions to the monotonic clock used by video. */
class AudioFrameClock private constructor() {
    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        @JvmStatic
        fun frameTimeNanos(
            timestampFramePosition: Long,
            timestampNanos: Long,
            targetFramePosition: Long,
            sampleRate: Int,
        ): Long {
            return saturatingAdd(
                timestampNanos,
                framesToNanos(targetFramePosition - timestampFramePosition, sampleRate),
            )
        }

        @JvmStatic
        fun framesToNanos(frameCount: Long, sampleRate: Int): Long {
            if (sampleRate <= 0) {
                throw IllegalArgumentException("Sample rate must be positive")
            }
            val wholeSeconds: Long = frameCount / sampleRate.toLong()
            val remainingFrames: Long = frameCount % sampleRate.toLong()
            return saturatingAdd(
                saturatingMultiply(wholeSeconds, NANOS_PER_SECOND),
                remainingFrames * NANOS_PER_SECOND / sampleRate.toLong(),
            )
        }

        @JvmStatic
        fun nanosToFramesCeil(durationNanos: Long, sampleRate: Int): Long {
            if (sampleRate <= 0) {
                throw IllegalArgumentException("Sample rate must be positive")
            }
            if (durationNanos <= 0L) {
                return 0L
            }
            val wholeSeconds: Long = durationNanos / NANOS_PER_SECOND
            val remainingNanos: Long = durationNanos % NANOS_PER_SECOND
            val wholeFrames: Long = saturatingMultiply(wholeSeconds, sampleRate.toLong())
            val partialFrames: Long =
                (remainingNanos * sampleRate.toLong() + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND
            return saturatingAdd(wholeFrames, partialFrames)
        }

        private fun saturatingAdd(left: Long, right: Long): Long {
            try {
                return Math.addExact(left, right)
            } catch (ignored: ArithmeticException) {
                if (right >= 0L) {
                    return Long.MAX_VALUE
                } else {
                    return Long.MIN_VALUE
                }
            }
        }

        private fun saturatingMultiply(left: Long, right: Long): Long {
            try {
                return Math.multiplyExact(left, right)
            } catch (ignored: ArithmeticException) {
                if ((left xor right) >= 0L) {
                    return Long.MAX_VALUE
                } else {
                    return Long.MIN_VALUE
                }
            }
        }
    }
}
