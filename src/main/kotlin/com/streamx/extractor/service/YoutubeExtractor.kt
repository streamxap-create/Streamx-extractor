package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.Stream
import com.streamx.extractor.model.Thumbnail
import com.streamx.extractor.model.SearchPage
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem

class YoutubeExtractor(
    private val downloader: Downloader = Downloader()
) {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private data class ClientConfig(
        val name: String,
        val clientJson: String,
        val userAgent: String,
        val clientNameId: String,
        val clientVersion: String,
        val extraContext: String = ""   // context level ka extra JSON, jaise thirdParty
    )

    private class Attempt(val video: Video?, val report: ClientReport)

    // Real device test: ANDROID aur IOS ke URL chalte hain (206), ANDROID_VR ka 403 aata hai -> last mein
    private val clients = listOf(
        ClientConfig(
            name = "ANDROID",
            clientJson = """
                "clientName": "ANDROID",
                "clientVersion": "20.10.38",
                "androidSdkVersion": 30,
                "osName": "Android",
                "osVersion": "11",
                "hl": "en",
                "gl": "IN"
            """,
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            clientNameId = "3",
            clientVersion = "20.10.38"
        ),
        ClientConfig(
            name = "IOS",
            clientJson = """
                "clientName": "IOS",
                "clientVersion": "20.10.4",
                "deviceMake": "Apple",
                "deviceModel": "iPhone16,2",
                "osName": "iPhone",
                "osVersion": "18.3.2.22D82",
                "hl": "en",
                "gl": "IN"
            """,
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            clientNameId = "5",
            clientVersion = "20.10.4"
        ),
        ClientConfig(
            name = "ANDROID_VR",
            clientJson = """
                "clientName": "ANDROID_VR",
                "clientVersion": "1.65.10",
                "deviceMake": "Oculus",
                "deviceModel": "Quest 3",
                "androidSdkVersion": 32,
                "osName": "Android",
                "osVersion": "12L",
                "hl": "en",
                "gl": "IN"
            """,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            clientNameId = "28",
            clientVersion = "1.65.10"
        )
    )

    private val apiUrl = "https://www.youtube.com/youtubei/v1/player" +
        "?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w&prettyPrint=false"

    private fun attempt(c: ClientConfig, videoId: String, probe: Boolean): Attempt {
        return try {
            val payload = """
                {
                  "context": { "client": { ${c.clientJson.trim()} }${c.extraContext} },
                  "videoId": "$videoId",
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
            """.trimIndent()

            val headers = mapOf(
                "User-Agent" to c.userAgent,
                "X-YouTube-Client-Name" to c.clientNameId,
                "X-YouTube-Client-Version" to c.clientVersion,
                "Origin" to "https://www.youtube.com"
            )

            val response = downloader.postJson(apiUrl, payload, headers)
            val root = moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>

            val ps = root["playabilityStatus"] as? Map<*, *>
            val status = ps?.get("status") as? String
            val reason = ps?.get("reason") as? String

            val details = root["videoDetails"] as? Map<*, *>
            if (details == null) {
                return Attempt(null, ClientReport(c.name, false, status, reason, 0, emptyList(), null, null))
            }

            val video = Video(
                id = videoId,
                title = details["title"] as? String ?: "",
                description = details["shortDescription"] as? String ?: "",
                duration = (details["lengthSeconds"] as? String)?.toIntOrNull() ?: 0,
                channelName = details["author"] as? String ?: "",
                channelId = details["channelId"] as? String ?: "",
                viewCount = (details["viewCount"] as? String)?.toLongOrNull() ?: 0L,
                thumbnails = parseThumbnails(details),
                streams = parseStreams(root["streamingData"] as? Map<*, *>),
                userAgent = c.userAgent,
                client = c.name,
                hlsUrl = (root["streamingData"] as? Map<*, *>)?.get("hlsManifestUrl") as? String ?: "",
                hlsUserAgent = c.userAgent
            )

            val probeCode = if (probe && video.streams.isNotEmpty())
                downloader.probe(video.streams.first().url, mapOf("User-Agent" to c.userAgent))
            else null

            Attempt(
                video,
                ClientReport(
                    c.name, true, status, reason,
                    video.streams.size, video.streams.map { it.itag }.distinct(), probeCode, null
                )
            )
        } catch (e: Exception) {
            Attempt(null, ClientReport(c.name, false, null, null, 0, emptyList(), null, e.message))
        }
    }

    fun getVideo(videoId: String): Video {
        val errors = mutableListOf<String>()
        var fallback: Video? = null

        for (c in clients) {
            val a = attempt(c, videoId, probe = true)
            val v = a.video
            if (v == null) {
                val r = a.report
                errors.add("${c.name}: ${r.error ?: "${r.playability} ${r.reason ?: ""}".trim()}")
                continue
            }
            if (v.streams.isEmpty()) {
                if (fallback == null) fallback = v
                errors.add("${c.name}: stream URLs nahi (${a.report.playability})")
                continue
            }
            val code = a.report.probeHttpCode
            if (code == 200 || code == 206) return withHls(v, videoId)   // URL sach mein chalta hai
            if (fallback == null) fallback = v
            errors.add("${c.name}: URL block (HTTP $code)")
        }
        return fallback?.let { withHls(it, videoId) }
            ?: throw Exception("Video load fail ($videoId) -> " + errors.joinToString(" | "))
    }

    // HLS manifest (adaptive 1080p) IOS client se aata hai; ANDROID wale video mein jod do.
    // Manifest sach mein khulta hai ya nahi check karta hai aur sabse badi quality nikalta hai.
    private fun withHls(v: Video, videoId: String): Video {
        if (v.hlsUrl.isNotEmpty()) return v
        val ios = clients.firstOrNull { it.name == "IOS" } ?: return v
        return try {
            val url = attempt(ios, videoId, probe = false).video?.hlsUrl.orEmpty()
            if (url.isEmpty()) return v
            val text = downloader.getText(url, mapOf("User-Agent" to ios.userAgent))
            if (text == null || !text.contains("#EXTM3U")) return v
            val maxH = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
            v.copy(hlsUrl = url, hlsUserAgent = ios.userAgent, hlsMaxHeight = maxH)
        } catch (e: Exception) {
            v
        }
    }

    fun diagnose(videoId: String): List<ClientReport> =
        clients.map { attempt(it, videoId, probe = true).report }

    // ---------- Search / Home feed ----------
    private val webClientJson = mapOf(
        "clientName" to "WEB",
        "clientVersion" to "2.20250101.00.00",
        "hl" to "en",
        "gl" to "IN"
    )

    fun search(query: String): List<VideoItem> = searchPage(query, null).items

    fun searchPage(query: String, continuation: String?): SearchPage {
        val ctx = mapOf("client" to webClientJson)
        val body: Map<String, Any> =
            if (continuation == null) mapOf("context" to ctx, "query" to query)
            else mapOf("context" to ctx, "continuation" to continuation)
        val payload = moshi.adapter(Map::class.java).toJson(body)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "X-YouTube-Client-Name" to "1",
            "X-YouTube-Client-Version" to "2.20250101.00.00",
            "Origin" to "https://www.youtube.com"
        )
        val url = "https://www.youtube.com/youtubei/v1/search" +
            "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

        val response = downloader.postJson(url, payload, headers)
        val root = moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>

        val renderers = mutableListOf<Map<*, *>>()
        collectVideoRenderers(root, renderers)

        val seen = HashSet<String>()
        val items = renderers.mapNotNull { r ->
            val id = r["videoId"] as? String ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val owner = r["ownerText"] ?: r["longBylineText"]
            VideoItem(
                id = id,
                title = textOf(r["title"]),
                channelName = textOf(owner),
                duration = textOf(r["lengthText"]),
                views = textOf(r["viewCountText"]).ifEmpty { textOf(r["shortViewCountText"]) },
                published = textOf(r["publishedTimeText"]),
                thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                channelId = channelIdOf(owner),
                channelThumbnail = avatarOf(r)
            )
        }
        return SearchPage(items, findContinuationToken(root))
    }

    private fun channelIdOf(owner: Any?): String {
        val run = ((owner as? Map<*, *>)?.get("runs") as? List<*>)?.firstOrNull() as? Map<*, *>
        val nav = run?.get("navigationEndpoint") as? Map<*, *>
        val browse = nav?.get("browseEndpoint") as? Map<*, *>
        return browse?.get("browseId") as? String ?: ""
    }

    private fun avatarOf(r: Map<*, *>): String {
        val a = (r["channelThumbnailSupportedRenderers"] as? Map<*, *>)
            ?.get("channelThumbnailWithLinkRenderer") as? Map<*, *>
        val thumbs = (a?.get("thumbnail") as? Map<*, *>)?.get("thumbnails") as? List<*>
        val url = (thumbs?.firstOrNull() as? Map<*, *>)?.get("url") as? String ?: return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    // Agla page ka token (infinite scroll ke liye)
    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is Map<*, *> -> {
                val cir = node["continuationItemRenderer"] as? Map<*, *>
                if (cir != null) {
                    val cmd = (cir["continuationEndpoint"] as? Map<*, *>)?.get("continuationCommand") as? Map<*, *>
                    (cmd?.get("token") as? String)?.let { return it }
                }
                for (v in node.values) findContinuationToken(v)?.let { return it }
            }
            is List<*> -> for (x in node) findContinuationToken(x)?.let { return it }
        }
        return null
    }

    // JSON mein kahin bhi "videoRenderer" mile to utha lo (layout badle to bhi chalega)
    private fun collectVideoRenderers(node: Any?, out: MutableList<Map<*, *>>) {
        when (node) {
            is Map<*, *> -> {
                (node["videoRenderer"] as? Map<*, *>)?.let { out.add(it) }
                node.values.forEach { collectVideoRenderers(it, out) }
            }
            is List<*> -> node.forEach { collectVideoRenderers(it, out) }
        }
    }

    private fun textOf(node: Any?): String {
        val m = node as? Map<*, *> ?: return ""
        (m["simpleText"] as? String)?.let { return it }
        val runs = m["runs"] as? List<*> ?: return ""
        return runs.joinToString("") { (it as? Map<*, *>)?.get("text") as? String ?: "" }
    }

    private fun parseThumbnails(details: Map<*, *>): List<Thumbnail> {
        val thumb = details["thumbnail"] as? Map<*, *> ?: return emptyList()
        val list = thumb["thumbnails"] as? List<*> ?: return emptyList()

        return list.mapNotNull { item ->
            val t = item as? Map<*, *> ?: return@mapNotNull null
            Thumbnail(
                url = t["url"] as? String ?: "",
                width = (t["width"] as? Number)?.toInt() ?: 0,
                height = (t["height"] as? Number)?.toInt() ?: 0
            )
        }
    }

    private fun parseStreams(data: Map<*, *>?): List<Stream> {
        if (data == null) return emptyList()

        val result = mutableListOf<Stream>()
        val formats = (data["formats"] as? List<*>) ?: emptyList<Any>()
        val adaptive = (data["adaptiveFormats"] as? List<*>) ?: emptyList<Any>()

        (formats + adaptive).forEach { item ->
            val f = item as? Map<*, *> ?: return@forEach
            val mime = f["mimeType"] as? String ?: return@forEach
            val url = f["url"] as? String ?: return@forEach

            result.add(
                Stream(
                    url = url,
                    itag = (f["itag"] as? Number)?.toInt() ?: 0,
                    mimeType = mime,
                    quality = f["qualityLabel"] as? String ?: "unknown",
                    bitrate = (f["bitrate"] as? Number)?.toInt() ?: 0,
                    isVideo = mime.startsWith("video"),
                    isAudio = mime.startsWith("audio")
                )
            )
        }
        return result
    }
                           }
                           
