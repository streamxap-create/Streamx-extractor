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
    val log: String = ""           // har client ka short report (debug ke liye screen par dikha sakte ho)
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
    fun pickVideo(maxHeight: Int): Stream? {
        val vids = videoOnlyStreams
        if (vids.isEmpty()) return null
        fun codecRank(s: Stream) = when {
            s.isAvc -> 3
            s.isVp9 -> 2
            else -> 1
        }
        val fit = vids.filter { it.height in 1..maxHeight }
        if (fit.isEmpty()) return vids.minByOrNull { it.height }
        return fit.maxWithOrNull(
            compareBy<Stream>({ it.height }, { codecRank(it) }, { it.bitrate })
        )
    }

    /** Sabse achi audio (m4a/AAC ko pehle rakhta hai) */
    fun pickAudio(): Stream? {
        val a = audioStreams
        if (a.isEmpty()) return null
        return a.maxWithOrNull(compareBy<Stream>({ if (it.isMp4) 1 else 0 }, { it.bitrate }))
    }

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
