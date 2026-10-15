package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.PlexItem
import java.net.URLEncoder

/**
 * Plex ka APNA free, ad-supported "Movies & TV" catalog — [PlexClient] se bilkul alag:
 * wo ek USER KE APNE Plex Media Server (apna URL + server token) se baat karta hai, ye
 * seedha Plex.tv se (koi apna server chahiye hi nahi, catalog free hai).
 *
 * Koi sign-in/PIN nahi chahiye — library khud ek ANONYMOUS Plex.tv account token le leti
 * hai (background mein, ek hi baar, chup-chaap), jaisa Plex ka apna web player bhi karta
 * hai jab tak user sign in nahi karta. [ensureToken] pehli call par ye le aata hai aur
 * yaad rakhta hai; dobara network call nahi hoti.
 */
class PlexDiscoverClient(private val downloader: Downloader = Downloader()) {

    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter(Map::class.java)

    @Volatile var clientIdentifier: String = java.util.UUID.randomUUID().toString()
    @Volatile private var anonToken: String? = null

    private fun baseHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "Accept" to "application/json",
            "X-Plex-Product" to "StreamX",
            "X-Plex-Client-Identifier" to clientIdentifier
        ) + extra

    /** Ek baar anonymous account token le aata hai (cached) — koi UI/user action nahi chahiye. */
    @Synchronized
    private fun ensureToken(): String {
        anonToken?.let { return it }
        val resp = downloader.postJson(
            "https://plex.tv/api/v2/users/anonymous", "{}", baseHeaders()
        )
        val root = mapAdapter.fromJson(resp) as? Map<*, *> ?: error("Unexpected /users/anonymous response")
        val token = root["authToken"] as? String
        if (token.isNullOrBlank()) error("No authToken in /users/anonymous response")
        anonToken = token
        return token
    }

    private fun discoverHeaders(): Map<String, String> = baseHeaders(mapOf("X-Plex-Token" to ensureToken()))

    /** Home hubs: "Plex Picks", trending, genre shelves — jo free catalog ki home screen par dikhta hai. */
    fun browse(): List<PlexItem> = try {
        val resp = downloader.getText(
            "https://vod.provider.plex.tv/hubs/promoted?includeMeta=1", discoverHeaders()
        )
        if (resp == null) emptyList() else parseHubItems(resp)
    } catch (e: Exception) {
        emptyList()
    }

    fun search(query: String): List<PlexItem> {
        if (query.isBlank()) return emptyList()
        return try {
            val url = "https://vod.provider.plex.tv/library/search?query=" +
                URLEncoder.encode(query, "UTF-8") + "&limit=30&searchTypes=movies,tv"
            val resp = downloader.getText(url, discoverHeaders())
            if (resp == null) emptyList() else parseSearchResults(resp)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Hub/search se mila PlexItem abhi "browsable" hai (streamUrl khali) — asli playable
     * file (aur agar DRM-protected hai to license URL) nikalne ke liye uski poori
     * metadata fetch karni padti hai. Isko tabhi call karo jab user actually play karna
     * chahta ho (list dikhate waqt nahi — har item ke liye extra request bachane ke liye).
     */
    fun resolvePlayable(item: PlexItem): PlexItem {
        if (item.streamUrl.isNotEmpty() || item.ratingKey.isBlank()) return item
        return try {
            val token = ensureToken()
            val resp = downloader.getText(
                "https://vod.provider.plex.tv/library/metadata/${item.ratingKey}",
                baseHeaders(mapOf("X-Plex-Token" to token))
            ) ?: return item
            val root = mapAdapter.fromJson(resp) as? Map<*, *> ?: return item
            val container = root["MediaContainer"] as? Map<*, *> ?: return item
            val meta = (container["Metadata"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return item
            val mediaList = meta["Media"] as? List<*> ?: return item

            fun abs(path: String, extra: Map<String, String> = emptyMap()): String {
                val sep = if (path.contains("?")) "&" else "?"
                val qs = (mapOf("X-Plex-Token" to token) + extra)
                    .entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
                return "https://vod.provider.plex.tv$path$sep$qs"
            }

            // DASH (usually Widevine-protected) preferred when available — that's what
            // this catalog mostly serves; fall back to any plain progressive/HLS part
            // that needs no DRM if one exists (some older/simpler titles have one).
            val dash = mediaList.firstOrNull { (it as? Map<*, *>)?.get("protocol") == "dash" } as? Map<*, *>
            if (dash != null) {
                val part = (dash["Part"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return item
                val partKey = part["key"] as? String ?: return item
                // Widevine license comes from a separate `license` URL on the Part when
                // the title is DRM-protected; if it's absent the DASH stream is playable
                // without DRM (isDash still has to be set so the player knows the
                // manifest is DASH rather than guessing from the URL's extension).
                val licenseKey = part["license"] as? String
                return item.copy(
                    streamUrl = abs(partKey),
                    isDash = true,
                    licenseUrl = licenseKey?.let { abs(it, mapOf("X-Plex-Drm" to "widevine")) } ?: ""
                )
            }
            val plain = mediaList.firstOrNull() as? Map<*, *> ?: return item
            val part = (plain["Part"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return item
            val partKey = part["key"] as? String ?: return item
            item.copy(streamUrl = abs(partKey))
        } catch (e: Exception) {
            item
        }
    }

    // ---- parsing ----

    private fun parseHubItems(json: String): List<PlexItem> {
        val root = mapAdapter.fromJson(json) as? Map<*, *> ?: return emptyList()
        val container = root["MediaContainer"] as? Map<*, *> ?: return emptyList()
        val hubs = container["Hub"] as? List<*> ?: return emptyList()
        val out = mutableListOf<PlexItem>()
        for (hub in hubs) {
            val h = hub as? Map<*, *> ?: continue
            val metas = h["Metadata"] as? List<*> ?: continue
            for (m in metas) (m as? Map<*, *>)?.let { toItem(it)?.let(out::add) }
        }
        return out
    }

    private fun parseSearchResults(json: String): List<PlexItem> {
        val root = mapAdapter.fromJson(json) as? Map<*, *> ?: return emptyList()
        val container = root["MediaContainer"] as? Map<*, *> ?: return emptyList()
        val out = mutableListOf<PlexItem>()
        // Plain search response ("MediaContainer":{"Metadata":[...]})
        (container["Metadata"] as? List<*>)?.forEach { (it as? Map<*, *>)?.let { m -> toItem(m)?.let(out::add) } }
        // Hub-wrapped search response ("MediaContainer":{"SearchResult":[{"Metadata":{...}}, ...]})
        (container["SearchResult"] as? List<*>)?.forEach {
            val sr = it as? Map<*, *> ?: return@forEach
            (sr["Metadata"] as? Map<*, *>)?.let { m -> toItem(m)?.let(out::add) }
        }
        return out.distinctBy { it.ratingKey }
    }

    private fun toItem(m: Map<*, *>): PlexItem? {
        val type = m["type"] as? String ?: return null
        if (type != "movie" && type != "show" && type != "episode") return null
        val ratingKey = m["ratingKey"]?.toString() ?: return null
        val title = m["title"] as? String ?: return null
        return PlexItem(
            ratingKey = ratingKey,
            title = title,
            type = type,
            year = (m["year"] as? Number)?.toInt() ?: 0,
            summary = m["summary"] as? String ?: "",
            thumbUrl = (m["thumb"] as? String)?.let { "https://vod.provider.plex.tv$it" } ?: "",
            durationMs = (m["duration"] as? Number)?.toLong() ?: 0L,
            streamUrl = "",
            childCount = (m["leafCount"] as? Number)?.toInt() ?: 0,
            parentTitle = m["parentTitle"] as? String ?: "",
            index = (m["index"] as? Number)?.toInt() ?: 0,
            videoResolution = "",
            container = ""
        )
    }
}
