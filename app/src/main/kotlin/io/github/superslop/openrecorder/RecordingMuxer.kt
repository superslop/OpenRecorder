// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList

/** Serializes both encoder threads into the same MP4 while capture is active. */
class RecordingMuxer @Throws(IOException::class) constructor(output: FileDescriptor) {
    private val muxer: MediaMuxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val tracks: MutableList<Track> = ArrayList()

    private var started: Boolean = false
    private var released: Boolean = false
    private var failure: IOException? = null

    @Synchronized
    fun createTrack(name: String): Track {
        if (started || released) {
            throw IllegalStateException("Tracks must be declared before the muxer starts")
        }
        val track = Track(this, name)
        tracks.add(track)
        return track
    }

    @Synchronized
    @Throws(IOException::class)
    fun stop() {
        if (released) {
            val currentFailure = failure
            if (currentFailure != null) {
                throw currentFailure
            }
            return
        }

        var stopFailure: IOException? = failure
        try {
            if (started) {
                muxer.stop()
            } else if (stopFailure == null) {
                stopFailure = IOException("No encoded tracks were available to finalize")
            }
        } catch (error: RuntimeException) {
            val currentStopFailure = stopFailure
            if (currentStopFailure == null) {
                stopFailure = IOException("Unable to finalize the recording", error)
            } else {
                currentStopFailure.addSuppressed(error)
            }
        } finally {
            try {
                muxer.release()
            } catch (error: RuntimeException) {
                val currentStopFailure = stopFailure
                if (currentStopFailure != null) {
                    currentStopFailure.addSuppressed(error)
                } else {
                    stopFailure = IOException("Unable to release the recording muxer", error)
                }
            }
            released = true
            (this as Object).notifyAll()
        }
        failure = stopFailure
        val failureToThrow = stopFailure
        if (failureToThrow != null) {
            throw failureToThrow
        }
    }

    @Synchronized
    fun release() {
        if (released) {
            return
        }
        try {
            if (started) {
                muxer.stop()
            }
        } catch (_: RuntimeException) {
            // The pending MediaStore item is deleted after a cancelled recording.
        }
        try {
            muxer.release()
        } catch (_: RuntimeException) {
            // Best effort during cancellation.
        }
        released = true
        (this as Object).notifyAll()
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun setTrackFormat(track: Track, format: MediaFormat) {
        verifyTrack(track)
        if (track.formatAdded) {
            throw IOException("${track.name} output format changed more than once")
        }
        if (track.finished) {
            throw IOException("${track.name} track finished before its format was added")
        }
        throwIfFailed()
        try {
            track.index = muxer.addTrack(format)
            track.formatAdded = true
            startIfReady()
        } catch (error: RuntimeException) {
            throw recordFailure("Unable to add the ${track.name} track", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun writeSampleData(
        track: Track,
        data: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        verifyTrack(track)
        if (!track.formatAdded) {
            throw IOException("${track.name} samples arrived before the output format")
        }

        val deadlineNanos: Long = System.nanoTime() + START_TIMEOUT_MS * 1_000_000L
        while (!started && !released && failure == null) {
            val remainingNanos: Long = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                throw recordFailure(
                    "Timed out waiting for all recording tracks",
                    IOException("Missing encoder output format"),
                )
            }
            try {
                val waitMillis: Long = Math.max(1L, remainingNanos / 1_000_000L)
                (this as Object).wait(waitMillis)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for the recording muxer", error)
            }
        }
        throwIfFailed()
        if (released || track.finished) {
            throw IOException("The ${track.name} track is already closed")
        }

        try {
            muxer.writeSampleData(track.index, data, info)
            track.sampleCount++
            track.bytesWritten += info.size.toLong()
        } catch (error: RuntimeException) {
            throw recordFailure("Unable to write the ${track.name} track", error)
        }
    }

    @Synchronized
    internal fun finishTrack(track: Track) {
        verifyTrack(track)
        if (track.finished) {
            return
        }
        track.finished = true
        try {
            startIfReady()
        } catch (error: IOException) {
            failure = error
        }
        (this as Object).notifyAll()
    }

    @Throws(IOException::class)
    private fun startIfReady() {
        if (started || released || failure != null || tracks.isEmpty()) {
            return
        }
        var hasConfiguredTrack: Boolean = false
        for (track in tracks) {
            hasConfiguredTrack = hasConfiguredTrack or track.formatAdded
            if (!track.formatAdded && !track.finished) {
                return
            }
        }
        if (!hasConfiguredTrack) {
            return
        }
        try {
            muxer.start()
            started = true
            (this as Object).notifyAll()
        } catch (error: RuntimeException) {
            throw recordFailure("Unable to start the recording muxer", error)
        }
    }

    private fun verifyTrack(track: Track?) {
        if (track == null || track.owner !== this || !tracks.contains(track)) {
            throw IllegalArgumentException("Track does not belong to this muxer")
        }
    }

    @Throws(IOException::class)
    private fun throwIfFailed() {
        val currentFailure = failure
        if (currentFailure != null) {
            throw currentFailure
        }
    }

    private fun recordFailure(message: String, cause: Throwable): IOException {
        val existingFailure = failure
        if (existingFailure == null) {
            val newFailure = IOException(message, cause)
            failure = newFailure
            (this as Object).notifyAll()
            return newFailure
        }
        if (existingFailure !== cause) {
            existingFailure.addSuppressed(cause)
        }
        (this as Object).notifyAll()
        return existingFailure
    }

    class Track internal constructor(
        internal val owner: RecordingMuxer,
        internal val name: String,
    ) {
        internal var index: Int = -1
        internal var formatAdded: Boolean = false
        internal var finished: Boolean = false
        internal var sampleCount: Long = 0L
        internal var bytesWritten: Long = 0L

        @Throws(IOException::class)
        fun setFormat(format: MediaFormat) {
            owner.setTrackFormat(this, format)
        }

        @Throws(IOException::class)
        fun writeSampleData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            owner.writeSampleData(this, data, info)
        }

        fun finish() {
            owner.finishTrack(this)
        }

        fun getSampleCount(): Long {
            return synchronized(owner) {
                sampleCount
            }
        }

        fun getBytesWritten(): Long {
            return synchronized(owner) {
                bytesWritten
            }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS: Long = 5_000L
    }
}
