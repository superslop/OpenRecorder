// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncodingProfileTest {
    @Test
    fun nativeResolutionKeepsSourceDimensions() {
        val layout = resolve(
            RecordingOptions.VIDEO_RESOLUTION_NATIVE,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 1080, 2340, 1080, 2340)
    }

    @Test
    fun resolutionPresetsPreserveAspectRatioAndCapLongEdge() {
        val fullHd = resolve(
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )
        val hd = resolve(
            RecordingOptions.VIDEO_RESOLUTION_720P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )
        val sd = resolve(
            RecordingOptions.VIDEO_RESOLUTION_480P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(fullHd, 886, 1920, 886, 1920)
        assertLayout(hd, 590, 1280, 590, 1280)
        assertLayout(sd, 394, 854, 394, 854)
    }

    @Test
    fun letterboxingProducesA16By9CanvasWithoutCropping() {
        val layout = resolve(
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            true,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 1080, 1920, 886, 1920)
    }

    @Test
    fun nativeLetterboxingKeepsNativeContentPixels() {
        val layout = resolve(
            RecordingOptions.VIDEO_RESOLUTION_NATIVE,
            true,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 1316, 2340, 1080, 2340)
        assertEquals(7_581_600, layout.videoBitrate)
    }

    @Test
    fun letterboxPixelsDoNotIncreaseAutomaticBitrate() {
        val regular = resolve(
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )
        val letterboxed = resolve(
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            true,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertEquals(regular.videoBitrate, letterboxed.videoBitrate)
        assertEquals(5_103_360, letterboxed.videoBitrate)
    }

    @Test
    fun automaticBitrateFallsMonotonicallyWithResolution() {
        val nativeBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_NATIVE, false, 0)
            .videoBitrate
        val fullHdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_1080P, false, 0)
            .videoBitrate
        val hdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_720P, false, 0)
            .videoBitrate
        val sdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_480P, false, 0)
            .videoBitrate

        assertEquals(7_581_600, nativeBitrate)
        assertEquals(5_103_360, fullHdBitrate)
        assertEquals(2_265_600, hdBitrate)
        assertEquals(1_009_428, sdBitrate)
        assertTrue(nativeBitrate > fullHdBitrate)
        assertTrue(fullHdBitrate > hdBitrate)
        assertTrue(hdBitrate > sdBitrate)
    }

    @Test
    fun forcedLandscapeIsAppliedBeforeLetterboxing() {
        val layout = VideoEncodingProfile.resolve(
            1080,
            2340,
            RecordingOptions.ORIENTATION_LANDSCAPE,
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            true,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 1920, 1080, 1920, 886)
    }

    @Test
    fun presetDoesNotUpscaleSmallerContent() {
        val layout = VideoEncodingProfile.resolve(
            720,
            1280,
            RecordingOptions.ORIENTATION_AUTOMATIC,
            RecordingOptions.VIDEO_RESOLUTION_1080P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 720, 1280, 720, 1280)
    }

    @Test
    fun everyFixedBitrateIsPassedThroughExactly() {
        val fixedBitrates = intArrayOf(
            RecordingOptions.VIDEO_BITRATE_4_MBPS,
            RecordingOptions.VIDEO_BITRATE_8_MBPS,
            RecordingOptions.VIDEO_BITRATE_16_MBPS,
            RecordingOptions.VIDEO_BITRATE_24_MBPS,
        )

        for (bitrate in fixedBitrates) {
            assertEquals(
                bitrate,
                resolve(
                    RecordingOptions.VIDEO_RESOLUTION_1080P,
                    true,
                    bitrate,
                ).videoBitrate,
            )
        }
    }

    @Test
    fun invalidResolutionFallsBackTo1080p() {
        val layout = resolve(
            Int.MAX_VALUE,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
        )

        assertLayout(layout, 886, 1920, 886, 1920)
    }

    @Test
    fun automaticBitrateScalesWithFrameRate() {
        val at30Fps = VideoEncodingProfile.resolve(
            1080,
            2340,
            RecordingOptions.ORIENTATION_AUTOMATIC,
            RecordingOptions.VIDEO_RESOLUTION_720P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
            RecordingOptions.VIDEO_FRAME_RATE_30_FPS,
        )
        val at60Fps = VideoEncodingProfile.resolve(
            1080,
            2340,
            RecordingOptions.ORIENTATION_AUTOMATIC,
            RecordingOptions.VIDEO_RESOLUTION_720P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
            RecordingOptions.VIDEO_FRAME_RATE_60_FPS,
        )
        val at120Fps = VideoEncodingProfile.resolve(
            1080,
            2340,
            RecordingOptions.ORIENTATION_AUTOMATIC,
            RecordingOptions.VIDEO_RESOLUTION_720P,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
            RecordingOptions.VIDEO_FRAME_RATE_120_FPS,
        )

        assertEquals(2_265_600, at30Fps.videoBitrate)
        assertEquals(4_531_200, at60Fps.videoBitrate)
        assertEquals(9_062_400, at120Fps.videoBitrate)
    }

    @Test
    fun automaticBitrateIsCappedAt24Mbps() {
        val layout = VideoEncodingProfile.resolve(
            1080,
            2340,
            RecordingOptions.ORIENTATION_AUTOMATIC,
            RecordingOptions.VIDEO_RESOLUTION_NATIVE,
            false,
            RecordingOptions.VIDEO_BITRATE_AUTO,
            RecordingOptions.VIDEO_FRAME_RATE_120_FPS,
        )

        assertEquals(24_000_000, layout.videoBitrate)
    }

    private companion object {
        private fun resolve(
            resolution: Int,
            letterbox: Boolean,
            bitrate: Int,
        ): VideoEncodingProfile.Layout {
            return VideoEncodingProfile.resolve(
                1080,
                2340,
                RecordingOptions.ORIENTATION_AUTOMATIC,
                resolution,
                letterbox,
                bitrate,
            )
        }

        private fun assertLayout(
            layout: VideoEncodingProfile.Layout,
            outputWidth: Int,
            outputHeight: Int,
            contentWidth: Int,
            contentHeight: Int,
        ) {
            assertEquals(outputWidth, layout.outputWidth)
            assertEquals(outputHeight, layout.outputHeight)
            assertEquals(contentWidth, layout.contentWidth)
            assertEquals(contentHeight, layout.contentHeight)
        }
    }
}
