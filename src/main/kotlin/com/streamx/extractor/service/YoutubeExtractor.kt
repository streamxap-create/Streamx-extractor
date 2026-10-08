package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.Stream
import com.streamx.extractor.model.Thumbnail
import com.streamx.extractor.model.Video

class YoutubeExtractor(
    private val downloader: Downloader = Downloader()
) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun getVideo(videoId: String): Video {
        val url = "https://www.youtube.com/youtubei/v1/player"

        val payload = """
            {
              "context": {
                "client": {
                  "clientName": "ANDROID",
                  "clientVersion": "19.09.37",
                  "androidSdkVersion": 30,
                  "hl": "en",
                  "gl": "IN"
                }
              },
              "videoId": "$videoId"
            }
        """.trimIndent()

        val response = downloader.postJson(url, payload)
        val root = moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>

        val details = root["videoDetails"] as? Map<*, *>
            ?: throw Exception("Video not found: $videoId")

        val streamingData = root["streamingData"] as? Map<*, *>

        return Video(
            id = videoId,
            title = details["title"] as? String ?: "",
            description = details["shortDescription"] as? String ?: "",
            duration = (details["lengthSeconds"] as? String)?.toIntOrNull() ?: 0,
            channelName = details["author"] as? String ?: "",
            channelId = details["channelId"] as? String ?: "",
            viewCount = (details["viewCount"] as? String)?.toLongOrNull() ?: 0L,
            thumbnails = parseThumbnails(details),
            streams = parseStreams(streamingData)
        )
    }

    private fun parseThumbnails(details: Map<*, *>): List<Thumbnail> {
        val thumb = details["thumbnail"] as? Map<*, *> ?: return emptyList()
        val list = thumb["thumbnails"] as? List<*> ?: return emptyList()

        return list.mapNotNull { item ->
            val t = item as? Map<*, *> ?: return@mapNotNull null
            Thumbnail(
                url = t["url"] as? String ?: "",
                width = (t["width"] as? Number)?.toInt() ?: 0,
                height = (t["height"] as? Number)?.toInt() ?: 0
            )
        }
    }

    private fun parseStreams(data: Map<*, *>?): List<Stream> {
        if (data == null) return emptyList()

        val result = mutableListOf<Stream>()
        val formats = (data["formats"] as? List<*>) ?: emptyList<Any>()
        val adaptive = (data["adaptiveFormats"] as? List<*>) ?: emptyList<Any>()

        (formats + adaptive).forEach { item ->
            val f = item as? Map<*, *> ?: return@forEach
            val mime = f["mimeType"] as? String ?: return@forEach
            val url = f["url"] as? String ?: return@forEach

            result.add(
                Stream(
                    url = url,
                    itag = (f["itag"] as? Number)?.toInt() ?: 0,
                    mimeType = mime,
                    quality = f["qualityLabel"] as? String ?: "unknown",
                    bitrate = (f["bitrate"] as? Number)?.toInt() ?: 0,
                    isVideo = mime.startsWith("video"),
                    isAudio = mime.startsWith("audio")
                )
            )
        }
        return result
    }
}
