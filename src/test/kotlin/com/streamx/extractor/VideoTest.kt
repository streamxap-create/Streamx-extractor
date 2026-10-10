package com.streamx.extractor

import com.streamx.extractor.model.Stream
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.qualityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoTest {

    private fun vid(h: Int, codec: String, fps: Int = 30, bitrate: Int = 1000) = Stream(
        url = "u$h$codec$fps", itag = h, mimeType = "video/mp4; codecs=\"$codec\"", quality = "${h}p",
        bitrate = bitrate, isVideo = true, isAudio = false, adaptive = true,
        height = h, fps = fps, codecs = codec
    )

    private val audio = Stream(
        url = "a", itag = 140, mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", quality = "medium",
        bitrate = 128000, isVideo = false, isAudio = true, adaptive = true, codecs = "mp4a.40.2"
    )

    private fun video(vararg s: Stream) =
        Video("id", "t", "d", 10, "c", "cid", 1L, emptyList(), streams = s.toList())

    @Test
    fun pickBestChoosesHighestResolution() {
        val v = video(vid(1080, "avc1.640028"), vid(1440, "vp09.00.40.08"), vid(2160, "vp09.00.50.08"), audio)
        assertEquals(2160, v.pickBest()?.height)
    }

    @Test
    fun pickVideoRespectsMaxHeight() {
        val v = video(vid(1080, "avc1.640028"), vid(2160, "vp09.00.50.08"), audio)
        assertEquals(1080, v.pickVideo(1440)?.height)
    }

    @Test
    fun sameHeightPrefers60fpsThenAvc() {
        val v = video(vid(1080, "vp09", 30), vid(1080, "avc1", 30), vid(1080, "avc1", 60), audio)
        assertEquals(60, v.pickVideo(1080)?.fps)
    }

    @Test
    fun qualityLabels() {
        assertEquals("2K", qualityLabel(1440))
        assertEquals("4K 60fps", qualityLabel(2160, 60))
        assertEquals("8K", qualityLabel(4320))
        assertEquals("720p", qualityLabel(720))
    }

    @Test
    fun extractVideoIdFromLinks() {
        assertEquals("dQw4w9WgXcQ", StreamxExtractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", StreamxExtractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
        assertEquals("dQw4w9WgXcQ", StreamxExtractor.extractVideoId("dQw4w9WgXcQ"))
        assertNull(StreamxExtractor.extractVideoId("hello"))
    }
}
