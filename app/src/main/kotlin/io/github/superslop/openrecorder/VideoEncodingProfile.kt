// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

/** Resolves the frame layout and exact target bitrate sent to the video encoder. */
class VideoEncodingProfile private constructor() {

    class Layout(
        @JvmField val outputWidth: Int,
        @JvmField val outputHeight: Int,
        @JvmField val contentWidth: Int,
        @JvmField val contentHeight: Int,
        @JvmField val videoBitrate: Int,
        @JvmField val letterboxed: Boolean,
    )

    companion object {
        private const val LANDSCAPE_16_BY_9: Double = 16.0 / 9.0
        private const val PORTRAIT_9_BY_16: Double = 9.0 / 16.0
        private const val LONG_EDGE_2160P: Int = 3_840
        private const val LONG_EDGE_1440P: Int = 2_560
        private const val LONG_EDGE_1080P: Int = 1_920
        private const val LONG_EDGE_720P: Int = 1_280
        private const val LONG_EDGE_480P: Int = 854
        private const val MAX_AUTOMATIC_VIDEO_BIT_RATE: Int = 24_000_000
        private val BASE_FRAME_RATE: Int = RecordingOptions.VIDEO_FRAME_RATE_30_FPS
        // Automatic bitrate is 0.10 bits per visible pixel per frame.
        // Black bars are excluded, and fixed bitrate choices are passed through unchanged.
        private const val AUTOMATIC_BITRATE_DIVISOR: Int = 10

        @JvmStatic
        fun resolve(
            requestedWidth: Int,
            requestedHeight: Int,
            requestedOrientation: Int,
            requestedResolution: Int,
            force16By9Letterboxing: Boolean,
            requestedBitrate: Int,
        ): Layout {
            return resolve(
                requestedWidth,
                requestedHeight,
                requestedOrientation,
                requestedResolution,
                force16By9Letterboxing,
                requestedBitrate,
                BASE_FRAME_RATE,
            )
        }

        @JvmStatic
        fun resolve(
            requestedWidth: Int,
            requestedHeight: Int,
            requestedOrientation: Int,
            requestedResolution: Int,
            force16By9Letterboxing: Boolean,
            requestedBitrate: Int,
            targetFrameRate: Int,
        ): Layout {
            if (requestedWidth <= 0 || requestedHeight <= 0) {
                throw IllegalArgumentException("Capture dimensions must be positive")
            }

            val orientation: Int = RecordingOptions.normalizeOrientation(requestedOrientation)
            var orientedWidth: Int = requestedWidth
            var orientedHeight: Int = requestedHeight
            val shouldSwap: Boolean =
                (orientation == RecordingOptions.ORIENTATION_PORTRAIT &&
                    orientedWidth > orientedHeight) ||
                    (orientation == RecordingOptions.ORIENTATION_LANDSCAPE &&
                        orientedHeight > orientedWidth)
            if (shouldSwap) {
                val originalWidth: Int = orientedWidth
                orientedWidth = orientedHeight
                orientedHeight = originalWidth
            }

            var canvasWidth: Double = orientedWidth.toDouble()
            var canvasHeight: Double = orientedHeight.toDouble()
            if (force16By9Letterboxing) {
                val portraitFrame: Boolean = orientedHeight > orientedWidth ||
                    (orientedHeight == orientedWidth &&
                        orientation != RecordingOptions.ORIENTATION_LANDSCAPE)
                val targetAspect: Double = if (portraitFrame) {
                    PORTRAIT_9_BY_16
                } else {
                    LANDSCAPE_16_BY_9
                }
                val contentAspect: Double = orientedWidth.toDouble() / orientedHeight.toDouble()
                if (contentAspect < targetAspect) {
                    canvasWidth = orientedHeight.toDouble() * targetAspect
                } else if (contentAspect > targetAspect) {
                    canvasHeight = orientedWidth.toDouble() / targetAspect
                }
            }

            val maximumLongEdge: Int = maximumLongEdge(requestedResolution)
            val canvasLongEdge: Double = Math.max(canvasWidth, canvasHeight)
            val scale: Double = if (maximumLongEdge > 0 &&
                canvasLongEdge > maximumLongEdge.toDouble()
            ) {
                maximumLongEdge.toDouble() / canvasLongEdge
            } else {
                1.0
            }

            val contentWidth: Int = toEvenDimension(orientedWidth.toDouble() * scale)
            val contentHeight: Int = toEvenDimension(orientedHeight.toDouble() * scale)
            val outputWidth: Int = Math.max(contentWidth, toEvenDimension(canvasWidth * scale))
            val outputHeight: Int = Math.max(contentHeight, toEvenDimension(canvasHeight * scale))
            val videoBitrate: Int = resolveBitrate(
                contentWidth,
                contentHeight,
                requestedBitrate,
                targetFrameRate,
            )
            return Layout(
                outputWidth,
                outputHeight,
                contentWidth,
                contentHeight,
                videoBitrate,
                force16By9Letterboxing,
            )
        }

        @JvmStatic
        private fun resolveBitrate(
            contentWidth: Int,
            contentHeight: Int,
            requestedBitrate: Int,
            targetFrameRate: Int,
        ): Int {
            val normalizedBitrate: Int = RecordingOptions.normalizeVideoBitrate(requestedBitrate)
            if (normalizedBitrate != RecordingOptions.VIDEO_BITRATE_AUTO) {
                return normalizedBitrate
            }

            val normalizedFrameRate: Int = RecordingOptions.normalizeVideoFrameRate(targetFrameRate)
            val bitrateFrameRate: Int = if (normalizedFrameRate ==
                RecordingOptions.VIDEO_FRAME_RATE_AUTO
            ) {
                BASE_FRAME_RATE
            } else {
                normalizedFrameRate
            }
            val automaticBitrate: Long = contentWidth.toLong() * contentHeight.toLong() *
                bitrateFrameRate.toLong() / AUTOMATIC_BITRATE_DIVISOR.toLong()
            return Math.min(MAX_AUTOMATIC_VIDEO_BIT_RATE.toLong(), automaticBitrate).toInt()
        }

        @JvmStatic
        private fun maximumLongEdge(requestedResolution: Int): Int {
            return when (requestedResolution) {
                RecordingOptions.VIDEO_RESOLUTION_NATIVE -> 0
                RecordingOptions.VIDEO_RESOLUTION_2160P -> LONG_EDGE_2160P
                RecordingOptions.VIDEO_RESOLUTION_1440P -> LONG_EDGE_1440P
                RecordingOptions.VIDEO_RESOLUTION_720P -> LONG_EDGE_720P
                RecordingOptions.VIDEO_RESOLUTION_480P -> LONG_EDGE_480P
                RecordingOptions.VIDEO_RESOLUTION_1080P -> LONG_EDGE_1080P
                else -> LONG_EDGE_1080P
            }
        }

        @JvmStatic
        private fun toEvenDimension(value: Double): Int {
            val rounded: Int = Math.round(value).toInt()
            return Math.max(2, rounded and 1.inv())
        }
    }
}
