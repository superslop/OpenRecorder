// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

class RecordingOptions private constructor() {
    companion object {
        const val SAMPLE_RATE_44_1_KHZ = 44_100
        const val SAMPLE_RATE_48_KHZ = 48_000
        const val DEFAULT_SAMPLE_RATE = SAMPLE_RATE_44_1_KHZ

        const val VIDEO_BITRATE_AUTO = 0
        const val VIDEO_BITRATE_4_MBPS = 4_000_000
        const val VIDEO_BITRATE_8_MBPS = 8_000_000
        const val VIDEO_BITRATE_16_MBPS = 16_000_000
        const val VIDEO_BITRATE_24_MBPS = 24_000_000
        const val DEFAULT_VIDEO_BITRATE = VIDEO_BITRATE_AUTO

        const val VIDEO_RESOLUTION_NATIVE = 0
        const val VIDEO_RESOLUTION_1080P = 1
        const val VIDEO_RESOLUTION_720P = 2
        const val VIDEO_RESOLUTION_480P = 3
        const val VIDEO_RESOLUTION_1440P = 4
        const val VIDEO_RESOLUTION_2160P = 5
        const val DEFAULT_VIDEO_RESOLUTION = VIDEO_RESOLUTION_NATIVE

        const val VIDEO_FRAME_RATE_AUTO = 0
        const val VIDEO_FRAME_RATE_120_FPS = 120
        const val VIDEO_FRAME_RATE_90_FPS = 90
        const val VIDEO_FRAME_RATE_60_FPS = 60
        const val VIDEO_FRAME_RATE_30_FPS = 30
        const val DEFAULT_VIDEO_FRAME_RATE = VIDEO_FRAME_RATE_AUTO

        const val VIDEO_CODEC_H264 = 0
        const val VIDEO_CODEC_H265 = 1
        const val DEFAULT_VIDEO_CODEC = VIDEO_CODEC_H264

        const val COUNTDOWN_OFF = 0
        const val COUNTDOWN_3_SECONDS = 3
        const val COUNTDOWN_5_SECONDS = 5
        const val COUNTDOWN_10_SECONDS = 10
        const val DEFAULT_COUNTDOWN_SECONDS = COUNTDOWN_OFF

        const val ORIENTATION_AUTOMATIC = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val DEFAULT_ORIENTATION = ORIENTATION_AUTOMATIC

        const val NAMING_DAY_MONTH_YEAR = "dd-MM-yyyy_HH-mm-ss"
        const val NAMING_MONTH_DAY_YEAR = "MM-dd-yyyy_HH-mm-ss"
        const val NAMING_YEAR_MONTH_DAY = "yyyy-MM-dd_HH-mm-ss"
        const val NAMING_YEAR_DAY_MONTH = "yyyy-dd-MM_HH-mm-ss"
        const val DEFAULT_NAMING_PATTERN = NAMING_DAY_MONTH_YEAR

        @JvmStatic
        fun normalizeSampleRate(value: Int): Int {
            if (value == SAMPLE_RATE_48_KHZ) {
                return SAMPLE_RATE_48_KHZ
            } else {
                return SAMPLE_RATE_44_1_KHZ
            }
        }

        @JvmStatic
        fun normalizeVideoBitrate(value: Int): Int {
            if (value == VIDEO_BITRATE_4_MBPS ||
                value == VIDEO_BITRATE_8_MBPS ||
                value == VIDEO_BITRATE_16_MBPS ||
                value == VIDEO_BITRATE_24_MBPS
            ) {
                return value
            }
            return VIDEO_BITRATE_AUTO
        }

        @JvmStatic
        fun normalizeVideoResolution(value: Int): Int {
            if (value == VIDEO_RESOLUTION_NATIVE ||
                value == VIDEO_RESOLUTION_1080P ||
                value == VIDEO_RESOLUTION_720P ||
                value == VIDEO_RESOLUTION_480P ||
                value == VIDEO_RESOLUTION_1440P ||
                value == VIDEO_RESOLUTION_2160P
            ) {
                return value
            }
            return DEFAULT_VIDEO_RESOLUTION
        }

        @JvmStatic
        fun normalizeVideoFrameRate(value: Int): Int {
            if (value == VIDEO_FRAME_RATE_120_FPS ||
                value == VIDEO_FRAME_RATE_90_FPS ||
                value == VIDEO_FRAME_RATE_60_FPS ||
                value == VIDEO_FRAME_RATE_30_FPS
            ) {
                return value
            }
            return VIDEO_FRAME_RATE_AUTO
        }

        @JvmStatic
        fun normalizeVideoCodec(value: Int): Int {
            if (value == VIDEO_CODEC_H265) {
                return VIDEO_CODEC_H265
            } else {
                return VIDEO_CODEC_H264
            }
        }

        @JvmStatic
        fun normalizeCountdownSeconds(value: Int): Int {
            if (value == COUNTDOWN_3_SECONDS ||
                value == COUNTDOWN_5_SECONDS ||
                value == COUNTDOWN_10_SECONDS
            ) {
                return value
            }
            return COUNTDOWN_OFF
        }

        @JvmStatic
        fun normalizeOrientation(value: Int): Int {
            if (value == ORIENTATION_PORTRAIT || value == ORIENTATION_LANDSCAPE) {
                return value
            } else {
                return ORIENTATION_AUTOMATIC
            }
        }

        @JvmStatic
        fun normalizeNamingPattern(value: String?): String {
            if (value != null &&
                (value == NAMING_MONTH_DAY_YEAR ||
                    value == NAMING_YEAR_MONTH_DAY ||
                    value == NAMING_YEAR_DAY_MONTH)
            ) {
                return value
            } else {
                return NAMING_DAY_MONTH_YEAR
            }
        }
    }
}
