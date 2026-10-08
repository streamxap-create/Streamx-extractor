package com.streamx.extractor

import com.streamx.extractor.model.Video
import com.streamx.extractor.service.YoutubeExtractor

object StreamxExtractor {
    private val youtube = YoutubeExtractor()

    fun getVideo(videoId: String): Video {
        return youtube.getVideo(videoId)
    }
}
