// SPDX-License-Identifier: GPL-3.0-only

package io.github.superslop.openrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingOptionsTest {
    @Test
    fun factoryProfileIsTheDefault() {
        assertEquals(
            RecordingOptions.SAMPLE_RATE_44_1_KHZ,
            RecordingOptions.DEFAULT_SAMPLE_RATE,
        )
        assertEquals(
            RecordingOptions.VIDEO_RESOLUTION_NATIVE,
            RecordingOptions.DEFAULT_VIDEO_RESOLUTION,
        )
        assertEquals(
            RecordingOptions.VIDEO_FRAME_RATE_AUTO,
            RecordingOptions.DEFAULT_VIDEO_FRAME_RATE,
        )
        assertEquals(
            RecordingOptions.VIDEO_CODEC_H264,
            RecordingOptions.DEFAULT_VIDEO_CODEC,
        )
        assertEquals(
            RecordingOptions.VIDEO_BITRATE_AUTO,
            RecordingOptions.DEFAULT_VIDEO_BITRATE,
        )
    }
}
