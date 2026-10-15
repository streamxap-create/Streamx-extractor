package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.PlexItem
import com.streamx.extractor.model.PlexLibrary
import java.net.URLEncoder

/**
 * Apne Plex Media Server ka client (aapki apni media library).
 * baseUrl jaise "http://192.168.1.10:32400", token = X-Plex-Token.
 * Plex ka free streaming (Movies & TV) content isse nahi milta; sirf aapke server ki files.
 */
class PlexClient(baseUrl: String, private val token: String, private val downloader: Downloader = Downloader()) {
    private val base = baseUrl.trim().trimEnd('/')
    private val moshi = Moshi.Builder().build()
    private val headers = mapOf(
        "Accept" to "application/json",
        "X-Plex-Token" to token,
        "X-Plex-Client-Identifier" to "streamx-extractor",
        "X-Plex-Product" to "Streamx"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun container(path: String): Map<*, *> {
        val text = downloader.getText(base + path, headers)
            ?: throw Exception("Plex server ne jawab nahi diya ($path). URL aur token check karo.")
        val root = try {
            moshi.adapter(Map::class.java).fromJson(text) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: throw Exception("Plex ka jawab samajh nahi aaya")
        return root["MediaContainer"] as? Map<*, *> ?: emptyMap<Any, Any>()
    }

    private fun withToken(path: String?): String =
        if (path.isNullOrEmpty()) "" else "$base$path" + (if (path.contains("?")) "&" else "?") + "X-Plex-Token=$token"

    private fun toItem(m: Map<*, *>): PlexItem {
        val media = (m["Media"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val part = (media?.get("Part") as? List<*>)?.firstOrNull() as? Map<*, *>
        return PlexItem(
            ratingKey = m["ratingKey"]?.toString() ?: "",
            title = m["title"] as? String ?: "",
            type = m["type"] as? String ?: "",
            year = (m["year"] as? Number)?.toInt() ?: 0,
            summary = m["summary"] as? String ?: "",
            thumbUrl = withToken(m["thumb"] as? String),
            durationMs = (m["duration"] as? Number)?.toLong() ?: 0L,
            streamUrl = withToken(part?.get("key") as? String),
            childCount = (m["childCount"] as? Number)?.toInt() ?: (m["leafCount"] as? Number)?.toInt() ?: 0,
            parentTitle = m["grandparentTitle"] as? String ?: m["parentTitle"] as? String ?: "",
            index = (m["index"] as? Number)?.toInt() ?: 0,
            videoResolution = media?.get("videoResolution") as? String ?: "",
            container = media?.get("container") as? String ?: ""
        )
    }

    private fun metadata(c: Map<*, *>): List<PlexItem> =
        (c["Metadata"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { m -> toItem(m) } } ?: emptyList()

    /** Server chal raha hai aur token sahi hai? Server ka naam deta hai. */
    fun ping(): String = container("/")["friendlyName"] as? String ?: "Plex server"

    fun libraries(): List<PlexLibrary> =
        (container("/library/sections")["Directory"] as? List<*>)?.mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            PlexLibrary(m["key"]?.toString() ?: return@mapNotNull null, m["title"] as? String ?: "", m["type"] as? String ?: "")
        } ?: emptyList()

    /** Ek library ki items (movies / shows) */
    fun items(sectionKey: String, start: Int = 0, size: Int = 200): List<PlexItem> =
        metadata(container("/library/sections/$sectionKey/all?sort=titleSort&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size"))

    /** Show -> seasons, season -> episodes */
    fun children(ratingKey: String): List<PlexItem> =
        metadata(container("/library/metadata/$ratingKey/children"))

    fun recentlyAdded(): List<PlexItem> = metadata(container("/library/recentlyAdded"))

    fun onDeck(): List<PlexItem> = metadata(container("/library/onDeck"))

    fun search(query: String): List<PlexItem> =
        metadata(container("/search?query=${enc(query)}")).filter { it.type in setOf("movie", "show", "episode") }
}
