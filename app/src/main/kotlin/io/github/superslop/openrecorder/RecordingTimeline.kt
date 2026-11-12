// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import java.util.ArrayList

/** Maps capture-clock timestamps onto one pause-free recording timeline. */
class RecordingTimeline(val startedAtNanos: Long) {
    private val completedPauses: MutableList<PauseInterval> = ArrayList()

    private var pausedAtNanos: Long = Long.MIN_VALUE
    private var stoppedAtNanos: Long = Long.MAX_VALUE

    @Synchronized
    fun pause(timestampNanos: Long) {
        if (pausedAtNanos != Long.MIN_VALUE || stoppedAtNanos != Long.MAX_VALUE) {
            return
        }
        pausedAtNanos = Math.max(startedAtNanos, timestampNanos)
    }

    @Synchronized
    fun resume(timestampNanos: Long) {
        if (pausedAtNanos == Long.MIN_VALUE || stoppedAtNanos != Long.MAX_VALUE) {
            return
        }
        val resumedAtNanos: Long = Math.max(pausedAtNanos, timestampNanos)
        completedPauses.add(PauseInterval(pausedAtNanos, resumedAtNanos))
        pausedAtNanos = Long.MIN_VALUE
    }

    @Synchronized
    fun stop(timestampNanos: Long) {
        if (stoppedAtNanos != Long.MAX_VALUE) {
            return
        }
        stoppedAtNanos = Math.max(startedAtNanos, timestampNanos)
        if (pausedAtNanos != Long.MIN_VALUE) {
            completedPauses.add(
                PauseInterval(
                    pausedAtNanos,
                    Math.max(pausedAtNanos, stoppedAtNanos),
                ),
            )
            pausedAtNanos = Long.MIN_VALUE
        }
    }

    @Synchronized
    fun shouldInclude(timestampNanos: Long): Boolean {
        if (timestampNanos < startedAtNanos || timestampNanos > stoppedAtNanos) {
            return false
        }
        for (pause in completedPauses) {
            if (pause.contains(timestampNanos)) {
                return false
            }
        }
        return pausedAtNanos == Long.MIN_VALUE || timestampNanos < pausedAtNanos
    }

    @Synchronized
    fun toPresentationTimeUs(timestampNanos: Long): Long {
        val boundedTimestamp: Long = Math.max(
            startedAtNanos,
            Math.min(timestampNanos, stoppedAtNanos),
        )
        var pausedDurationNanos: Long = 0L
        for (pause in completedPauses) {
            pausedDurationNanos += pause.durationBefore(boundedTimestamp)
        }
        if (pausedAtNanos != Long.MIN_VALUE && boundedTimestamp > pausedAtNanos) {
            pausedDurationNanos += boundedTimestamp - pausedAtNanos
        }
        return Math.max(
            0L,
            (boundedTimestamp - startedAtNanos - pausedDurationNanos) / 1_000L,
        )
    }

    /** Returns the next continuous capture range that survives pause removal. */
    @Synchronized
    fun findNextIncludedRange(
        searchStartNanos: Long,
        searchEndNanos: Long,
    ): IncludedRange? {
        if (searchEndNanos <= searchStartNanos) {
            return null
        }

        var cursorNanos: Long = Math.max(searchStartNanos, startedAtNanos)
        var limitNanos: Long = searchEndNanos
        if (stoppedAtNanos != Long.MAX_VALUE) {
            limitNanos = Math.min(limitNanos, saturatingIncrement(stoppedAtNanos))
        }
        if (cursorNanos >= limitNanos) {
            return null
        }

        for (pause in completedPauses) {
            val included: IncludedRange? = rangeBeforePause(
                cursorNanos,
                limitNanos,
                pause.startNanos,
                pause.endNanos,
            )
            if (included != null) {
                return included
            }
            if (cursorNanos < pause.endNanos && pause.startNanos < limitNanos) {
                cursorNanos = Math.max(cursorNanos, pause.endNanos)
            }
            if (cursorNanos >= limitNanos) {
                return null
            }
        }

        if (pausedAtNanos != Long.MIN_VALUE) {
            val included: IncludedRange? = rangeBeforePause(
                cursorNanos,
                limitNanos,
                pausedAtNanos,
                Long.MAX_VALUE,
            )
            if (included != null) {
                return included
            }
            if (cursorNanos >= pausedAtNanos) {
                return null
            }
        }
        return IncludedRange(cursorNanos, limitNanos)
    }

    class IncludedRange(
        @JvmField val startNanos: Long,
        @JvmField val endNanos: Long,
    )

    private class PauseInterval(
        val startNanos: Long,
        val endNanos: Long,
    ) {
        fun contains(timestampNanos: Long): Boolean {
            return timestampNanos >= startNanos && timestampNanos < endNanos
        }

        fun durationBefore(timestampNanos: Long): Long {
            if (timestampNanos <= startNanos) {
                return 0L
            }
            return Math.min(timestampNanos, endNanos) - startNanos
        }
    }

    private companion object {
        @JvmStatic
        private fun rangeBeforePause(
            cursorNanos: Long,
            limitNanos: Long,
            pauseStartNanos: Long,
            pauseEndNanos: Long,
        ): IncludedRange? {
            if (pauseEndNanos <= cursorNanos || pauseStartNanos >= limitNanos) {
                return null
            }
            if (cursorNanos < pauseStartNanos) {
                return IncludedRange(cursorNanos, Math.min(limitNanos, pauseStartNanos))
            }
            return null
        }

        @JvmStatic
        private fun saturatingIncrement(value: Long): Long {
            if (value == Long.MAX_VALUE) {
                return Long.MAX_VALUE
            }
            return value + 1L
        }
    }
}
