// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class RecordingService : Service(), ScreenRecorder.Listener {
    private val stopping = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val recorderStarted = AtomicBoolean()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var executor: ExecutorService
    private lateinit var notificationManager: NotificationManager

    @Volatile
    private var recorder: ScreenRecorder? = null

    @Volatile
    private var pendingStart: Runnable? = null
    private var pendingNotificationRestore: Runnable? = null

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var recordingStartedAtElapsedRealtime: Long = 0L

    @Volatile
    private var pausedAtElapsedRealtime: Long = 0L

    @Volatile
    private var totalPausedDurationMs: Long = 0L

    @Volatile
    private var stopWhenLockScreen: Boolean = false
    private var audioSource: AudioSource = AudioSource.NONE

    private val screenOffReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Intent.ACTION_SCREEN_OFF == intent?.action
                && stopWhenLockScreen
                && !finished.get()
                && (recorder != null || pendingStart != null)
            ) {
                stopAndSave()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ScreenRecorderWorker")
        }
        executor.execute { ScreenRecorder.deleteStaleTemporaryFiles(this) }
        notificationManager = getSystemService(NotificationManager::class.java)
        val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                screenOffReceiver,
                screenOffFilter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(screenOffReceiver, screenOffFilter)
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_recording_text)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (ACTION_DELETE == intent.action) {
            deleteSavedRecording(intent, startId)
            return START_NOT_STICKY
        }
        if (finished.get()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> startFromIntent(intent)
            ACTION_CANCEL_PENDING -> cancelPendingStart()
            ACTION_RECORDING_NOTIFICATION_DISMISSED -> {
                scheduleRecordingNotificationRestore()
                if (recorder == null && RecordingState.get() == RecordingState.IDLE) {
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startFromIntent(intent: Intent) {
        if (recorder != null || RecordingState.get() != RecordingState.IDLE
            || stopping.get()
        ) {
            return
        }

        audioSource = AudioSource.fromOrdinal(
            intent.getIntExtra(EXTRA_AUDIO_SOURCE, AudioSource.NONE.ordinal),
        )
        try {
            startForegroundCompat(createRecordingNotification(true))
            setRecordingState(RecordingState.PREPARING)

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                throw IllegalArgumentException("Missing or invalid MediaProjection result")
            }

            val sampleRate = RecordingOptions.normalizeSampleRate(
                intent.getIntExtra(
                    EXTRA_SAMPLE_RATE,
                    RecordingOptions.DEFAULT_SAMPLE_RATE,
                ),
            )
            val videoResolution = RecordingOptions.normalizeVideoResolution(
                intent.getIntExtra(
                    EXTRA_VIDEO_RESOLUTION,
                    RecordingOptions.DEFAULT_VIDEO_RESOLUTION,
                ),
            )
            val videoFrameRate = RecordingOptions.normalizeVideoFrameRate(
                intent.getIntExtra(
                    EXTRA_VIDEO_FRAME_RATE,
                    RecordingOptions.DEFAULT_VIDEO_FRAME_RATE,
                ),
            )
            val force16By9Letterboxing = intent.getBooleanExtra(
                EXTRA_FORCE_16_BY_9_LETTERBOXING,
                false,
            )
            stopWhenLockScreen = intent.getBooleanExtra(
                EXTRA_STOP_WHEN_LOCK_SCREEN,
                false,
            )
            val videoBitrate = RecordingOptions.normalizeVideoBitrate(
                intent.getIntExtra(
                    EXTRA_VIDEO_BITRATE,
                    RecordingOptions.DEFAULT_VIDEO_BITRATE,
                ),
            )
            val videoCodec = RecordingOptions.normalizeVideoCodec(
                intent.getIntExtra(
                    EXTRA_VIDEO_CODEC,
                    RecordingOptions.DEFAULT_VIDEO_CODEC,
                ),
            )
            val namingPattern = RecordingOptions.normalizeNamingPattern(
                intent.getStringExtra(EXTRA_NAMING_PATTERN),
            )
            val recordingOrientation = RecordingOptions.normalizeOrientation(
                intent.getIntExtra(
                    EXTRA_ORIENTATION,
                    RecordingOptions.DEFAULT_ORIENTATION,
                ),
            )
            val pendingRecorder = ScreenRecorder(
                this,
                resultCode,
                resultData,
                audioSource,
                sampleRate,
                videoResolution,
                videoFrameRate,
                force16By9Letterboxing,
                videoBitrate,
                videoCodec,
                namingPattern,
                recordingOrientation,
                this,
            )
            recorder = pendingRecorder

            val requestedStart = intent.getLongExtra(
                EXTRA_START_AT_ELAPSED_REALTIME,
                SystemClock.elapsedRealtime(),
            )
            executor.execute { prepareRecorder(pendingRecorder, requestedStart) }
        } catch (error: Exception) {
            failRecording(error, recorder)
        }
    }

    private fun prepareRecorder(pendingRecorder: ScreenRecorder, requestedStart: Long) {
        if (finished.get() || stopping.get()) {
            pendingRecorder.release()
            finishService()
            return
        }

        try {
            pendingRecorder.prepare()
            if (finished.get() || stopping.get() || recorder !== pendingRecorder) {
                pendingRecorder.release()
                finishService()
                return
            }

            val now = SystemClock.elapsedRealtime()
            val delay = Math.max(0L, Math.min(requestedStart - now, MAX_PREPARATION_DELAY_MS))
            val scheduledStart: Runnable = object : Runnable {
                override fun run() {
                    if (pendingStart !== this) {
                        return
                    }
                    pendingStart = null
                    if (finished.get() || stopping.get() || recorder !== pendingRecorder) {
                        return
                    }
                    executor.execute { startRecorder(pendingRecorder) }
                }
            }
            pendingStart = scheduledStart
            mainHandler.postDelayed(scheduledStart, delay)
        } catch (error: Exception) {
            if (stopping.get()) {
                pendingRecorder.release()
                finishService()
            } else {
                failRecording(error, pendingRecorder)
            }
        }
    }

    private fun startRecorder(pendingRecorder: ScreenRecorder) {
        if (finished.get() || stopping.get()) {
            pendingRecorder.release()
            finishService()
            return
        }

        try {
            pendingRecorder.start()
            recorderStarted.set(true)
            recordingStartedAtElapsedRealtime = SystemClock.elapsedRealtime()
            pausedAtElapsedRealtime = 0L
            totalPausedDurationMs = 0L
            paused = false
            if (!stopping.get() && !finished.get()) {
                setRecordingState(RecordingState.RECORDING)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createRecordingNotification(false),
                )
            }
        } catch (error: Exception) {
            if (stopping.get()) {
                pendingRecorder.release()
                finishService()
            } else {
                failRecording(error, pendingRecorder)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && audioSource.usesMicrophone()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun createRecordingNotification(preparing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (preparing) {
            builder.setContentText(getString(R.string.notification_preparing))
                .setShowWhen(false)
        } else {
            val notificationDismissedIntent = PendingIntent.getService(
                this,
                4,
                createRecordingNotificationDismissedIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setDeleteIntent(notificationDismissedIntent)

            val notificationPaused = paused

            if (notificationPaused) {
                val elapsed = DateUtils.formatElapsedTime(
                    getActiveRecordingDurationMs() / 1_000L,
                )
                builder.setContentText(getString(R.string.notification_paused_time, elapsed))
                    .setShowWhen(false)
            } else {
                builder.setContentText(getString(R.string.notification_recording_text))
                    .setWhen(System.currentTimeMillis() - getActiveRecordingDurationMs())
                    .setUsesChronometer(true)
                    .setShowWhen(true)
            }
        }

        return builder.build()
    }

    private fun scheduleRecordingNotificationRestore() {
        if (!isRecordingNotificationRequired()) {
            return
        }

        cancelPendingNotificationRestore()
        val restoreNotification: Runnable = object : Runnable {
            override fun run() {
                if (pendingNotificationRestore !== this) {
                    return
                }
                pendingNotificationRestore = null
                if (isRecordingNotificationRequired()) {
                    startForegroundCompat(createRecordingNotification(false))
                }
            }
        }
        pendingNotificationRestore = restoreNotification
        mainHandler.postDelayed(
            restoreNotification,
            RECORDING_NOTIFICATION_RESTORE_DELAY_MS,
        )
    }

    private fun isRecordingNotificationRequired(): Boolean {
        val state = RecordingState.get()
        return recorder != null
            && recorderStarted.get()
            && !stopping.get()
            && !finished.get()
            && (state == RecordingState.RECORDING || state == RecordingState.PAUSED)
    }

    private fun cancelPendingNotificationRestore() {
        val restoreNotification = pendingNotificationRestore
        if (restoreNotification != null) {
            mainHandler.removeCallbacks(restoreNotification)
            pendingNotificationRestore = null
        }
    }

    private fun createSavingNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_saving))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createSavedNotification(uri: Uri): Notification {
        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val viewPendingIntent = PendingIntent.getActivity(
            this,
            10,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sendIntent.clipData = ClipData.newRawUri("recording", uri)
        val chooserIntent = Intent.createChooser(
            sendIntent,
            getString(R.string.share_video),
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val sharePendingIntent = PendingIntent.getActivity(
            this,
            11,
            chooserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shareAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_share,
            getString(R.string.action_share),
            sharePendingIntent,
        ).build()
        val deletePendingIntent = PendingIntent.getService(
            this,
            12,
            createDeleteIntent(this, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deleteAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_delete,
            getString(R.string.action_delete),
            deletePendingIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentTitle(getString(R.string.notification_saved_title))
            .setContentText(getString(R.string.notification_saved_text))
            .setContentIntent(viewPendingIntent)
            .setAutoCancel(true)
            .addAction(shareAction)
            .addAction(deleteAction)
            .build()
    }

    private fun createErrorNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_info)
            .setContentTitle(getString(R.string.notification_error_title))
            .setContentText(getString(R.string.notification_error_text))
            .setAutoCancel(true)
            .build()
    }

    private fun cancelPendingStart() {
        if (recorderStarted.get()) {
            return
        }
        stopAndSave()
    }

    private fun pauseRecording() {
        val activeRecorder = recorder
        if (activeRecorder == null || paused || stopping.get() || finished.get()) {
            return
        }

        try {
            activeRecorder.pause()
            if (recorder !== activeRecorder || stopping.get() || finished.get()) {
                return
            }
            pausedAtElapsedRealtime = SystemClock.elapsedRealtime()
            paused = true
            setRecordingState(RecordingState.PAUSED)
            notificationManager.notify(
                NOTIFICATION_ID,
                createRecordingNotification(false),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to pause recording", error)
            runOnMainThread {
                Toast.makeText(
                    this,
                    R.string.pause_recording_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun resumeRecording() {
        val activeRecorder = recorder
        if (activeRecorder == null || !paused || stopping.get() || finished.get()) {
            return
        }

        try {
            activeRecorder.resume()
            if (recorder !== activeRecorder || stopping.get() || finished.get()) {
                return
            }
            val resumedAt = SystemClock.elapsedRealtime()
            totalPausedDurationMs += Math.max(0L, resumedAt - pausedAtElapsedRealtime)
            pausedAtElapsedRealtime = 0L
            paused = false
            setRecordingState(RecordingState.RECORDING)
            notificationManager.notify(
                NOTIFICATION_ID,
                createRecordingNotification(false),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to resume recording", error)
            runOnMainThread {
                Toast.makeText(
                    this,
                    R.string.resume_recording_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun getActiveRecordingDurationMs(): Long {
        val startedAt = recordingStartedAtElapsedRealtime
        if (startedAt <= 0L) {
            return 0L
        }
        val end = if (paused) pausedAtElapsedRealtime else SystemClock.elapsedRealtime()
        return Math.max(0L, end - startedAt - totalPausedDurationMs)
    }

    private fun deleteSavedRecording(intent: Intent, startId: Int) {
        val uri = intent.data
        var deleted = false
        if (uri != null) {
            try {
                deleted = contentResolver.delete(uri, null, null) > 0
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to delete saved recording", error)
            }
        }

        if (deleted) {
            notificationManager.cancel(SAVED_NOTIFICATION_ID)
            Toast.makeText(this, R.string.recording_deleted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.recording_delete_failed, Toast.LENGTH_LONG).show()
        }
        if (recorder == null && RecordingState.get() == RecordingState.IDLE) {
            stopSelf(startId)
        }
    }

    private fun stopAndSave() {
        if (finished.get() || !stopping.compareAndSet(false, true)) {
            return
        }

        val scheduledStart = pendingStart
        if (scheduledStart != null) {
            mainHandler.removeCallbacks(scheduledStart)
            pendingStart = null
            val cancelledRecorder = recorder
            recorder = null
            executor.execute {
                if (cancelledRecorder != null) {
                    cancelledRecorder.release()
                }
                finishService()
            }
            return
        }

        val activeRecorder = recorder
        if (activeRecorder == null) {
            finishService()
            return
        }
        recorder = null
        val shouldSave = recorderStarted.get()
        if (shouldSave) {
            setRecordingState(RecordingState.SAVING)
            notificationManager.notify(NOTIFICATION_ID, createSavingNotification())
        }

        executor.execute {
            if (finished.get()) {
                return@execute
            }
            if (!shouldSave) {
                activeRecorder.release()
                finishService()
                return@execute
            }

            try {
                activeRecorder.stop()
                val uri = activeRecorder.save()
                notificationManager.notify(
                    SAVED_NOTIFICATION_ID,
                    createSavedNotification(uri),
                )
                runOnMainThread {
                    Toast.makeText(
                        this,
                        R.string.saved_success,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to stop or save recording", error)
                notificationManager.notify(
                    SAVED_NOTIFICATION_ID,
                    createErrorNotification(),
                )
            } finally {
                activeRecorder.release()
                finishService()
            }
        }
    }

    private fun failRecording(error: Exception, failedRecorder: ScreenRecorder?) {
        Log.e(TAG, "Unable to start recording", error)
        if (failedRecorder != null) {
            if (recorder === failedRecorder) {
                recorder = null
            }
            failedRecorder.release()
        }
        runOnMainThread {
            Toast.makeText(
                this,
                R.string.recording_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
        notificationManager.notify(SAVED_NOTIFICATION_ID, createErrorNotification())
        finishService()
    }

    private fun finishService() {
        if (!finished.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            cancelPendingNotificationRestore()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runOnMainThread(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    private fun setRecordingState(state: Int) {
        RecordingState.set(state)
        RecorderTileService.requestStateRefresh(this)
    }

    override fun onProjectionStopped() {
        stopAndSave()
    }

    override fun onRecorderError(error: Exception) {
        Log.e(TAG, "Video encoder error", error)
        stopAndSave()
    }

    override fun onAudioCaptureFailed() {
        if (finished.get() || (stopping.get() && !recorderStarted.get())) {
            return
        }
        runOnMainThread {
            Toast.makeText(
                this,
                R.string.audio_capture_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        cancelPendingNotificationRestore()
        val scheduledStart = pendingStart
        if (scheduledStart != null) {
            mainHandler.removeCallbacks(scheduledStart)
            pendingStart = null
        }
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            activeRecorder.release()
        }
        setRecordingState(RecordingState.IDLE)
        if (::executor.isInitialized) {
            executor.shutdownNow()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 4273
        private const val SAVED_NOTIFICATION_ID = 4274
        private const val MAX_PREPARATION_DELAY_MS = 10_000L
        private const val RECORDING_NOTIFICATION_RESTORE_DELAY_MS = 1_000L

        private const val ACTION_START = "io.github.superslop.openrecorder.action.START"
        private const val ACTION_CANCEL_PENDING =
            "io.github.superslop.openrecorder.action.CANCEL_PENDING"
        private const val ACTION_RECORDING_NOTIFICATION_DISMISSED =
            "io.github.superslop.openrecorder.action.RECORDING_NOTIFICATION_DISMISSED"
        private const val ACTION_DELETE = "io.github.superslop.openrecorder.action.DELETE"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_AUDIO_SOURCE = "audio_source"
        private const val EXTRA_SAMPLE_RATE = "sample_rate"
        private const val EXTRA_VIDEO_RESOLUTION = "video_resolution"
        private const val EXTRA_VIDEO_FRAME_RATE = "video_frame_rate"
        private const val EXTRA_FORCE_16_BY_9_LETTERBOXING = "force_16_by_9_letterboxing"
        private const val EXTRA_STOP_WHEN_LOCK_SCREEN = "stop_when_lock_screen"
        private const val EXTRA_VIDEO_BITRATE = "video_bitrate"
        private const val EXTRA_VIDEO_CODEC = "video_codec"
        private const val EXTRA_NAMING_PATTERN = "naming_pattern"
        private const val EXTRA_ORIENTATION = "recording_orientation"
        private const val EXTRA_START_AT_ELAPSED_REALTIME = "start_at_elapsed_realtime"

        @JvmStatic
        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            audioSource: AudioSource,
            sampleRate: Int,
            videoResolution: Int,
            videoFrameRate: Int,
            force16By9Letterboxing: Boolean,
            stopWhenLockScreen: Boolean,
            videoBitrate: Int,
            videoCodec: Int,
            namingPattern: String,
            recordingOrientation: Int,
            startAtElapsedRealtime: Long,
        ): Intent {
            return Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSource.ordinal)
                .putExtra(
                    EXTRA_SAMPLE_RATE,
                    RecordingOptions.normalizeSampleRate(sampleRate),
                )
                .putExtra(
                    EXTRA_VIDEO_RESOLUTION,
                    RecordingOptions.normalizeVideoResolution(videoResolution),
                )
                .putExtra(
                    EXTRA_VIDEO_FRAME_RATE,
                    RecordingOptions.normalizeVideoFrameRate(videoFrameRate),
                )
                .putExtra(EXTRA_FORCE_16_BY_9_LETTERBOXING, force16By9Letterboxing)
                .putExtra(EXTRA_STOP_WHEN_LOCK_SCREEN, stopWhenLockScreen)
                .putExtra(
                    EXTRA_VIDEO_BITRATE,
                    RecordingOptions.normalizeVideoBitrate(videoBitrate),
                )
                .putExtra(
                    EXTRA_VIDEO_CODEC,
                    RecordingOptions.normalizeVideoCodec(videoCodec),
                )
                .putExtra(
                    EXTRA_NAMING_PATTERN,
                    RecordingOptions.normalizeNamingPattern(namingPattern),
                )
                .putExtra(
                    EXTRA_ORIENTATION,
                    RecordingOptions.normalizeOrientation(recordingOrientation),
                )
                .putExtra(EXTRA_START_AT_ELAPSED_REALTIME, startAtElapsedRealtime)
        }

        @JvmStatic
        fun createCancelPendingIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java)
                .setAction(ACTION_CANCEL_PENDING)
        }

        private fun createRecordingNotificationDismissedIntent(context: Context): Intent {
            return Intent(context, RecordingService::class.java)
                .setAction(ACTION_RECORDING_NOTIFICATION_DISMISSED)
        }

        private fun createDeleteIntent(context: Context, uri: Uri): Intent {
            return Intent(context, RecordingService::class.java)
                .setAction(ACTION_DELETE)
                .setData(uri)
        }
    }
}
