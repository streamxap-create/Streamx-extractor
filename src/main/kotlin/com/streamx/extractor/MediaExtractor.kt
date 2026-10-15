package com.streamx.extractor

import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.ChannelInfo
import com.streamx.extractor.model.ItemPage
import com.streamx.extractor.model.Lyrics
import com.streamx.extractor.model.MusicFilter
import com.streamx.extractor.model.MusicItem
import com.streamx.extractor.model.SearchFilter
import com.streamx.extractor.service.LyricsService
import com.streamx.extractor.service.PlexClient
import java.util.concurrent.ConcurrentHashMap
import com.streamx.extractor.model.SearchPage
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem
import com.streamx.extractor.service.YoutubeExtractor

object StreamxExtractor {
    private val youtube = YoutubeExtractor()

    private class Cached(val at: Long, val video: Video)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val audioCache = ConcurrentHashMap<String, Cached>()
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

    /**
     * Sirf audio chahiye (music): getVideo se kai guna tez. Pehle se bani cache (video ya audio) ho to wahi.
     */
    fun getAudio(videoId: String, useCache: Boolean = true): Video {
        val ttl = ExtractorConfig.cacheSeconds * 1000L
        if (useCache && ttl > 0) {
            val hit = cache[videoId] ?: audioCache[videoId]
            if (hit != null && System.currentTimeMillis() - hit.at < ttl) return hit.video
        }
        val v = youtube.getAudio(videoId)
        if (ttl > 0) audioCache[videoId] = Cached(System.currentTimeMillis(), v)
        return v
    }

    fun prefetchAudio(videoId: String) {
        try { getAudio(videoId) } catch (e: Exception) { /* ignore */ }
    }

    fun clearCache() { cache.clear(); audioCache.clear() }

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

    /** Video ke comments ka pehla page (top comments). */
    fun getComments(videoId: String): com.streamx.extractor.model.CommentPage = youtube.getComments(videoId)

    /** Agla comments page — getComments() se mile nextToken se. */
    fun getCommentsPage(continuationToken: String): com.streamx.extractor.model.CommentPage =
        youtube.getCommentsPage(continuationToken)

    /** Link ya ID dono chalte hain: youtu.be/ID, watch?v=ID, shorts/ID, embed/ID */
    fun extractVideoId(urlOrId: String): String? {
        val t = urlOrId.trim()
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
        val m = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(t)
        return m?.groupValues?.get(1)
    }

    // ---- StreamX app ke liye extra ----
    /** "UC..." id, @handle ya channel URL -> channel id */
    fun resolveChannelId(input: String): String = youtube.resolveChannelId(input)

    fun getChannelShorts(channelId: String): List<VideoItem> = youtube.getChannelShorts(channelId)

    fun searchPlaylists(query: String): List<com.streamx.extractor.model.PlaylistSummary> =
        youtube.searchPlaylists(query)

    fun searchChannelAvatar(query: String): String = youtube.searchChannelAvatar(query)

    /** Channel ke naye uploads (RSS) — hamesha latest, date ke saath */
    fun channelFeed(channelId: String): List<VideoItem> = youtube.channelFeed(channelId)

    /** Search box autocomplete */
    fun suggestions(query: String): List<String> = youtube.suggestions(query)

    /** YouTube Music search: gaane, albums, artists, playlists */
    fun searchMusic(query: String, filter: MusicFilter = MusicFilter.SONGS): List<MusicItem> =
        youtube.searchMusic(query, filter)

    /** Autoplay / radio: is gaane jaise aur gaane */
    fun getRadio(videoId: String, includeSeed: Boolean = false): List<VideoItem> =
        youtube.getRadio(videoId, includeSeed)

    fun getChannelInfo(channelId: String): ChannelInfo = youtube.getChannelInfo(channelId)

    private val lyricsCache = ConcurrentHashMap<String, Lyrics>()

    /**
     * Video ke title + channel se lyrics (LRCLIB). "Official Video" jaisa shor khud saaf karta hai.
     * Mil jaye to cache hota hai; na mile ya network fail ho to null.
     */
    fun findLyricsForVideo(title: String, channelName: String, durationSec: Int = 0): Lyrics? {
        val key = "$title|$channelName|$durationSec"
        lyricsCache[key]?.let { return it }
        val l = lyricsService.findForVideo(title, channelName, durationSec)
        if (l != null) lyricsCache[key] = l
        return l
    }

    /** Synced lyrics (LRCLIB). Na mile to null. */
    fun findLyrics(title: String, artist: String, durationSec: Int = 0): Lyrics? =
        lyricsService.find(title, artist, durationSec)

    /** Apna Plex Media Server: URL + X-Plex-Token */
    fun plex(baseUrl: String, token: String): PlexClient = PlexClient(baseUrl, token)

    // Plex ke apne free Movies & TV catalog ke liye ek hi shared client — iska
    // (anonymous, no-sign-in) token isi instance me cache hota hai, isliye alag se ek
    // singleton hai (har baar naya object banaya to token bhi baar baar dobara lena
    // padega). App isi ko use kare — naya PlexDiscoverClient() khud mat banao.
    val plexDiscover: com.streamx.extractor.service.PlexDiscoverClient by lazy {
        com.streamx.extractor.service.PlexDiscoverClient()
    }
}
