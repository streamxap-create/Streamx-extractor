package com.streamx.extractor.model

data class Stream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val quality: String,
    val bitrate: Int,
    val isVideo: Boolean,
    val isAudio: Boolean
)
