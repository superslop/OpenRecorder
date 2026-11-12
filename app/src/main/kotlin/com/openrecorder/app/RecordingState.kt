// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

class RecordingState private constructor() {
    fun interface Listener {
        fun onStateChanged(state: Int)
    }

    companion object {
        const val IDLE = 0
        const val PREPARING = 1
        const val RECORDING = 2
        const val PAUSED = 3
        const val SAVING = 4

        private val CURRENT_STATE: AtomicInteger = AtomicInteger(IDLE)
        private val LISTENERS: CopyOnWriteArraySet<Listener> = CopyOnWriteArraySet()
        private val MAIN_HANDLER: Handler = Handler(Looper.getMainLooper())

        @JvmStatic
        fun get(): Int {
            return CURRENT_STATE.get()
        }

        @JvmStatic
        fun set(state: Int) {
            val normalizedState: Int = normalize(state)
            if (CURRENT_STATE.getAndSet(normalizedState) == normalizedState) {
                return
            }

            if (Looper.myLooper() === Looper.getMainLooper()) {
                notifyListenersIfCurrent(normalizedState)
            } else {
                MAIN_HANDLER.post { notifyListenersIfCurrent(normalizedState) }
            }
        }

        @JvmStatic
        fun addListener(listener: Listener) {
            LISTENERS.add(listener)
        }

        @JvmStatic
        fun removeListener(listener: Listener) {
            LISTENERS.remove(listener)
        }

        private fun normalize(state: Int): Int {
            if (state >= IDLE && state <= SAVING) {
                return state
            } else {
                return IDLE
            }
        }

        private fun notifyListenersIfCurrent(state: Int) {
            if (CURRENT_STATE.get() != state) {
                return
            }
            for (listener in LISTENERS) {
                listener.onStateChanged(state)
            }
        }
    }
}
