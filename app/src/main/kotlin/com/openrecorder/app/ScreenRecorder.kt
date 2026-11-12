// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ScreenRecorder(
    context: Context,
    private val resultCode: Int,
    private val resultData: Intent,
    private val audioSource: AudioSource,
    audioSampleRate: Int,
    videoResolution: Int,
    videoFrameRate: Int,
    private val force16By9Letterboxing: Boolean,
    videoBitrate: Int,
    videoCodec: Int,
    namingPattern: String,
    recordingOrientation: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onProjectionStopped()
        fun onRecorderError(error: Exception)
        fun onAudioCaptureFailed()
    }

    private val context: Context
    private val audioSampleRate: Int
    private val videoResolution: Int
    private val videoFrameRate: Int
    private val videoBitrate: Int
    private val videoCodec: Int
    private val namingPattern: String
    private val recordingOrientation: Int

    init {
        this.context = context.applicationContext
        this.audioSampleRate = RecordingOptions.normalizeSampleRate(audioSampleRate)
        this.videoResolution = RecordingOptions.normalizeVideoResolution(videoResolution)
        this.videoFrameRate = RecordingOptions.normalizeVideoFrameRate(videoFrameRate)
        this.videoBitrate = RecordingOptions.normalizeVideoBitrate(videoBitrate)
        this.videoCodec = RecordingOptions.normalizeVideoCodec(videoCodec)
        this.namingPattern = RecordingOptions.normalizeNamingPattern(namingPattern)
        this.recordingOrientation = RecordingOptions.normalizeOrientation(recordingOrientation)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionCallback: MediaProjection.Callback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                listener.onProjectionStopped()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    capturedContentSize.set(packSize(width, height))
                    capturedSizeReady.countDown()
                }
            }
        }
    private val capturedSizeReady = CountDownLatch(1)
    private val capturedContentSize = AtomicLong()

    private var projection: MediaProjection? = null
    private var videoRecorder: VideoTrackRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var audioRecorder: InternalAudioRecorder? = null
    private var microphoneRecorder: InternalAudioRecorder? = null
    private var recordingMuxer: RecordingMuxer? = null
    private var videoOutputTrack: RecordingMuxer.Track? = null
    private var timeline: RecordingTimeline? = null
    private var outputUri: Uri? = null
    private lateinit var preparedCaptureSize: CaptureSize
    private var prepared: Boolean = false
    private var started: Boolean = false
    private var paused: Boolean = false
    private var stopped: Boolean = false
    private var audioFailed: Boolean = false
    private var outputFinalized: Boolean = false
    private var outputPublished: Boolean = false

    @Synchronized
    @Throws(IOException::class)
    fun prepare() {
        if (prepared) {
            return
        }
        if (started || stopped) {
            throw IllegalStateException("ScreenRecorder is single-use")
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData)
        val mediaProjection = projection
        if (mediaProjection == null) {
            throw IOException("MediaProjection permission was not granted")
        }
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        val initialCaptureSize = getCaptureSize()
        preparedCaptureSize = initialCaptureSize
        prepareVideoRecorder(initialCaptureSize)

        val createdDisplay = mediaProjection.createVirtualDisplay(
            "Open Recorder",
            initialCaptureSize.width,
            initialCaptureSize.height,
            initialCaptureSize.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            mainHandler,
        )
        virtualDisplay = createdDisplay
        if (createdDisplay == null) {
            throw IOException("Unable to create a virtual display")
        }

        reconfigureCaptureSize(awaitAccurateCaptureSize(initialCaptureSize))

        if (audioSource != AudioSource.NONE) {
            try {
                val createdAudioRecorder = InternalAudioRecorder(
                    context,
                    mediaProjection,
                    if (audioSource.usesInternalAudio()) {
                        AudioSource.INTERNAL
                    } else {
                        AudioSource.MICROPHONE
                    },
                    audioSampleRate,
                )
                audioRecorder = createdAudioRecorder
                createdAudioRecorder.prepare()
                if (audioSource == AudioSource.INTERNAL_AND_MICROPHONE) {
                    // A second encoder produces an independent AAC track in the same MP4.
                    val createdMicrophoneRecorder = InternalAudioRecorder(
                        context,
                        mediaProjection,
                        AudioSource.MICROPHONE,
                        audioSampleRate,
                    )
                    microphoneRecorder = createdMicrophoneRecorder
                    createdMicrophoneRecorder.prepare()
                }
            } catch (error: Exception) {
                disableAudio(error)
            }
        }
        prepared = true
    }

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (started || stopped) {
            throw IllegalStateException("ScreenRecorder is single-use")
        }
        prepare()
        reconfigureCaptureSize(latestAccurateCaptureSize(preparedCaptureSize))
        prepareOutput()
        val recordingTimeline = RecordingTimeline(System.nanoTime())
        timeline = recordingTimeline
        started = true

        videoRecorder!!.start(recordingTimeline)
        try {
            virtualDisplay!!.setSurface(surface)
        } catch (error: RuntimeException) {
            throw IOException("Unable to connect the capture surface", error)
        }
        val activeAudioRecorder = audioRecorder
        if (activeAudioRecorder != null) {
            try {
                activeAudioRecorder.start(recordingTimeline)
                val activeMicrophoneRecorder = microphoneRecorder
                if (activeMicrophoneRecorder != null) {
                    activeMicrophoneRecorder.start(recordingTimeline)
                }
            } catch (error: Exception) {
                disableAudio(error)
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun pause() {
        if (!started || stopped) {
            throw IllegalStateException("Recording is not active")
        }
        if (paused) {
            return
        }

        timeline!!.pause(System.nanoTime())
        videoRecorder!!.pause()
        paused = true

        val activeAudioRecorder = audioRecorder
        if (activeAudioRecorder != null) {
            try {
                activeAudioRecorder.pause()
                val activeMicrophoneRecorder = microphoneRecorder
                if (activeMicrophoneRecorder != null) {
                    activeMicrophoneRecorder.pause()
                }
            } catch (error: Exception) {
                disableAudio(error)
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun resume() {
        if (!started || stopped) {
            throw IllegalStateException("Recording is not active")
        }
        if (!paused) {
            return
        }

        timeline!!.resume(System.nanoTime())
        videoRecorder!!.resume()
        paused = false

        val activeAudioRecorder = audioRecorder
        if (activeAudioRecorder != null) {
            try {
                activeAudioRecorder.resume()
                val activeMicrophoneRecorder = microphoneRecorder
                if (activeMicrophoneRecorder != null) {
                    activeMicrophoneRecorder.resume()
                }
            } catch (error: Exception) {
                disableAudio(error)
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun stop() {
        if (stopped) {
            return
        }
        stopped = true
        paused = false
        val activeTimeline = timeline
        if (activeTimeline != null) {
            activeTimeline.stop(System.nanoTime())
        }
        detachCaptureSurface()

        val activeVideoRecorder = videoRecorder
        if (activeVideoRecorder != null) {
            activeVideoRecorder.requestStop()
        }
        val activeAudioRecorder = audioRecorder
        if (activeAudioRecorder != null) {
            activeAudioRecorder.requestStop()
        }
        val activeMicrophoneRecorder = microphoneRecorder
        if (activeMicrophoneRecorder != null) {
            activeMicrophoneRecorder.requestStop()
        }

        if (activeAudioRecorder != null) {
            try {
                activeAudioRecorder.awaitStopped()
                val currentMicrophoneRecorder = microphoneRecorder
                if (currentMicrophoneRecorder != null) {
                    currentMicrophoneRecorder.awaitStopped()
                }
            } catch (error: IOException) {
                disableAudio(error)
            }
        }

        var failure: IOException? = null
        if (activeVideoRecorder != null) {
            try {
                activeVideoRecorder.awaitStopped()
            } catch (error: IOException) {
                failure = error
            }
        }

        val activeMuxer = recordingMuxer
        if (failure == null && activeMuxer != null) {
            try {
                activeMuxer.stop()
                outputFinalized = true
            } catch (error: IOException) {
                failure = error
            }
        }
        releaseCaptureResources()
        if (failure != null) {
            throw failure
        }
    }

    @Throws(IOException::class)
    fun save(): Uri {
        val savedOutputUri = outputUri
        val savedVideoTrack = videoOutputTrack
        if (!outputFinalized
            || savedOutputUri == null
            || savedVideoTrack == null
            || savedVideoTrack.getSampleCount() == 0L
        ) {
            throw IOException("No recorded video was produced")
        }
        val resolver = context.contentResolver
        val ready = ContentValues()
        ready.put(MediaStore.Video.Media.IS_PENDING, 0)
        if (resolver.update(savedOutputUri, ready, null, null) <= 0) {
            throw IOException("MediaStore did not publish the output item")
        }
        outputPublished = true
        return savedOutputUri
    }

    @Synchronized
    fun release() {
        stopped = true
        paused = false
        val stoppingMicrophoneRecorder = microphoneRecorder
        if (stoppingMicrophoneRecorder != null) {
            stoppingMicrophoneRecorder.requestStop()
        }
        val releasingAudioRecorder = audioRecorder
        if (releasingAudioRecorder != null) {
            releasingAudioRecorder.release()
            audioRecorder = null
        }
        val releasingMicrophoneRecorder = microphoneRecorder
        if (releasingMicrophoneRecorder != null) {
            releasingMicrophoneRecorder.release()
            microphoneRecorder = null
        }
        releaseCaptureResources()
        releaseOutput()
    }

    private fun releaseCaptureResources() {
        val display = virtualDisplay
        if (display != null) {
            try {
                display.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to release virtual display", error)
            }
            virtualDisplay = null
        }
        releaseRecorderResources()
        val mediaProjection = projection
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to unregister MediaProjection callback", error)
            }
            try {
                mediaProjection.stop()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to stop MediaProjection", error)
            }
            projection = null
        }
    }

    @Throws(IOException::class)
    private fun prepareOutput() {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val fileName = SimpleDateFormat(namingPattern, Locale.US).format(Date(now)) + ".mp4"
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Open Recorder")
        values.put(MediaStore.Video.Media.DATE_TAKEN, now)
        values.put(MediaStore.Video.Media.IS_PENDING, 1)

        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val pendingUri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore did not create an output item")

        var pendingMuxer: RecordingMuxer? = null
        var pendingVideoTrack: RecordingMuxer.Track? = null
        try {
            resolver.openFileDescriptor(pendingUri, "rw").use { descriptor ->
                if (descriptor == null) {
                    throw IOException("MediaStore output file is unavailable")
                }
                val muxer = RecordingMuxer(descriptor.fileDescriptor)
                pendingMuxer = muxer
                val videoTrack = muxer.createTrack("video")
                pendingVideoTrack = videoTrack
                videoRecorder!!.setOutputTrack(videoTrack)
                val activeAudioRecorder = audioRecorder
                if (activeAudioRecorder != null) {
                    activeAudioRecorder.setOutputTrack(
                        muxer.createTrack(
                            if (audioSource.usesInternalAudio()) {
                                "internal audio"
                            } else {
                                "microphone"
                            },
                        ),
                    )
                }
                val activeMicrophoneRecorder = microphoneRecorder
                if (activeMicrophoneRecorder != null) {
                    activeMicrophoneRecorder.setOutputTrack(
                        muxer.createTrack("microphone"),
                    )
                }
            }
        } catch (error: Exception) {
            val failedMuxer = pendingMuxer
            if (failedMuxer != null) {
                failedMuxer.release()
            }
            try {
                resolver.delete(pendingUri, null, null)
            } catch (deleteError: RuntimeException) {
                error.addSuppressed(deleteError)
            }
            if (error is IOException) {
                throw error
            }
            throw IOException("Unable to prepare the recording output", error)
        }
        outputUri = pendingUri
        recordingMuxer = pendingMuxer
        videoOutputTrack = pendingVideoTrack
    }

    private fun detachCaptureSurface() {
        val display = virtualDisplay ?: return
        try {
            display.setSurface(null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to detach the capture surface", error)
        }
    }

    private fun releaseOutput() {
        val muxer = recordingMuxer
        if (muxer != null) {
            muxer.release()
            recordingMuxer = null
        }
        val uri = outputUri
        if (!outputPublished && uri != null) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to remove an incomplete MediaStore item", error)
            }
        }
        outputUri = null
    }

    @Suppress("DEPRECATION")
    private fun getCaptureSize(): CaptureSize {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds: Rect
        val density: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds = windowManager.maximumWindowMetrics.bounds
            density = context.resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            bounds = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            density = metrics.densityDpi
        }

        return normalizeCaptureSize(bounds.width(), bounds.height(), density)
    }

    private fun normalizeCaptureSize(
        requestedWidth: Int,
        requestedHeight: Int,
        density: Int,
    ): CaptureSize {
        val sourceRefreshRate = getSourceRefreshRate()
        val targetFrameRate = VideoFrameRatePolicy.resolvePreferredFrameRate(
            videoFrameRate,
            sourceRefreshRate,
        )
        val layout = VideoEncodingProfile.resolve(
            requestedWidth,
            requestedHeight,
            recordingOrientation,
            videoResolution,
            force16By9Letterboxing,
            videoBitrate,
            targetFrameRate,
        )
        return CaptureSize(
            layout.outputWidth,
            layout.outputHeight,
            layout.contentWidth,
            layout.contentHeight,
            layout.videoBitrate,
            density,
            sourceRefreshRate,
            targetFrameRate,
        )
    }

    @Throws(IOException::class)
    private fun prepareVideoRecorder(size: CaptureSize) {
        ensureEnoughStorageToStart()
        val recorder = VideoTrackRecorder(
            size.width,
            size.height,
            size.videoBitrate,
            videoCodec,
            videoFrameRate,
            size.sourceRefreshRate,
            object : VideoTrackRecorder.Listener {
                override fun onFailure(error: Exception) {
                    listener.onRecorderError(error)
                }
            },
        )
        videoRecorder = recorder
        surface = recorder.getInputSurface()
        Log.i(
            TAG,
            "Video profile: canvas=${size.width}x${size.height}" +
                ", content=${size.contentWidth}x${size.contentHeight}" +
                ", frameRate=${recorder.getConfiguredFrameRate()} fps" +
                ", target=${size.videoBitrate} bps" +
                if (force16By9Letterboxing) ", 16:9 letterbox" else "",
        )
    }

    private fun getSourceRefreshRate(): Float {
        try {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display = if (displayManager == null) {
                null
            } else {
                displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            }
            if (display == null) {
                return 0f
            }
            val refreshRate = display.refreshRate
            return if (refreshRate.isFinite() && refreshRate > 0f) refreshRate else 0f
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to read the source display refresh rate", error)
            return 0f
        }
    }

    @Throws(IOException::class)
    private fun awaitAccurateCaptureSize(fallback: CaptureSize): CaptureSize {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return fallback
        }
        try {
            capturedSizeReady.await(CAPTURE_SIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for capture dimensions", error)
        }
        return latestAccurateCaptureSize(fallback)
    }

    private fun latestAccurateCaptureSize(fallback: CaptureSize): CaptureSize {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return fallback
        }
        val packedSize = capturedContentSize.get()
        val width = (packedSize ushr 32).toInt()
        val height = packedSize.toInt()
        if (width <= 0 || height <= 0) {
            return fallback
        }
        return normalizeCaptureSize(
            width,
            height,
            fallback.densityDpi,
        )
    }

    @Throws(IOException::class)
    private fun reconfigureCaptureSize(accurateSize: CaptureSize) {
        if (accurateSize.width == preparedCaptureSize.width
            && accurateSize.height == preparedCaptureSize.height
            && accurateSize.videoBitrate == preparedCaptureSize.videoBitrate
            && accurateSize.targetFrameRate == preparedCaptureSize.targetFrameRate
        ) {
            preparedCaptureSize = accurateSize
            return
        }
        val display = virtualDisplay!!
        display.setSurface(null)
        releaseRecorderResources()
        prepareVideoRecorder(accurateSize)
        display.resize(
            accurateSize.width,
            accurateSize.height,
            accurateSize.densityDpi,
        )
        if (started) {
            display.setSurface(surface)
        }
        preparedCaptureSize = accurateSize
    }

    private fun releaseRecorderResources() {
        val recorder = videoRecorder
        if (recorder != null) {
            recorder.release()
            videoRecorder = null
        }
        surface = null
    }

    private fun disableAudio(error: Exception) {
        val notifyListener = !audioFailed
        audioFailed = true
        Log.e(TAG, "Audio capture failed; continuing without it", error)
        val stoppingMicrophoneRecorder = microphoneRecorder
        if (stoppingMicrophoneRecorder != null) {
            stoppingMicrophoneRecorder.requestStop()
        }
        val releasingAudioRecorder = audioRecorder
        if (releasingAudioRecorder != null) {
            releasingAudioRecorder.release()
            audioRecorder = null
        }
        val releasingMicrophoneRecorder = microphoneRecorder
        if (releasingMicrophoneRecorder != null) {
            releasingMicrophoneRecorder.release()
            microphoneRecorder = null
        }
        if (notifyListener) {
            listener.onAudioCaptureFailed()
        }
    }

    @Throws(IOException::class)
    private fun ensureEnoughStorageToStart() {
        val storageProbe = context.getExternalFilesDir(null) ?: context.cacheDir
        val usableSpace = storageProbe.usableSpace
        val availableSpace = Math.max(0L, usableSpace - STORAGE_RESERVE)
        if (availableSpace < MIN_FILE_SIZE) {
            throw IOException("Not enough storage space to start recording")
        }
    }

    private class CaptureSize(
        val width: Int,
        val height: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val videoBitrate: Int,
        val densityDpi: Int,
        val sourceRefreshRate: Float,
        val targetFrameRate: Int,
    )

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val MIN_FILE_SIZE = 32L * 1024L
        private const val STORAGE_RESERVE = 64L * 1024L
        private const val CAPTURE_SIZE_TIMEOUT_MS = 500L

        @JvmStatic
        fun deleteStaleTemporaryFiles(context: Context) {
            val cacheFiles = context.cacheDir.listFiles()
            if (cacheFiles == null) {
                return
            }
            for (file in cacheFiles) {
                val name = file.name
                if (name.startsWith("screen-video-")
                    || name.startsWith("screen-audio-")
                    || name.startsWith("screen-final-")
                ) {
                    if (!file.delete()) {
                        Log.w(TAG, "Unable to delete stale temporary file: $name")
                    }
                }
            }
        }

        @JvmStatic
        fun isVideoCodecSupported(requestedCodec: Int): Boolean {
            return VideoTrackRecorder.isCodecSupported(requestedCodec)
        }

        private fun packSize(width: Int, height: Int): Long {
            return (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)
        }
    }
}
