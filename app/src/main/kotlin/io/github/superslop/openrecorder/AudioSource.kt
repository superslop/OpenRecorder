// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

enum class AudioSource {
    NONE,
    INTERNAL,
    MICROPHONE,
    INTERNAL_AND_MICROPHONE;

    fun usesInternalAudio(): Boolean {
        return this == INTERNAL || this == INTERNAL_AND_MICROPHONE
    }

    fun usesMicrophone(): Boolean {
        return this == MICROPHONE || this == INTERNAL_AND_MICROPHONE
    }

    companion object {
        @JvmStatic
        fun fromOrdinal(ordinal: Int): AudioSource {
            val values: Array<AudioSource> = AudioSource.values()
            if (ordinal >= 0 && ordinal < values.size) {
                return values[ordinal]
            } else {
                return NONE
            }
        }
    }
}
