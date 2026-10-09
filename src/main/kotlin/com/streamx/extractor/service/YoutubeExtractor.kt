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
        val extraContext: String = "",   // context level ka extra JSON, jaise thirdParty
        val chunkSize: Long = 0L         // is client ke URL par ek request mein itne bytes se zyada maangna 403 deta hai (0 = had nahi)
    )

    private class Attempt(val video: Video?, val report: ClientReport)

    // NewPipe (v0.28.8+) ne SABR/PO-token ka hal VISIONOS client se nikala: iske URL bina PO token ke
    // 1080p+ deti hain aur bade Range bhi accept karti hain. ANDROID_VR/ANDROID/IOS sirf ~1MiB tak ki
    // bounded Range accept karti hain (open-ended ya bada Range = 403), isliye unka chunkSize chhota hai.
    private val smallChunk = 786_432L

    private val clients = listOf(
        ClientConfig(
            name = "VISIONOS",
            clientJson = """
                "clientName": "VISIONOS",
                "clientVersion": "1.02",
                "deviceMake": "Apple",
                "deviceModel": "RealityDevice17,1",
                "osName": "visionOS",
                "osVersion": "26.5.23O471",
                "hl": "en",
                "gl": "IN"
            """,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            clientNameId = "101",
            clientVersion = "1.02"
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
            clientVersion = "1.65.10",
            chunkSize = smallChunk
        ),
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
            clientVersion = "20.10.38",
            chunkSize = smallChunk
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
            clientVersion = "20.10.4",
            chunkSize = smallChunk
        )
    )

    // YouTube /player ke liye visitorData maangta hai (NewPipe/yt-dlp bhi bhejte hain). Ek baar le kar yaad rakhta hai.
    private val visitorData: String by lazy {
        try {
            val html = downloader.getText(
                "https://www.youtube.com/",
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            ) ?: ""
            Regex("\"visitorData\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private val apiUrl = "https://www.youtube.com/youtubei/v1/player" +
        "?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w&prettyPrint=false"

    private fun attempt(c: ClientConfig, videoId: String): Attempt {
        return try {
            val vd = visitorData
            val vdJson = if (vd.isNotBlank()) ", \"visitorData\": \"$vd\"" else ""
            val payload = """
                {
                  "context": { "client": { ${c.clientJson.trim()}$vdJson }${c.extraContext} },
                  "videoId": "$videoId",
                  "contentCheckOk": true,
                  "racyCheckOk": true
                }
            """.trimIndent()

            val headers = mutableMapOf(
                "User-Agent" to c.userAgent,
                "X-YouTube-Client-Name" to c.clientNameId,
                "X-YouTube-Client-Version" to c.clientVersion,
                "Origin" to "https://www.youtube.com"
            )
            if (vd.isNotBlank()) headers["X-Goog-Visitor-Id"] = vd

            val response = downloader.postJson(apiUrl, payload, headers)
            val root = moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>

            val ps = root["playabilityStatus"] as? Map<*, *>
            val status = ps?.get("status") as? String
            val reason = ps?.get("reason") as? String

            val details = root["videoDetails"] as? Map<*, *>
            if (details == null) {
                return Attempt(null, ClientReport(c.name, false, status, reason, 0, emptyList(), null, null))
            }

            val sd = root["streamingData"] as? Map<*, *>
            val video = Video(
                id = videoId,
                title = details["title"] as? String ?: "",
                description = details["shortDescription"] as? String ?: "",
                duration = (details["lengthSeconds"] as? String)?.toIntOrNull() ?: 0,
                channelName = details["author"] as? String ?: "",
                channelId = details["channelId"] as? String ?: "",
                viewCount = (details["viewCount"] as? String)?.toLongOrNull() ?: 0L,
                thumbnails = parseThumbnails(details),
                streams = parseStreams(sd, c),
                userAgent = c.userAgent,
                client = c.name,
                hlsUrl = sd?.get("hlsManifestUrl") as? String ?: "",
                hlsUserAgent = c.userAgent
            )

            Attempt(
                video,
                ClientReport(
                    c.name, true, status, reason,
                    video.streams.size, video.streams.map { it.itag }.distinct(), null, null
                )
            )
        } catch (e: Exception) {
            Attempt(null, ClientReport(c.name, false, null, null, 0, emptyList(), null, e.message))
        }
    }

    private fun isOk(code: Int?) = code == 200 || code == 206

    /**
     * Player jaisa test: pehle 10MB ki Range (ExoPlayer ka pehla chunk), phir file ke beech se doosri Range.
     * Kuch URLs shuru ke 2 byte de dete hain par asli request par 403 dete hain.
     */
    private fun deepProbe(s: Stream, h: Map<String, String>): Int {
        val chunk = if (s.chunkSize > 0) s.chunkSize else 10L * 1024 * 1024
        val head = downloader.probeRange(s.url, h, 0, chunk - 1)
        if (!isOk(head)) return head
        val len = s.contentLength
        if (len > 4L * 1024 * 1024) {
            val mid = len / 2
            return downloader.probeRange(s.url, h, mid, minOf(mid + chunk - 1, len - 1))
        }
        return head
    }

    private class Verified(
        val video: Video,
        val muxedProbe: Int?,
        val videoProbe: Int?,
        val audioProbe: Int?
    )
    
    /**
     * Har type ki sabse achi stream ka URL sach mein khol ke dekhta hai (2 byte maang kar).
     * Jo URL 403 de uske streams hata deta hai, taake player kabhi kharab URL par na jaye.
     */
    private fun verify(c: ClientConfig, v: Video): Verified {
        val h = mapOf("User-Agent" to c.userAgent)

        val muxedTop = v.muxedStreams.maxByOrNull { it.height }
        val muxedCode = muxedTop?.let { downloader.probe(it.url, h) }

        val vids = v.videoOnlyStreams
        val vidTop = vids.filter { it.isMp4 && it.height <= 1080 }.maxByOrNull { it.height }
            ?: vids.maxByOrNull { it.height }
        val vidCode = vidTop?.let { deepProbe(it, h) }

        val audTop = v.pickAudio()
        val audCode = audTop?.let { deepProbe(it, h) }

        val adaptiveOk = isOk(vidCode) && isOk(audCode)
        val keep = v.streams.filter { s ->
            if (s.isVideo && !s.adaptive) isOk(muxedCode) else adaptiveOk
        }
        return Verified(v.copy(streams = keep), muxedCode, vidCode, audCode)
    }

    private fun reportOf(c: ClientConfig, a: Attempt, ver: Verified?): ClientReport {
        val r = a.report
        if (ver == null) return r
        return r.copy(
            probeHttpCode = ver.muxedProbe,
            videoProbe = ver.videoProbe,
            audioProbe = ver.audioProbe,
            maxHeight = ver.video.bestHeight
        )
    }

    private fun logLine(c: ClientConfig, a: Attempt, ver: Verified?): String {
        val r = a.report
        if (a.video == null) return "${c.name}: ${r.error ?: "${r.playability} ${r.reason ?: ""}".trim()}"
        if (ver == null) return "${c.name}: stream URLs nahi (${r.playability})"
        return "${c.name}: ${ver.video.bestHeight}p (mux ${ver.muxedProbe ?: "-"}, vid ${ver.videoProbe ?: "-"}, aud ${ver.audioProbe ?: "-"})"
    }

    fun getVideo(videoId: String): Video {
        val errors = mutableListOf<String>()
        val log = mutableListOf<String>()
        val good = mutableListOf<Video>()

        for (c in clients) {
            val a = attempt(c, videoId)
            val v = a.video
            if (v == null || v.streams.isEmpty()) {
                log.add(logLine(c, a, null))
                errors.add(log.last())
                continue
            }
            val ver = verify(c, v)
            log.add(logLine(c, a, ver))
            if (ver.video.streams.isEmpty()) {
                errors.add(log.last())
                continue
            }
            good.add(ver.video)
            // 1080p+ aur kam az kam 2 chalne wale clients mil gaye to baaki ko tang na karo
            if (ver.video.bestHeight >= 1080 && good.size >= 2) break
        }

        if (good.isEmpty()) {
            throw Exception("Video load fail ($videoId) -> " + errors.joinToString(" | "))
        }

        val best = good.maxByOrNull { it.bestHeight }!!
        // Doosre clients ki working muxed (360p) streams backup ke taur par jod do
        val extraMuxed = good.filter { it !== best }.flatMap { it.muxedStreams }
            .filter { e -> best.streams.none { it.itag == e.itag } }
        // Best ki adaptive fail ho to player inhe try kare
        val alts = good.filter { it !== best && it.videoOnlyStreams.isNotEmpty() && it.audioStreams.isNotEmpty() }
            .sortedByDescending { it.bestHeight }
            .map { it.copy(alternates = emptyList(), log = "") }
        val merged = best.copy(
            streams = best.streams + extraMuxed,
            log = log.joinToString("\n"),
            alternates = alts
        )
        // HLS sirf tab dekho jab adaptive se 720p se kam mili
        return if (merged.bestHeight >= 720) merged else withHls(merged, videoId)
    }

    // HLS manifest (bonus fallback) IOS client se aata hai. Manifest sach mein khulta hai ya nahi check karta hai.
    // Natija log mein likhta hai taake pata chale HLS kyun nahi mila.
    private fun withHls(v: Video, videoId: String): Video {
        if (v.hlsUrl.isNotEmpty()) return v
        val ios = clients.firstOrNull { it.name == "IOS" } ?: return v
        fun note(t: String) = v.copy(log = (v.log + "\nHLS: " + t).trim())
        return try {
            val a = attempt(ios, videoId)
            val url = a.video?.hlsUrl.orEmpty()
            if (url.isEmpty()) {
                return note("IOS ne manifest nahi diya (${a.report.playability ?: a.report.error ?: "?"})")
            }
            val text = downloader.getText(url, mapOf("User-Agent" to ios.userAgent))
            if (text == null || !text.contains("#EXTM3U")) return note("manifest khula nahi")
            val maxH = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
            v.copy(
                hlsUrl = url, hlsUserAgent = ios.userAgent, hlsMaxHeight = maxH,
                log = (v.log + "\nHLS: ok ${maxH}p").trim()
            )
        } catch (e: Exception) {
            note("error ${e.message}")
        }
    }

    fun diagnose(videoId: String): List<ClientReport> =
        clients.map { c ->
            val a = attempt(c, videoId)
            val v = a.video
            if (v == null || v.streams.isEmpty()) a.report else reportOf(c, a, verify(c, v))
        }

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

    private fun parseStreams(data: Map<*, *>?, c: ClientConfig): List<Stream> {
        if (data == null) return emptyList()

        val result = mutableListOf<Stream>()
        val formats = (data["formats"] as? List<*>) ?: emptyList<Any>()
        val adaptive = (data["adaptiveFormats"] as? List<*>) ?: emptyList<Any>()
        val codecRegex = Regex("codecs=\"([^\"]+)\"")

        fun add(item: Any?, isAdaptive: Boolean) {
            val f = item as? Map<*, *> ?: return
            val mime = f["mimeType"] as? String ?: return
            val url = f["url"] as? String ?: return   // signatureCipher wali streams skip

            result.add(
                Stream(
                    url = url,
                    itag = (f["itag"] as? Number)?.toInt() ?: 0,
                    mimeType = mime,
                    quality = f["qualityLabel"] as? String ?: (f["audioQuality"] as? String ?: "unknown"),
                    bitrate = (f["bitrate"] as? Number)?.toInt() ?: 0,
                    isVideo = mime.startsWith("video"),
                    isAudio = mime.startsWith("audio"),
                    adaptive = isAdaptive,
                    width = (f["width"] as? Number)?.toInt() ?: 0,
                    height = (f["height"] as? Number)?.toInt() ?: 0,
                    fps = (f["fps"] as? Number)?.toInt() ?: 0,
                    contentLength = (f["contentLength"] as? String)?.toLongOrNull() ?: 0L,
                    codecs = codecRegex.find(mime)?.groupValues?.get(1)?.substringBefore(',')?.trim() ?: "",
                    client = c.name,
                    userAgent = c.userAgent,
                    chunkSize = c.chunkSize
                )
            )
        }

        formats.forEach { add(it, false) }
        adaptive.forEach { add(it, true) }
        return result
    }
}
