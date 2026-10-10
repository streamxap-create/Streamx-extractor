package com.streamx.extractor.model

// ---------- Music / Spotify-jaise features ----------

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val thumbnailUrl: String = ""
)

enum class MusicType { SONG, VIDEO, ALBUM, ARTIST, PLAYLIST }

/** YouTube Music search ka ek natija. id = videoId (song/video), albumId "MPRE...", channelId "UC...", playlistId. */
data class MusicItem(
    val type: MusicType,
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: String = "",
    val subtitle: String = "",
    val thumbnailUrl: String = ""
)

enum class MusicFilter(val params: String?) {
    ALL(null),
    SONGS("EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS("EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    ARTISTS("EgWKAQIgAWoMEA4QChADEAQQCRAF"),
    PLAYLISTS("EgWKAQIoAWoMEA4QChADEAQQCRAF")
}

/** Normal YouTube search ke filters */
enum class SearchFilter(val params: String?) {
    ALL(null),
    VIDEOS("EgIQAQ=="),
    LIVE("EgJAAQ=="),
    UNDER_4_MIN("EgIYAQ=="),
    NEWEST("CAISAhAB")
}

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val synced: List<LyricLine>,   // time ke saath (karaoke style), khali ho sakta hai
    val plain: String,
    val instrumental: Boolean = false,
    val source: String = "LRCLIB"
)

data class Subtitle(
    val url: String,
    val languageCode: String,
    val name: String,
    val auto: Boolean
) {
    /** WebVTT format mein (player/subtitle view ke liye) */
    val vttUrl: String
        get() = if (url.contains("fmt=")) url.replace(Regex("fmt=[^&]*"), "fmt=vtt") else "$url&fmt=vtt"
}

data class ChannelInfo(
    val id: String,
    val name: String,
    val description: String,
    val avatarUrl: String,
    val url: String,
    val subscribers: String = "",
    val keywords: String = ""
)

data class Comment(
    val id: String,
    val author: String,
    val text: String,
    val likes: String,
    val published: String,
    val avatarUrl: String = "",
    val replyCount: String = ""
)

data class CommentPage(val comments: List<Comment>, val nextToken: String?)

fun parseClock(s: String): Int {
    val parts = s.trim().split(":")
    val nums = parts.mapNotNull { it.toIntOrNull() }
    if (nums.isEmpty() || nums.size != parts.size) return 0
    return nums.fold(0) { acc, x -> acc * 60 + x }
}

fun cleanArtist(name: String): String = name.removeSuffix(" - Topic").trim()

private fun splitArtistTitle(channel: String, title: String): Pair<String, String> {
    if (!channel.endsWith(" - Topic") && " - " in title) {
        val a = title.substringBefore(" - ").trim()
        val t = title.substringAfter(" - ").trim()
        if (a.isNotEmpty() && t.isNotEmpty()) return a to t
    }
    return cleanArtist(channel) to title
}

fun VideoItem.toTrack(): Track {
    val (artist, t) = splitArtistTitle(channelName, title)
    return Track(id, t, artist, "", parseClock(duration), thumbnailUrl)
}

fun Video.toTrack(): Track {
    val (artist, t) = splitArtistTitle(channelName, title)
    val thumb = thumbnails.maxByOrNull { it.width }?.url ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    return Track(id, t, artist, "", duration, thumb)
}

fun MusicItem.toTrack(): Track? =
    if (type == MusicType.SONG || type == MusicType.VIDEO)
        Track(id, title, artist, album, parseClock(duration), thumbnailUrl)
    else null
