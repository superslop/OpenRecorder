// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class InternalAudioRecorder(
    context: Context,
    projection: MediaProjection,
    audioSource: AudioSource,
    sampleRate: Int,
) {
    private val context: Context = context.getApplicationContext()
    private val projection: MediaProjection = projection
    private val capturePlayback: Boolean = audioSource.usesInternalAudio()
    private val includeMicrophone: Boolean = audioSource.usesMicrophone()
    private val sampleRate: Int = RecordingOptions.normalizeSampleRate(sampleRate)
    private val failure: AtomicReference<Exception> = AtomicReference()
    private val codecResourcesFinished: AtomicBoolean = AtomicBoolean()
    private val codecBufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
    private val captureTimestamp: AudioTimestamp = AudioTimestamp()
    private val pauseLock: Any = Any()

    private var playbackRecord: AudioRecord? = null
    private var microphoneRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var outputTrack: RecordingMuxer.Track? = null
    private lateinit var timeline: RecordingTimeline
    private var negotiatedOutputFormat: MediaFormat? = null
    private var outputFormatRegistered: Boolean = false
    private var worker: Thread? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var paused: Boolean = false

    private var prepared: Boolean = false
    private var started: Boolean = false
    private var codecStarted: Boolean = false
    private var endOfStreamQueued: Boolean = false
    private var captureSessionFrames: Long = 0L
    private var lastEstimatedBufferEndNanos: Long = Long.MIN_VALUE
    private var lastQueuedPresentationTimeUs: Long = -1L
    private var endOfStreamPresentationTimeUs: Long = 0L

    init {
        if (!capturePlayback && !includeMicrophone) {
            throw IllegalArgumentException("An audio capture source is required")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun prepare() {
        if (prepared) {
            return
        }
        try {
            setup()
            val encoder: MediaCodec = codec!!
            encoder.start()
            codecStarted = true
            prepared = true
        } catch (error: SecurityException) {
            cleanUpFailedStart()
            throw error
        } catch (error: Exception) {
            cleanUpFailedStart()
            if (error is IOException) {
                throw error
            }
            throw IOException("Unable to prepare internal audio capture", error)
        }
    }

    @Synchronized
    fun setOutputTrack(outputTrack: RecordingMuxer.Track?) {
        if (started || this.outputTrack != null || outputTrack == null) {
            throw IllegalStateException("Audio output track cannot be changed")
        }
        this.outputTrack = outputTrack
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(timeline: RecordingTimeline?) {
        if (started) {
            throw IllegalStateException("Audio recorder is single-use")
        }
        if (outputTrack == null || timeline == null) {
            throw IllegalStateException("Audio output is not configured")
        }
        prepare()
        started = true

        try {
            this.timeline = timeline
            val playback: AudioRecord? = playbackRecord
            if (playback != null) {
                playback.startRecording()
            }
            val microphone: AudioRecord? = microphoneRecord
            if (microphone != null) {
                microphone.startRecording()
            }
            ensureRecordersStarted()

            running = true
            paused = false
            resetCaptureClockSession()
            val captureWorker: Thread = Thread(
                Runnable { captureLoop() },
                "ScreenAudioCapture",
            )
            worker = captureWorker
            captureWorker.start()
        } catch (error: SecurityException) {
            cleanUpFailedStart()
            throw error
        } catch (error: Exception) {
            cleanUpFailedStart()
            if (error is IOException) {
                throw error
            }
            throw IOException("Unable to start internal audio capture", error)
        }
    }

    private fun setup() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission is required")
        }
        val minimumBuffer: Int = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            throw IOException("Unsupported audio capture configuration")
        }
        val bufferSize: Int = Math.max(minimumBuffer * 4, 64 * 1024)

        val format: AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        if (capturePlayback) {
            val captureConfiguration: AudioPlaybackCaptureConfiguration =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            val playback: AudioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build()
            playbackRecord = playback
            ensureInitialized(playback, "Internal audio recorder")
        }

        if (includeMicrophone) {
            val microphone: AudioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()
            microphoneRecord = microphone
            ensureInitialized(microphone, "Microphone recorder")
        }

        val codecFormat: MediaFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            CHANNEL_COUNT,
        )
        codecFormat.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        )
        codecFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        codecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val encoder: MediaCodec = codec!!
        encoder.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun ensureRecordersStarted() {
        val playback: AudioRecord? = playbackRecord
        if (playback != null &&
            playback.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING
        ) {
            throw IOException("Internal audio capture did not start")
        }
        val microphone: AudioRecord? = microphoneRecord
        if (microphone != null &&
            microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING
        ) {
            throw IOException("Microphone capture did not start")
        }
    }

    private fun captureLoop() {
        try {
            val primaryRecord: AudioRecord = if (capturePlayback) {
                playbackRecord!!
            } else {
                microphoneRecord!!
            }
            val primaryName: String = if (capturePlayback) {
                "Internal audio"
            } else {
                "Microphone"
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (capturePlayback && includeMicrophone) {
                captureMixedAudio(primaryRecord, primaryName)
            } else {
                captureDirectAudio(primaryRecord, primaryName)
            }
            finishEncodingStream()
        } catch (error: Exception) {
            if (running) {
                failure.compareAndSet(null, error)
                Log.e(TAG, "Audio capture failed", error)
            } else {
                try {
                    finishEncodingStream()
                } catch (finishError: Exception) {
                    failure.compareAndSet(null, finishError)
                    Log.w(TAG, "Unable to finalize the audio stream", finishError)
                }
            }
        } finally {
            running = false
            finishCodecAndOutput()
        }
    }

    /** Reads a single source straight into the AAC encoder's direct input buffer. */
    private fun captureDirectAudio(record: AudioRecord, sourceName: String) {
        val encoder: MediaCodec = codec!!
        while (running) {
            if (!waitUntilActive()) {
                break
            }
            val inputIndex: Int = dequeueInputBufferWhileCapturing()
            if (inputIndex < 0) {
                break
            }
            val input: ByteBuffer? = encoder.getInputBuffer(inputIndex)
            if (input == null) {
                throw IOException("AAC encoder input buffer is unavailable")
            }
            input.clear()
            val maximumBytes: Int = Math.min(input.remaining(), READ_SAMPLES * 2) and 1.inv()
            if (maximumBytes == 0) {
                throw IOException("AAC encoder input buffer is too small")
            }
            if (!running) {
                queueEmptyInputBuffer(inputIndex)
                break
            }

            val byteCount: Int = record.read(input, maximumBytes, AudioRecord.READ_BLOCKING)
            if (byteCount <= 0) {
                queueEmptyInputBuffer(inputIndex)
                if (running) {
                    if (paused) {
                        continue
                    }
                    throw IOException(sourceName + " read failed: " + byteCount)
                }
                break
            }

            val frameCount: Int = (byteCount and 1.inv()) / 2
            val bufferStartFrame: Long = captureSessionFrames
            captureSessionFrames += frameCount
            val bufferStartNanos: Long = estimateBufferStartNanos(
                record,
                bufferStartFrame,
                frameCount,
            )
            queueDirectPcmBuffer(inputIndex, input, frameCount, bufferStartNanos)
            drainCodec(false)
        }
    }

    private fun captureMixedAudio(primaryRecord: AudioRecord, primaryName: String) {
        val primary: ShortArray = ShortArray(READ_SAMPLES)
        val microphone: ShortArray = ShortArray(READ_SAMPLES)
        while (running) {
            if (!waitUntilActive()) {
                break
            }
            val primaryCount: Int = primaryRecord.read(
                primary,
                0,
                primary.size,
                AudioRecord.READ_BLOCKING,
            )
            if (primaryCount <= 0) {
                if (running) {
                    if (paused) {
                        continue
                    }
                    throw IOException(primaryName + " read failed: " + primaryCount)
                }
                break
            }
            val bufferStartFrame: Long = captureSessionFrames
            captureSessionFrames += primaryCount
            val bufferStartNanos: Long = estimateBufferStartNanos(
                primaryRecord,
                bufferStartFrame,
                primaryCount,
            )

            val activeMicrophoneRecord: AudioRecord = microphoneRecord!!
            val microphoneCount: Int = activeMicrophoneRecord.read(
                microphone,
                0,
                microphone.size,
                AudioRecord.READ_BLOCKING,
            )
            if (microphoneCount <= 0) {
                if (running) {
                    if (paused) {
                        continue
                    }
                    throw IOException("Microphone read failed: " + microphoneCount)
                }
                break
            }
            val sampleCount: Int = Math.min(primaryCount, microphoneCount)
            mix(primary, microphone, sampleCount)
            queueShortPcm(primary, sampleCount, bufferStartNanos)
            drainCodec(false)
        }
    }

    private fun waitUntilActive(): Boolean {
        return synchronized(pauseLock) {
            while (running && paused) {
                pauseLock.wait()
            }
            running
        }
    }

    private fun mix(playback: ShortArray, microphone: ShortArray, count: Int) {
        for (i in 0 until count) {
            val mic: Int = Math.round(microphone[i] * MIC_GAIN)
            val mixed: Int = playback[i] + mic
            playback[i] = Math.max(
                Short.MIN_VALUE.toInt(),
                Math.min(Short.MAX_VALUE.toInt(), mixed),
            ).toShort()
        }
    }

    private fun estimateBufferStartNanos(
        record: AudioRecord,
        bufferStartFrame: Long,
        frameCount: Int,
    ): Long {
        val estimateNanos: Long
        val timestampResult: Int
        try {
            timestampResult = record.getTimestamp(
                captureTimestamp,
                AudioTimestamp.TIMEBASE_MONOTONIC,
            )
        } catch (ignored: RuntimeException) {
            timestampResult = AudioRecord.ERROR_INVALID_OPERATION
        }
        if (timestampResult == AudioRecord.SUCCESS) {
            estimateNanos = AudioFrameClock.frameTimeNanos(
                captureTimestamp.framePosition,
                captureTimestamp.nanoTime,
                bufferStartFrame,
                sampleRate,
            )
        } else if (lastEstimatedBufferEndNanos != Long.MIN_VALUE) {
            estimateNanos = lastEstimatedBufferEndNanos
        } else {
            estimateNanos = System.nanoTime() -
                AudioFrameClock.framesToNanos(frameCount.toLong(), sampleRate)
        }
        lastEstimatedBufferEndNanos = estimateNanos +
            AudioFrameClock.framesToNanos(frameCount.toLong(), sampleRate)
        return estimateNanos
    }

    private fun dequeueInputBufferWhileCapturing(): Int {
        val encoder: MediaCodec = codec!!
        while (running) {
            val inputIndex: Int = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                return inputIndex
            }
            drainCodec(false)
        }
        return -1
    }

    private fun dequeueInputBufferForPcm(): Int {
        val encoder: MediaCodec = codec!!
        for (attempts in 0 until MAX_CODEC_POLLS) {
            val inputIndex: Int = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                return inputIndex
            }
            drainCodec(false)
        }
        throw IOException("Timed out while feeding the AAC encoder")
    }

    private fun queueDirectPcmBuffer(
        inputIndex: Int,
        input: ByteBuffer,
        frameCount: Int,
        bufferStartNanos: Long,
    ) {
        val bufferEndNanos: Long = bufferStartNanos +
            AudioFrameClock.framesToNanos(frameCount.toLong(), sampleRate)
        var cursorNanos: Long = bufferStartNanos
        var writtenFrames: Int = 0
        var firstIncludedFrameNanos: Long = Long.MIN_VALUE

        while (cursorNanos < bufferEndNanos) {
            val range: RecordingTimeline.IncludedRange? = timeline.findNextIncludedRange(
                cursorNanos,
                bufferEndNanos,
            )
            if (range == null) {
                break
            }
            val sourceStartFrame: Int = frameOffsetAtOrAfter(
                range.startNanos,
                bufferStartNanos,
                frameCount,
            )
            val sourceEndFrame: Int = frameOffsetAtOrAfter(
                range.endNanos,
                bufferStartNanos,
                frameCount,
            )
            if (sourceEndFrame > sourceStartFrame) {
                if (firstIncludedFrameNanos == Long.MIN_VALUE) {
                    firstIncludedFrameNanos = bufferStartNanos +
                        AudioFrameClock.framesToNanos(sourceStartFrame.toLong(), sampleRate)
                }
                val includedFrames: Int = sourceEndFrame - sourceStartFrame
                if (sourceStartFrame != writtenFrames) {
                    movePcmFrames(input, sourceStartFrame, writtenFrames, includedFrames)
                }
                writtenFrames += includedFrames
            }
            if (range.endNanos <= cursorNanos) {
                break
            }
            cursorNanos = range.endNanos
        }

        if (writtenFrames == 0) {
            queueEmptyInputBuffer(inputIndex)
            return
        }
        queueAudioInputBuffer(
            inputIndex,
            writtenFrames * 2,
            firstIncludedFrameNanos,
            writtenFrames,
        )
    }

    private fun queueShortPcm(
        samples: ShortArray,
        frameCount: Int,
        bufferStartNanos: Long,
    ) {
        val encoder: MediaCodec = codec!!
        val bufferEndNanos: Long = bufferStartNanos +
            AudioFrameClock.framesToNanos(frameCount.toLong(), sampleRate)
        var cursorNanos: Long = bufferStartNanos
        while (cursorNanos < bufferEndNanos) {
            val range: RecordingTimeline.IncludedRange? = timeline.findNextIncludedRange(
                cursorNanos,
                bufferEndNanos,
            )
            if (range == null) {
                return
            }
            var sourceFrame: Int = frameOffsetAtOrAfter(
                range.startNanos,
                bufferStartNanos,
                frameCount,
            )
            val sourceEndFrame: Int = frameOffsetAtOrAfter(
                range.endNanos,
                bufferStartNanos,
                frameCount,
            )
            while (sourceFrame < sourceEndFrame) {
                val inputIndex: Int = dequeueInputBufferForPcm()
                val input: ByteBuffer? = encoder.getInputBuffer(inputIndex)
                if (input == null) {
                    throw IOException("AAC encoder input buffer is unavailable")
                }
                input.clear()
                val chunkFrames: Int = Math.min(
                    input.remaining() / 2,
                    sourceEndFrame - sourceFrame,
                )
                if (chunkFrames == 0) {
                    throw IOException("AAC encoder input buffer is too small")
                }
                input.order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(samples, sourceFrame, chunkFrames)
                val chunkStartNanos: Long = bufferStartNanos +
                    AudioFrameClock.framesToNanos(sourceFrame.toLong(), sampleRate)
                queueAudioInputBuffer(
                    inputIndex,
                    chunkFrames * 2,
                    chunkStartNanos,
                    chunkFrames,
                )
                sourceFrame += chunkFrames
            }
            if (range.endNanos <= cursorNanos) {
                return
            }
            cursorNanos = range.endNanos
        }
    }

    private fun frameOffsetAtOrAfter(
        timestampNanos: Long,
        bufferStartNanos: Long,
        frameCount: Int,
    ): Int {
        val offsetFrames: Long = AudioFrameClock.nanosToFramesCeil(
            Math.max(0L, timestampNanos - bufferStartNanos),
            sampleRate,
        )
        return Math.min(frameCount.toLong(), offsetFrames).toInt()
    }

    private fun queueAudioInputBuffer(
        inputIndex: Int,
        byteCount: Int,
        firstFrameNanos: Long,
        frameCount: Int,
    ) {
        val encoder: MediaCodec = codec!!
        var presentationTimeUs: Long = timeline.toPresentationTimeUs(firstFrameNanos)
        if (presentationTimeUs <= lastQueuedPresentationTimeUs) {
            presentationTimeUs = lastQueuedPresentationTimeUs + 1L
        }
        encoder.queueInputBuffer(inputIndex, 0, byteCount, presentationTimeUs, 0)
        lastQueuedPresentationTimeUs = presentationTimeUs
        val durationUs: Long = AudioFrameClock.framesToNanos(
            frameCount.toLong(),
            sampleRate,
        ) / 1_000L
        endOfStreamPresentationTimeUs = Math.max(
            endOfStreamPresentationTimeUs,
            presentationTimeUs + durationUs,
        )
    }

    private fun queueEmptyInputBuffer(inputIndex: Int) {
        val encoder: MediaCodec = codec!!
        encoder.queueInputBuffer(
            inputIndex,
            0,
            0,
            Math.max(0L, endOfStreamPresentationTimeUs),
            0,
        )
    }

    private fun finishEncodingStream() {
        queueEndOfStream()
        drainCodec(true)
    }

    private fun queueEndOfStream() {
        if (endOfStreamQueued) {
            return
        }
        val encoder: MediaCodec = codec!!
        for (attempts in 0 until MAX_CODEC_POLLS) {
            val inputIndex: Int = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    endOfStreamPresentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                endOfStreamQueued = true
                return
            }
            drainCodec(false)
        }
        throw IOException("Timed out while signaling the AAC encoder")
    }

    private fun drainCodec(waitForEnd: Boolean) {
        val encoder: MediaCodec = codec!!
        var emptyPolls: Int = 0
        while (true) {
            val timeoutUs: Long = if (waitForEnd) {
                CODEC_TIMEOUT_US
            } else {
                0L
            }
            val outputIndex: Int = encoder.dequeueOutputBuffer(codecBufferInfo, timeoutUs)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEnd) {
                    return
                }
                emptyPolls += 1
                if (emptyPolls >= MAX_CODEC_POLLS) {
                    throw IOException("Timed out while draining the AAC encoder")
                }
                continue
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (negotiatedOutputFormat != null) {
                    throw IOException("AAC output format changed more than once")
                }
                negotiatedOutputFormat = encoder.getOutputFormat()
                emptyPolls = 0
                continue
            }
            if (outputIndex < 0) {
                continue
            }

            var end: Boolean = false
            try {
                val output: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                if (codecBufferInfo.size > 0 && output == null) {
                    throw IOException("AAC encoder output buffer is unavailable")
                }
                if (output != null &&
                    codecBufferInfo.size > 0 &&
                    (codecBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    ensureOutputTrackConfigured()
                    output.position(codecBufferInfo.offset)
                    output.limit(codecBufferInfo.offset + codecBufferInfo.size)
                    outputTrack!!.writeSampleData(output, codecBufferInfo)
                }
                end = (codecBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            } finally {
                encoder.releaseOutputBuffer(outputIndex, false)
            }
            if (end) {
                return
            }
            emptyPolls = 0
        }
    }

    private fun ensureOutputTrackConfigured() {
        if (outputFormatRegistered) {
            return
        }
        val format: MediaFormat? = negotiatedOutputFormat
        if (format == null) {
            throw IOException("AAC samples arrived before the output format")
        }
        outputTrack!!.setFormat(format)
        outputFormatRegistered = true
    }

    @Synchronized
    fun pause() {
        if (!started || !running) {
            throw IllegalStateException("Audio recording is not active")
        }
        synchronized(pauseLock) {
            if (paused) {
                return
            }
            paused = true
        }
        safeStop(playbackRecord)
        safeStop(microphoneRecord)
    }

    @Synchronized
    @Throws(IOException::class)
    fun resume() {
        if (!started || !running) {
            throw IllegalStateException("Audio recording is not active")
        }
        synchronized(pauseLock) {
            if (!paused) {
                return
            }
            try {
                val playback: AudioRecord? = playbackRecord
                if (playback != null) {
                    playback.startRecording()
                }
                val microphone: AudioRecord? = microphoneRecord
                if (microphone != null) {
                    microphone.startRecording()
                }
                ensureRecordersStarted()
                resetCaptureClockSession()
            } catch (error: Exception) {
                throw IOException("Unable to resume internal audio capture", error)
            }
            paused = false
            pauseLock.notifyAll()
        }
    }

    @Synchronized
    fun requestStop() {
        if (!started) {
            return
        }
        running = false
        wakePausedWorker()
        safeStop(playbackRecord)
        safeStop(microphoneRecord)
    }

    @Synchronized
    @Throws(IOException::class)
    fun awaitStopped() {
        if (!started) {
            return
        }
        waitForWorker()

        val audioFailure: Exception? = failure.get()
        if (audioFailure != null) {
            throw IOException("Internal audio capture failed", audioFailure)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun stop() {
        requestStop()
        awaitStopped()
    }

    @Synchronized
    fun release() {
        val activeWorker: Thread? = worker
        val waitForCaptureWorker: Boolean = activeWorker != null && activeWorker.isAlive()
        running = false
        wakePausedWorker()
        safeStop(playbackRecord)
        safeStop(microphoneRecord)
        if (waitForCaptureWorker) {
            try {
                waitForWorker()
            } catch (error: IOException) {
                Log.w(TAG, "Unable to stop the audio worker cleanly", error)
            }
        }
        safeRelease(playbackRecord)
        safeRelease(microphoneRecord)
        playbackRecord = null
        microphoneRecord = null
        val remainingWorker: Thread? = worker
        if (remainingWorker == null || !remainingWorker.isAlive()) {
            finishCodecAndOutput()
        }
    }

    private fun waitForWorker() {
        val activeWorker: Thread? = worker
        if (activeWorker == null) {
            return
        }
        try {
            activeWorker.join(WORKER_JOIN_TIMEOUT_MS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while stopping audio", error)
        }
        if (activeWorker.isAlive()) {
            activeWorker.interrupt()
            throw IOException("Timed out while stopping audio capture")
        }
        worker = null
    }

    private fun cleanUpFailedStart() {
        running = false
        wakePausedWorker()
        safeStop(playbackRecord)
        safeStop(microphoneRecord)
        safeRelease(playbackRecord)
        safeRelease(microphoneRecord)
        playbackRecord = null
        microphoneRecord = null
        finishCodecAndOutput()
    }

    private fun wakePausedWorker() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    private fun resetCaptureClockSession() {
        captureSessionFrames = 0L
        lastEstimatedBufferEndNanos = Long.MIN_VALUE
    }

    private fun finishCodecAndOutput() {
        if (!codecResourcesFinished.compareAndSet(false, true)) {
            return
        }
        val encoder: MediaCodec? = codec
        if (encoder != null) {
            if (codecStarted) {
                try {
                    encoder.stop()
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to stop AAC encoder", error)
                }
            }
            try {
                encoder.release()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to release AAC encoder", error)
            }
            codec = null
        }
        val track: RecordingMuxer.Track? = outputTrack
        if (track != null) {
            track.finish()
        }
    }

    companion object {
        private const val TAG = "InternalAudioRecorder"
        private const val BIT_RATE = 196_000
        private const val CHANNEL_COUNT = 1
        private const val READ_SAMPLES = 4096
        private const val MIC_GAIN = 1.4f
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_CODEC_POLLS = 200
        private const val WORKER_JOIN_TIMEOUT_MS = 5_000L

        @JvmStatic
        private fun ensureInitialized(record: AudioRecord, name: String) {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw IOException(name + " is unavailable")
            }
        }

        @JvmStatic
        private fun movePcmFrames(
            input: ByteBuffer,
            sourceFrame: Int,
            destinationFrame: Int,
            frameCount: Int,
        ) {
            val sourceByte: Int = sourceFrame * 2
            val destinationByte: Int = destinationFrame * 2
            val byteCount: Int = frameCount * 2
            for (index in 0 until byteCount) {
                input.put(destinationByte + index, input.get(sourceByte + index))
            }
        }

        @JvmStatic
        private fun safeStop(record: AudioRecord?) {
            if (record == null) {
                return
            }
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (ignored: IllegalStateException) {
                // The audio server may already have stopped this recorder.
            }
        }

        @JvmStatic
        private fun safeRelease(record: AudioRecord?) {
            if (record == null) {
                return
            }
            try {
                record.release()
            } catch (ignored: Exception) {
                // Best effort during cleanup.
            }
        }
    }
}
