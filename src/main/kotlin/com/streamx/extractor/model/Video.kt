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
    val streams: List<Stream> = emptyList(),
    val userAgent: String = "",   // stream URL isi User-Agent se chalta hai (player mein lagao)
    val client: String = "",      // kaunse client se mila
    val hlsUrl: String = "",      // HLS manifest (adaptive, 1080p tak) - bina PO token ke chal sakta hai
    val hlsUserAgent: String = "", // HLS ke liye User-Agent
    val hlsMaxHeight: Int = 0      // HLS manifest mein sabse badi quality (1080 etc), 0 = pata nahi
)

data class Thumbnail(
    val url: String,
    val width: Int,
    val height: Int
)

// diagnose() ka result: har YouTube client ka alag report
data class ClientReport(
    val client: String,
    val gotInfo: Boolean,          // videoDetails mila?
    val playability: String?,      // OK / LOGIN_REQUIRED / UNPLAYABLE ...
    val reason: String?,
    val streamCount: Int,
    val itags: List<Int>,
    val probeHttpCode: Int?,       // pehle stream URL ka test (200/206 = chalega, 403 = block)
    val error: String?
)

// Search / home feed ka ek item
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String,
    val duration: String,     // "3:33" (live ho to khali)
    val views: String,        // "1.8B views"
    val published: String,    // "2 years ago"
    val thumbnailUrl: String,
    val channelId: String = "",
    val channelThumbnail: String = ""   // channel ka logo
)

// Ek page: items + agla page lene ka token (null = aur nahi hai)
data class SearchPage(
    val items: List<VideoItem>,
    val nextToken: String?
)
