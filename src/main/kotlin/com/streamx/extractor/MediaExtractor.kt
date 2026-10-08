package com.streamx.extractor

import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.Video
import com.streamx.extractor.service.YoutubeExtractor

object StreamxExtractor {
    private val youtube = YoutubeExtractor()

    fun getVideo(videoId: String): Video {
        return youtube.getVideo(videoId)
    }

    /** Har client try karke report deta hai (kaun chala, kitni streams, URL chalta hai ya 403) */
    fun diagnose(videoId: String): List<ClientReport> {
        return youtube.diagnose(videoId)
    }
}

