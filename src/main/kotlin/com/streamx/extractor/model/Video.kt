package com.streamx.extractor.model

data class Video(
    val id: String,
    val title: String,
    val description: String,
    val duration: Int,
    val channelName: String,
    val channelId: String,
    val viewCount: Long,
    val thumbnails: List<Thumbnail>,
    val streams: List<Stream> = emptyList()
)

data class Thumbnail(
    val url: String,
    val width: Int,
    val height: Int
)
