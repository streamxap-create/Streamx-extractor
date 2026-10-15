package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.LyricLine
import com.streamx.extractor.model.Lyrics
import com.streamx.extractor.model.cleanArtist
import java.net.URLEncoder
import kotlin.math.abs

object LrcParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** "[01:23.45] line" format ko time-sorted lines mein badalta hai (ek line par kai timestamps bhi chalte hain) */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val tags = tag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out.add(LyricLine((min * 60 + sec) * 1000 + ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** positionMs par kaun si line chal rahi hai (-1 = abhi pehli line se pehle) */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * YouTube ke title + channel se gaane ka asli (title, artist) nikalta hai, taake LRCLIB par
 * lyrics mil sakein. "Song (Official Video)" jaisa shor hata deta hai, "Artist - Title" todta hai.
 */
object LyricsQuery {
    private val noise = Regex(
        """\s*[\(\[][^\)\]]*(official|video|audio|lyric|visuali[sz]er|\bhd\b|\b4k\b|\bmv\b|m/v|full song|remaster|music)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val vevo = Regex("""(?i)\s*(vevo|official)$""")

    fun cleanTitle(t: String): String =
        noise.replace(t, "").substringBefore(" | ").replace(Regex("\\s+"), " ").trim()

    fun cleanChannel(c: String): String =
        vevo.replace(cleanArtist(c), "").trim()

    /** Koshish karne ke liye (title, artist) jodiyan, sabse mumkin pehle. Zyada se zyada 3. */
    fun candidates(title: String, channelName: String): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        val clean = cleanTitle(title)
        val isTopic = channelName.endsWith(" - Topic")
        if (!isTopic && " - " in clean) {
            val a = clean.substringBefore(" - ").trim()
            val t = clean.substringAfter(" - ").trim()
            if (a.isNotEmpty() && t.isNotEmpty()) out.add(t to a)
        }
        val channel = cleanChannel(channelName)
        if (clean.isNotEmpty() && channel.isNotEmpty()) out.add(clean to channel)
        if (title.isNotBlank() && channel.isNotEmpty()) out.add(title.trim() to channel)
        return out.filter { it.first.isNotBlank() && it.second.isNotBlank() }.take(3)
    }
}

/** Free public LRCLIB API (lrclib.net) se synced lyrics. Koi key nahi chahiye. */
class LyricsService(private val downloader: Downloader = Downloader()) {
    private val moshi = Moshi.Builder().build()
    private val headers = mapOf(
        "User-Agent" to "StreamxExtractor/1.0 (https://github.com/streamxap-create/Streamx-extractor)"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun toLyrics(m: Map<*, *>): Lyrics {
        val synced = (m["syncedLyrics"] as? String)?.let { LrcParser.parse(it) } ?: emptyList()
        return Lyrics(
            synced = synced,
            plain = m["plainLyrics"] as? String ?: "",
            instrumental = m["instrumental"] as? Boolean ?: false
        )
    }

    private fun parseMap(text: String?): Map<*, *>? =
        try { if (text == null) null else moshi.adapter(Map::class.java).fromJson(text) } catch (e: Exception) { null }

    private fun parseList(text: String?): List<*>? =
        try { if (text == null) null else moshi.adapter(List::class.java).fromJson(text) } catch (e: Exception) { null }

    fun find(title: String, artist: String, durationSec: Int = 0): Lyrics? {
        // 1) exact match
        var q = "track_name=${enc(title)}&artist_name=${enc(artist)}"
        if (durationSec > 0) q += "&duration=$durationSec"
        parseMap(downloader.getText("https://lrclib.net/api/get?$q", headers))?.let { return toLyrics(it) }

        // 2) search, duration ke sabse qareeb wala
        val list = parseList(downloader.getText("https://lrclib.net/api/search?q=${enc("$artist $title")}", headers))
            ?.mapNotNull { it as? Map<*, *> } ?: return null
        if (list.isEmpty()) return null
        val best = if (durationSec > 0) {
            list.minByOrNull { abs(((it["duration"] as? Number)?.toInt() ?: 0) - durationSec) }
        } else list.firstOrNull { !(it["syncedLyrics"] as? String).isNullOrEmpty() } ?: list.first()
        return best?.let { toLyrics(it) }
    }

    /** YouTube video (title + channel) ke liye lyrics: kai (title, artist) combinations try karta hai. */
    fun findForVideo(title: String, channelName: String, durationSec: Int = 0): Lyrics? {
        for ((t, a) in LyricsQuery.candidates(title, channelName)) {
            val hit = find(t, a, durationSec)
            if (hit != null && (hit.synced.isNotEmpty() || hit.plain.isNotBlank() || hit.instrumental)) return hit
        }
        return null
    }
}
