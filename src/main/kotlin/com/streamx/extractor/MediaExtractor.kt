package com.streamx.extractor

import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.SearchPage
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem
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

    /** Search / home feed: video list deta hai */
    fun search(query: String): List<VideoItem> {
        return youtube.search(query)
    }

    /** Page-wise search: pehli baar continuation = null, phir nextToken do (infinite scroll) */
    fun searchPage(query: String, continuation: String? = null): SearchPage {
        return youtube.searchPage(query, continuation)
    }
}
