// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Encodes the screen through an asynchronous, hardware-first surface pipeline. */
class VideoTrackRecorder(
    width: Int,
    height: Int,
    bitrate: Int,
    videoCodec: Int,
    requestedFrameRate: Int,
    sourceRefreshRate: Float,
    listener: Listener,
) {
    fun interface Listener {
        fun onFailure(error: Exception)
    }

    private val width: Int = width
    private val height: Int = height
    private val bitrate: Int = bitrate
    private val videoCodec: Int = RecordingOptions.normalizeVideoCodec(videoCodec)
    private val requestedFrameRate: Int =
        RecordingOptions.normalizeVideoFrameRate(requestedFrameRate)
    private val sourceRefreshRate: Float = sourceRefreshRate
    private val listener: Listener = listener
    private val failure: AtomicReference<Exception> = AtomicReference()
    private val resourcesFinished: AtomicBoolean = AtomicBoolean()
    private val encodingFinished: AtomicBoolean = AtomicBoolean()
    private val encodingFinishedLatch: CountDownLatch = CountDownLatch(1)

    @Volatile
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var outputTrack: RecordingMuxer.Track? = null
    private lateinit var timeline: RecordingTimeline
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    private var negotiatedOutputFormat: MediaFormat? = null
    private var outputFormatRegistered: Boolean = false
    private var prepared: Boolean = false
    private var started: Boolean = false
    private var codecStarted: Boolean = false

    @Volatile
    private var endOfStreamSignaled: Boolean = false

    @Volatile
    private var paused: Boolean = false

    private var sourceClockResolved: Boolean = false
    private var sourceToMonotonicOffsetNanos: Long = 0L
    private var lastWrittenPresentationTimeUs: Long = -1L
    private var configuredFrameRate: Int = 0

    private val codecCallback: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(callbackCodec: MediaCodec, index: Int) {
            // Surface-input encoders never consume ByteBuffer input.
        }

        override fun onOutputBufferAvailable(
            callbackCodec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            handleOutputBuffer(callbackCodec, index, info)
        }

        override fun onError(callbackCodec: MediaCodec, error: MediaCodec.CodecException) {
            if (callbackCodec === codec) {
                completeEncoding(error)
            }
        }

        override fun onOutputFormatChanged(callbackCodec: MediaCodec, format: MediaFormat) {
            if (callbackCodec !== codec || encodingFinished.get()) {
                return
            }
            try {
                if (negotiatedOutputFormat != null) {
                    throw IOException("Video output format changed more than once")
                }
                negotiatedOutputFormat = format
                logNegotiatedBitrate(format)
            } catch (error: Exception) {
                completeEncoding(error)
            }
        }
    }

    init {
        if (width <= 0 || height <= 0 || bitrate <= 0) {
            throw IllegalArgumentException("Invalid video encoder configuration")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun prepare() {
        if (prepared) {
            return
        }
        if (started) {
            throw IllegalStateException("Video recorder is single-use")
        }

        try {
            startCallbackThread()
            configureBestEncoder(mimeTypeForCodec(videoCodec))
            prepared = true
        } catch (error: Exception) {
            finishResources()
            if (error is IOException) {
                throw error
            }
            throw IOException("Unable to prepare the video encoder", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun getInputSurface(): Surface {
        prepare()
        return inputSurface!!
    }

    @Synchronized
    fun getConfiguredFrameRate(): Int {
        if (!prepared || configuredFrameRate <= 0) {
            throw IllegalStateException("Video encoder is not prepared")
        }
        return configuredFrameRate
    }

    @Synchronized
    fun setOutputTrack(outputTrack: RecordingMuxer.Track?) {
        if (started || this.outputTrack != null || outputTrack == null) {
            throw IllegalStateException("Video output track cannot be changed")
        }
        this.outputTrack = outputTrack
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(timeline: RecordingTimeline?) {
        if (started) {
            throw IllegalStateException("Video recorder is single-use")
        }
        if (outputTrack == null || timeline == null) {
            throw IllegalStateException("Video output is not configured")
        }
        prepare()
        try {
            this.timeline = timeline
            started = true
            codec!!.start()
            codecStarted = true
        } catch (error: Exception) {
            completeEncoding(error)
            finishResources()
            throw IOException("Unable to start the video encoder", error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun pause() {
        if (!started || endOfStreamSignaled || encodingFinished.get()) {
            throw IllegalStateException("Video recording is not active")
        }
        if (paused) {
            return
        }
        paused = true
        setSuspended(true)
    }

    @Synchronized
    @Throws(IOException::class)
    fun resume() {
        if (!started || endOfStreamSignaled || encodingFinished.get()) {
            throw IllegalStateException("Video recording is not active")
        }
        if (!paused) {
            return
        }
        setSuspended(false)
        paused = false
    }

    @Synchronized
    fun requestStop() {
        if (!started || endOfStreamSignaled || encodingFinished.get()) {
            return
        }
        if (paused) {
            setSuspended(false)
            paused = false
        }
        endOfStreamSignaled = true
        try {
            codec!!.signalEndOfInputStream()
        } catch (error: RuntimeException) {
            completeEncoding(error)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun awaitStopped() {
        if (!started) {
            return
        }
        val completed: Boolean
        try {
            completed = encodingFinishedLatch.await(
                ENCODER_STOP_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while stopping video encoding", error)
        }
        if (!completed) {
            val timeout: IOException = IOException("Timed out while draining the video encoder")
            failure.compareAndSet(null, timeout)
        }
        finishResources()

        val encoderFailure: Exception? = failure.get()
        if (encoderFailure != null) {
            throw IOException("Video encoding failed", encoderFailure)
        }
        if (!completed) {
            throw IOException("Timed out while draining the video encoder")
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
        if (started && !resourcesFinished.get()) {
            requestStop()
            try {
                awaitStopped()
            } catch (error: IOException) {
                Log.w(TAG, "Unable to stop the video encoder cleanly", error)
            }
        }
        finishResources()
    }

    private fun startCallbackThread() {
        val thread: HandlerThread = HandlerThread(
            "ScreenVideoEncoderCallback",
            Process.THREAD_PRIORITY_DISPLAY,
        )
        callbackThread = thread
        thread.start()
        callbackHandler = Handler(thread.getLooper())
    }

    private fun configureBestEncoder(mimeType: String) {
        val candidates: List<MediaCodecInfo> = encoderCandidates(mimeType)
        if (candidates.isEmpty()) {
            throw IOException("No compatible surface video encoder was found")
        }

        val frameRates: IntArray = VideoFrameRatePolicy.fallbackCandidates(
            requestedFrameRate,
            sourceRefreshRate,
        )
        var lastFailure: Exception? = null
        for (targetHardwareRank in 0..2) {
            for (frameRate in frameRates) {
                for (candidate in candidates) {
                    if (hardwareRank(candidate) != targetHardwareRank) {
                        continue
                    }
                    try {
                        configureEncoder(candidate, mimeType, frameRate)
                        logConfiguredEncoder(candidate, frameRate)
                        return
                    } catch (error: Exception) {
                        lastFailure = error
                        Log.w(
                            TAG,
                            "Encoder " + candidate.getName() +
                                " rejected " + width + "x" + height +
                                " at " + frameRate + " fps",
                            error,
                        )
                    }
                }
            }
        }

        throw IOException(
            "No encoder could be configured for the requested frame-rate fallbacks",
            lastFailure,
        )
    }

    private fun configureEncoder(
        codecInfo: MediaCodecInfo,
        mimeType: String,
        frameRate: Int,
    ) {
        var candidateCodec: MediaCodec? = null
        var candidateSurface: Surface? = null
        try {
            val frameIntervalUs: Long = VideoTimestampNormalizer.frameIntervalUs(frameRate)
            val format: MediaFormat = MediaFormat.createVideoFormat(mimeType, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, frameIntervalUs)
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, frameRate.toFloat())

            val createdCodec: MediaCodec = MediaCodec.createByCodecName(codecInfo.getName())
            candidateCodec = createdCodec
            createdCodec.setCallback(codecCallback, callbackHandler)
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val createdSurface: Surface = createdCodec.createInputSurface()
            candidateSurface = createdSurface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    createdSurface.setFrameRate(
                        frameRate.toFloat(),
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    )
                } catch (error: RuntimeException) {
                    Log.w(
                        TAG,
                        "Unable to apply the frame-rate hint to the input surface",
                        error,
                    )
                }
            }

            codec = candidateCodec
            inputSurface = candidateSurface
            configuredFrameRate = frameRate
        } catch (error: Exception) {
            val surfaceToRelease: Surface? = candidateSurface
            if (surfaceToRelease != null) {
                try {
                    surfaceToRelease.release()
                } catch (ignored: RuntimeException) {
                }
            }
            val codecToRelease: MediaCodec? = candidateCodec
            if (codecToRelease != null) {
                try {
                    codecToRelease.release()
                } catch (ignored: RuntimeException) {
                }
            }
            if (error is IOException) {
                throw error
            }
            throw IOException("Encoder configuration failed", error)
        }
    }

    private fun logConfiguredEncoder(codecInfo: MediaCodecInfo, frameRate: Int) {
        val hardwareSuffix: String = if (codecInfo.isHardwareAccelerated()) {
            ", hardware"
        } else {
            ""
        }
        Log.i(
            TAG,
            "Encoder " + codecInfo.getName() +
                " configured at " + width + "x" + height +
                ", target " + bitrate + " bps, " + frameRate + " fps" +
                hardwareSuffix,
        )
        val preferredFrameRate: Int = VideoFrameRatePolicy.resolvePreferredFrameRate(
            requestedFrameRate,
            sourceRefreshRate,
        )
        if (frameRate != preferredFrameRate) {
            Log.w(
                TAG,
                "Requested " + preferredFrameRate + " fps, using configured fallback " +
                    frameRate + " fps",
            )
        }
    }

    private fun handleOutputBuffer(
        callbackCodec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
    ) {
        if (callbackCodec !== codec) {
            return
        }

        var callbackFailure: Exception? = null
        val endOfStream: Boolean = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        try {
            val output: ByteBuffer? = callbackCodec.getOutputBuffer(outputIndex)
            if (info.size > 0 && output == null) {
                throw IOException("Video encoder output buffer is unavailable")
            }
            if (output != null &&
                info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                !encodingFinished.get()
            ) {
                val adjustedPresentationTimeUs: Long = adjustPresentationTime(
                    info.presentationTimeUs,
                )
                if (adjustedPresentationTimeUs >= 0L) {
                    ensureOutputTrackConfigured()
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    info.presentationTimeUs = adjustedPresentationTimeUs
                    outputTrack!!.writeSampleData(output, info)
                }
            }
        } catch (error: Exception) {
            callbackFailure = error
        } finally {
            try {
                callbackCodec.releaseOutputBuffer(outputIndex, false)
            } catch (releaseError: RuntimeException) {
                val existingFailure: Exception? = callbackFailure
                if (existingFailure == null) {
                    callbackFailure = releaseError
                } else {
                    existingFailure.addSuppressed(releaseError)
                }
            }
        }

        val failureToReport: Exception? = callbackFailure
        if (failureToReport != null) {
            completeEncoding(failureToReport)
        } else if (endOfStream) {
            completeEncoding(null)
        }
    }

    private fun ensureOutputTrackConfigured() {
        if (outputFormatRegistered) {
            return
        }
        val format: MediaFormat? = negotiatedOutputFormat
        if (format == null) {
            throw IOException("Video samples arrived before the output format")
        }
        outputTrack!!.setFormat(format)
        outputFormatRegistered = true
    }

    private fun adjustPresentationTime(sourcePresentationTimeUs: Long): Long {
        val sourceNanos: Long = sourcePresentationTimeUs * 1_000L
        if (!sourceClockResolved) {
            val startedAtNanos: Long = timeline.startedAtNanos
            val clockDifferenceNanos: Long = sourceNanos - startedAtNanos
            if (clockDifferenceNanos > SOURCE_CLOCK_TOLERANCE_NANOS ||
                clockDifferenceNanos < -SOURCE_CLOCK_TOLERANCE_NANOS
            ) {
                sourceToMonotonicOffsetNanos = startedAtNanos - sourceNanos
                Log.w(
                    TAG,
                    "Video timestamps use a non-monotonic origin; applying a clock offset",
                )
            }
            sourceClockResolved = true
        }
        val monotonicTimestampNanos: Long = sourceNanos + sourceToMonotonicOffsetNanos
        if (!timeline.shouldInclude(monotonicTimestampNanos)) {
            return -1L
        }
        var adjusted: Long = timeline.toPresentationTimeUs(monotonicTimestampNanos)
        adjusted = VideoTimestampNormalizer.ensureFrameSpacing(
            adjusted,
            lastWrittenPresentationTimeUs,
            configuredFrameRate,
        )
        lastWrittenPresentationTimeUs = adjusted
        return adjusted
    }

    private fun logNegotiatedBitrate(outputFormat: MediaFormat) {
        if (!outputFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
            Log.i(
                TAG,
                "Encoder accepted target bitrate without exposing a fixed negotiated value",
            )
            return
        }
        try {
            val negotiatedBitrate: Int = outputFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            Log.i(
                TAG,
                "Encoder bitrate target=" + bitrate +
                    " bps, negotiated=" + negotiatedBitrate +
                    " bps; vendor rate control remains active",
            )
        } catch (error: ClassCastException) {
            Log.w(TAG, "Encoder returned a non-integer bitrate value", error)
        }
    }

    private fun setSuspended(suspended: Boolean) {
        val activeCodec: MediaCodec? = codec
        if (activeCodec == null) {
            return
        }
        try {
            val parameters: Bundle = Bundle()
            parameters.putInt(
                MediaCodec.PARAMETER_KEY_SUSPEND,
                if (suspended) {
                    1
                } else {
                    0
                },
            )
            activeCodec.setParameters(parameters)
        } catch (error: RuntimeException) {
            val message: String = if (suspended) {
                "Encoder suspend is unavailable; dropping paused frames by timestamp"
            } else {
                "Encoder resume parameter failed; continuing with pause-free timestamps"
            }
            Log.w(TAG, message, error)
        }
    }

    private fun completeEncoding(error: Exception?) {
        if (!encodingFinished.compareAndSet(false, true)) {
            return
        }
        if (error != null) {
            failure.compareAndSet(null, error)
            Log.e(TAG, "Video encoder failed", error)
        }
        encodingFinishedLatch.countDown()
        if (error != null) {
            try {
                listener.onFailure(error)
            } catch (listenerError: RuntimeException) {
                Log.w(TAG, "Video failure listener failed", listenerError)
            }
        }
    }

    private fun finishResources() {
        if (!resourcesFinished.compareAndSet(false, true)) {
            return
        }

        val activeCodec: MediaCodec? = codec
        codec = null
        if (activeCodec != null) {
            if (codecStarted) {
                try {
                    activeCodec.stop()
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to stop video codec", error)
                }
            }
            try {
                activeCodec.release()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to release video codec", error)
            }
        }
        val surface: Surface? = inputSurface
        if (surface != null) {
            try {
                surface.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to release video input surface", error)
            }
            inputSurface = null
        }
        val track: RecordingMuxer.Track? = outputTrack
        if (track != null) {
            track.finish()
        }

        val activeCallbackThread: HandlerThread? = callbackThread
        callbackThread = null
        callbackHandler = null
        if (activeCallbackThread != null) {
            activeCallbackThread.quit()
            if (Thread.currentThread() !== activeCallbackThread) {
                try {
                    activeCallbackThread.join(CALLBACK_THREAD_JOIN_TIMEOUT_MS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Interrupted while stopping video callback thread", error)
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoTrackRecorder"
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val ENCODER_STOP_TIMEOUT_MS = 8_000L
        private const val CALLBACK_THREAD_JOIN_TIMEOUT_MS = 1_000L
        private const val SOURCE_CLOCK_TOLERANCE_NANOS = 30_000_000_000L

        @JvmStatic
        fun isCodecSupported(requestedCodec: Int): Boolean {
            val mimeType: String = mimeTypeForCodec(requestedCodec)
            try {
                return encoderCandidates(mimeType).isNotEmpty()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to query video encoder support", error)
                return false
            }
        }

        @JvmStatic
        private fun encoderCandidates(mimeType: String): List<MediaCodecInfo> {
            val candidates: ArrayList<MediaCodecInfo> = ArrayList()
            val codecInfos: Array<MediaCodecInfo> = MediaCodecList(
                MediaCodecList.REGULAR_CODECS,
            ).getCodecInfos()
            for (codecInfo in codecInfos) {
                if (codecInfo.isEncoder() && supportsSurfaceInput(codecInfo, mimeType)) {
                    candidates.add(codecInfo)
                }
            }
            candidates.sortWith(
                compareBy<MediaCodecInfo>(
                    { codecInfo -> hardwareRank(codecInfo) },
                    { codecInfo -> codecInfo.getName() },
                ),
            )
            return candidates
        }

        @JvmStatic
        private fun hardwareRank(codecInfo: MediaCodecInfo): Int {
            if (codecInfo.isHardwareAccelerated()) {
                return 0
            }
            if (codecInfo.isSoftwareOnly()) {
                return 2
            }
            return 1
        }

        @JvmStatic
        private fun supportsSurfaceInput(codecInfo: MediaCodecInfo, mimeType: String): Boolean {
            try {
                var supportsMimeType: Boolean = false
                for (supportedType in codecInfo.getSupportedTypes()) {
                    if (mimeType.equalsIgnoreCase(supportedType)) {
                        supportsMimeType = true
                        break
                    }
                }
                if (!supportsMimeType) {
                    return false
                }
                for (colorFormat in codecInfo.getCapabilitiesForType(mimeType).colorFormats) {
                    if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        return true
                    }
                }
            } catch (ignored: RuntimeException) {
                // A codec that cannot report its capabilities cannot be used.
            }
            return false
        }

        @JvmStatic
        private fun mimeTypeForCodec(requestedCodec: Int): String {
            return if (RecordingOptions.normalizeVideoCodec(requestedCodec) ==
                RecordingOptions.VIDEO_CODEC_H265
            ) {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                MediaFormat.MIMETYPE_VIDEO_AVC
            }
        }
    }
}
