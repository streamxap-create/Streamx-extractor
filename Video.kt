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
    val userAgent: String = "",    // purana field: best client ka User-Agent
    val client: String = "",       // best client ka naam
    val hlsUrl: String = "",       // HLS manifest (bonus fallback)
    val hlsUserAgent: String = "",
    val hlsMaxHeight: Int = 0,
    val log: String = "",          // har client ka short report (debug ke liye screen par dikha sakte ho)
    val keywords: List<String> = emptyList(),
    val isLive: Boolean = false,
    val category: String = "",
    val publishDate: String = "",
    val likeCount: Long = -1L,
    val subtitles: List<Subtitle> = emptyList(),
    val alternates: List<Video> = emptyList()   // doosre clients jinki adaptive streams verify hui (best fail ho to inhe try karo)
) {
    /** Alag video-only streams (sirf wo jo verify ho chuki hain) */
    val videoOnlyStreams: List<Stream>
        get() = streams.filter { it.isVideo && it.adaptive }

    val audioStreams: List<Stream>
        get() = streams.filter { it.isAudio }

    /** Video+audio ek file wali streams (itag 18 = 360p) */
    val muxedStreams: List<Stream>
        get() = streams.filter { it.isVideo && !it.adaptive }

    /** Merge ho sakne wali (video-only + audio) qualities aur muxed qualities, bade se chhote */
    val availableHeights: List<Int>
        get() {
            val list = mutableListOf<Int>()
            if (audioStreams.isNotEmpty()) list += videoOnlyStreams.map { it.height }
            list += muxedStreams.map { it.height }
            return list.filter { it > 0 }.distinct().sortedDescending()
        }

    /** Sabse badi playable height */
    val bestHeight: Int get() = availableHeights.firstOrNull() ?: 0

    /**
     * maxHeight tak ki sabse achi video-only stream. Same height par avc1 (H.264) ko pehle rakhta hai
     * kyunki har phone par hardware decode hota hai. Kuch na mile to sabse choti stream deta hai.
     */
    fun pickVideo(maxHeight: Int = Int.MAX_VALUE): Stream? {
        val vids = videoOnlyStreams
        if (vids.isEmpty()) return null
        fun codecRank(s: Stream) = when {
            s.isAvc -> 3
            s.isVp9 -> 2
            else -> 1
        }
        val fit = vids.filter { it.height in 1..maxHeight }
        if (fit.isEmpty()) return vids.minByOrNull { it.height }
        // 1440p/2160p/4320p par H.264 hota hi nahi, wahan VP9 phir AV1 chunta hai
        return fit.maxWithOrNull(
            compareBy<Stream>({ it.height }, { it.fps }, { codecRank(it) }, { it.bitrate })
        )
    }

    /** Sabse high quality (4K/8K tak jo bhi verify hui) */
    fun pickBest(): Stream? = pickVideo(Int.MAX_VALUE)

    /** Quality menu ke liye: 4320p (8K), 2160p (4K), 1440p (2K) ... bade se chhote */
    val qualityOptions: List<QualityOption>
        get() = availableHeights.map { h ->
            val fps = videoOnlyStreams.filter { it.height == h }.maxOfOrNull { it.fps }
                ?: muxedStreams.filter { it.height == h }.maxOfOrNull { it.fps } ?: 0
            QualityOption(h, fps, qualityLabel(h, fps), muxedOnly = videoOnlyStreams.none { it.height == h } || audioStreams.isEmpty())
        }

    /**
     * Sabse achi audio. Asli (original) language track ko pehle rakhta hai (dubbed nahi).
     * preferOpus = true to Opus (webm) pehle, warna AAC (m4a). maxBitrate se data bachao.
     */
    fun pickAudio(preferOpus: Boolean = false, maxBitrate: Int = Int.MAX_VALUE): Stream? {
        val all = audioStreams
        if (all.isEmpty()) return null
        val original = all.filter { it.audioIsDefault }.ifEmpty { all }
        val fit = original.filter { it.bitrate <= maxBitrate }.ifEmpty { listOf(original.minByOrNull { it.bitrate }!!) }
        fun codecRank(s: Stream): Int {
            val opus = s.codecs.startsWith("opus")
            return if (preferOpus) (if (opus) 1 else 0) else (if (s.isMp4) 1 else 0)
        }
        return fit.maxWithOrNull(compareBy<Stream>({ codecRank(it) }, { it.bitrate }))
    }

    /** Music app ke quality menu ke liye: "Opus 160kbps", "AAC 128kbps" ... */
    val audioOptions: List<AudioOption>
        get() = audioStreams.filter { it.audioIsDefault }.ifEmpty { audioStreams }
            .sortedByDescending { it.bitrate }
            .map { AudioOption(it.itag, audioLabel(it), it.bitrate, it.codecs, it.audioSampleRate) }

    val hasHdr: Boolean get() = videoOnlyStreams.any { it.isHdr }

    /** Aakhri safe option: video+audio ek file (itag 18) */
    fun pickMuxed(): Stream? =
        muxedStreams.firstOrNull { it.itag == 18 } ?: muxedStreams.maxByOrNull { it.height }
}

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
    val probeHttpCode: Int?,       // muxed stream ka test (200/206 = chalega, 403 = block)
    val error: String?,
    val maxHeight: Int = 0,        // is client se sabse badi height
    val videoProbe: Int? = null,   // adaptive video URL ka test
    val audioProbe: Int? = null    // adaptive audio URL ka test
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

data class QualityOption(
    val height: Int,
    val fps: Int,
    val label: String,       // "4K 60fps", "2K", "1080p"
    val muxedOnly: Boolean   // true = video+audio ek file (merge nahi)
)

fun qualityLabel(height: Int, fps: Int = 0): String {
    val base = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        else -> "${height}p"
    }
    return if (fps > 30) "$base ${fps}fps" else base
}

// Playlist / channel / related ka ek page
data class ItemPage(
    val title: String,
    val items: List<VideoItem>,
    val nextToken: String?
)

data class AudioOption(
    val itag: Int,
    val label: String,       // "Opus 160kbps"
    val bitrate: Int,
    val codecs: String,
    val sampleRate: Int
)

fun audioLabel(s: Stream): String {
    val name = when {
        s.codecs.startsWith("opus") -> "Opus"
        s.codecs.startsWith("mp4a") -> "AAC"
        else -> s.codecs.ifEmpty { "Audio" }
    }
    return "$name ${s.bitrate / 1000}kbps"
}
