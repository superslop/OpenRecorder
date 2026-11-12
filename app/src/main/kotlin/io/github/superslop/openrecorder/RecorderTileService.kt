// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

class RecorderTileService : TileService() {
    @Volatile
    private var listening: Boolean = false

    private val stateListener = RecordingState.Listener { state ->
        if (listening) {
            updateTile(state)
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(RecordingState.get())
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        RecordingState.addListener(stateListener)
        updateTile(RecordingState.get())
    }

    override fun onStopListening() {
        listening = false
        RecordingState.removeListener(stateListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val state = RecordingState.get()
        if (state == RecordingState.IDLE) {
            if (isLocked) {
                unlockAndRun { openCapturePermission() }
            } else {
                openCapturePermission()
            }
            return
        }
        if (state == RecordingState.SAVING) {
            updateTile(state)
            return
        }

        Toast.makeText(this, R.string.recording_power_stop_hint, Toast.LENGTH_LONG).show()
        updateTile(state)
    }

    override fun onDestroy() {
        listening = false
        RecordingState.removeListener(stateListener)
        super.onDestroy()
    }

    private fun openCapturePermission() {
        val intent = Intent(this, RecorderTileActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                20,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun updateTile(recordingState: Int) {
        val tile = qsTile ?: return

        val tileState: Int
        val descriptionRes: Int
        when (recordingState) {
            RecordingState.PREPARING -> {
                tileState = Tile.STATE_ACTIVE
                descriptionRes = R.string.quick_settings_preparing
            }
            RecordingState.RECORDING -> {
                tileState = Tile.STATE_ACTIVE
                descriptionRes = R.string.quick_settings_recording
            }
            RecordingState.PAUSED -> {
                tileState = Tile.STATE_ACTIVE
                descriptionRes = R.string.quick_settings_paused
            }
            RecordingState.SAVING -> {
                tileState = Tile.STATE_UNAVAILABLE
                descriptionRes = R.string.quick_settings_saving
            }
            else -> {
                tileState = Tile.STATE_INACTIVE
                descriptionRes = 0
            }
        }

        val label = getString(R.string.quick_settings_tile_label)
        val description = if (descriptionRes == 0) "" else getString(descriptionRes)
        val hasDescription = description.isNotEmpty()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_recorder_toggle)
        tile.label = label
        tile.subtitle = description
        tile.state = tileState
        tile.contentDescription = if (!hasDescription) {
            label
        } else {
            getString(R.string.quick_settings_description, label, description)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = description
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "RecorderTileService"

        @JvmStatic
        fun requestStateRefresh(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, RecorderTileService::class.java),
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to request a Quick Settings tile refresh", error)
            }
        }
    }
}
