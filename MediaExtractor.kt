package com.streamx.extractor

import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.ChannelInfo
import com.streamx.extractor.model.CommentPage
import com.streamx.extractor.model.ItemPage
import com.streamx.extractor.model.Lyrics
import com.streamx.extractor.model.MusicFilter
import com.streamx.extractor.model.MusicItem
import com.streamx.extractor.model.SearchFilter
import com.streamx.extractor.service.LyricsService
import java.util.concurrent.ConcurrentHashMap
import com.streamx.extractor.model.SearchPage
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem
import com.streamx.extractor.service.YoutubeExtractor

object StreamxExtractor {
    private val youtube = YoutubeExtractor()

    private class Cached(val at: Long, val video: Video)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val lyricsService = LyricsService()

    /** Cache ExtractorConfig.cacheSeconds tak (0 = band). useCache = false se zabardasti naya. */
    fun getVideo(videoId: String, useCache: Boolean = true): Video {
        val ttl = ExtractorConfig.cacheSeconds * 1000L
        if (useCache && ttl > 0) {
            cache[videoId]?.let { if (System.currentTimeMillis() - it.at < ttl) return it.video }
        }
        val v = youtube.getVideo(videoId)
        if (ttl > 0) cache[videoId] = Cached(System.currentTimeMillis(), v)
        return v
    }

    fun clearCache() = cache.clear()

    /** Agla gaana pehle se tayyar rakho (queue ke liye). Fail ho to chup-chap ignore. */
    fun prefetch(videoId: String) {
        try { getVideo(videoId) } catch (e: Exception) { /* ignore */ }
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
    fun searchPage(query: String, continuation: String? = null, filter: SearchFilter = SearchFilter.ALL): SearchPage {
        return youtube.searchPage(query, continuation, filter)
    }

    /** Playlist ke videos (page-wise) */
    fun getPlaylist(playlistId: String, continuation: String? = null): ItemPage {
        return youtube.getPlaylist(playlistId, continuation)
    }

    /** Channel ke videos (page-wise). channelId = "UC..." */
    fun getChannelVideos(channelId: String, continuation: String? = null): ItemPage {
        return youtube.getChannelVideos(channelId, continuation)
    }

    /** Related videos */
    fun getRelated(videoId: String): List<VideoItem> {
        return youtube.getRelated(videoId)
    }

    /** Link ya ID dono chalte hain: youtu.be/ID, watch?v=ID, shorts/ID, embed/ID */
    fun extractVideoId(urlOrId: String): String? {
        val t = urlOrId.trim()
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
        val m = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(t)
        return m?.groupValues?.get(1)
    }

    /** Search box autocomplete */
    fun suggestions(query: String): List<String> = youtube.suggestions(query)

    /** YouTube Music search: gaane, albums, artists, playlists */
    fun searchMusic(query: String, filter: MusicFilter = MusicFilter.SONGS): List<MusicItem> =
        youtube.searchMusic(query, filter)

    /** Autoplay / radio: is gaane jaise aur gaane */
    fun getRadio(videoId: String, includeSeed: Boolean = false): List<VideoItem> =
        youtube.getRadio(videoId, includeSeed)

    fun getChannelInfo(channelId: String): ChannelInfo = youtube.getChannelInfo(channelId)

    /** Experimental */
    fun getComments(videoId: String, continuation: String? = null): CommentPage =
        youtube.getComments(videoId, continuation)

    /** Synced lyrics (LRCLIB). Na mile to null. */
    fun findLyrics(title: String, artist: String, durationSec: Int = 0): Lyrics? =
        lyricsService.find(title, artist, durationSec)
}
