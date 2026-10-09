package com.streamx.extractor.model

data class Stream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val quality: String,
    val bitrate: Int,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val adaptive: Boolean = false,   // true = alag video-only / audio-only (merge karna padta hai), false = muxed (video+audio ek file)
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val contentLength: Long = 0L,
    val codecs: String = "",         // "avc1.640028", "vp9", "av01...", "mp4a.40.2", "opus"
    val client: String = "",         // kaunse YouTube client se mila
    val userAgent: String = "",      // ye URL isi User-Agent se chalta hai (player mein lagao)
    val chunkSize: Long = 0L         // player is se bade Range na maange (0 = koi had nahi, 10MB tak theek)
) {
    val isMp4: Boolean get() = mimeType.contains("mp4")
    val isAvc: Boolean get() = codecs.startsWith("avc1")
    val isVp9: Boolean get() = codecs.startsWith("vp9") || codecs.startsWith("vp09")
    val isAv1: Boolean get() = codecs.startsWith("av01")
}
