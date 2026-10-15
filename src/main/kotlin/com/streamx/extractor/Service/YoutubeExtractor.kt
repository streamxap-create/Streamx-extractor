package com.streamx.extractor.service

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.streamx.extractor.ExtractorConfig
import com.streamx.extractor.client.Downloader
import com.streamx.extractor.model.ChannelInfo
import com.streamx.extractor.model.PlaylistSummary
import com.streamx.extractor.model.Comment
import com.streamx.extractor.model.CommentPage
import com.streamx.extractor.model.ItemPage
import com.streamx.extractor.model.MusicFilter
import com.streamx.extractor.model.MusicItem
import com.streamx.extractor.model.MusicType
import com.streamx.extractor.model.SearchFilter
import com.streamx.extractor.model.Subtitle
import com.streamx.extractor.model.audioLabel
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.streamx.extractor.model.ClientReport
import com.streamx.extractor.model.Stream
import com.streamx.extractor.model.Thumbnail
import com.streamx.extractor.model.SearchPage
import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem

class YoutubeExtractor(
    private val downloader: Downloader = Downloader()
) {

    // Clients aur probes ek saath chalte hain (pehle ek ek karke chalte the = dheema)
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "streamx-io").apply { isDaemon = true }
    }

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

    // YouTube /player ke liye visitorData maangta hai (NewPipe/yt-dlp bhi bhejte hain).
    // Same homepage fetch se ExtractorConfig.webClientVersion/musicClientVersion ko bhi
    // LIVE kar deta hai — pehle ye dono ExtractorConfig mein ek fixed date (2025-01-01)
    // par hardcoded the; YouTube kaafi purani clientVersion wali request par khali/invalid
    // response de deta hai, jisse search/playlist/channel/related/radio sab "data scrape
    // nahi hoti" lagta tha, jab ki player/getVideo() alag se kaam karta reh sakta tha
    // (wo client-specific ANDROID/IOS/etc. version string use karta hai, is WEB version
    // par depend nahi karta — isliye yahi ek jagah tooti dikhti thi). Ek hi fetch, result
    // cache (by lazy) — baar baar homepage nahi mangwati.
    private val visitorData: String by lazy {
        try {
            val html = downloader.getText(
                "https://www.youtube.com/",
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            ) ?: ""
            Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?.let { cv ->
                    ExtractorConfig.webClientVersion = cv
                    // WEB_REMIX (music.youtube.com) ka version WEB jaisa hi date-stamp
                    // share karta hai ("1.<date>.01.00" vs web ka "2.<date>.00.00") — isi
                    // date se derive karne se music search bhi ek hi fetch se sync rehta
                    // hai, alag se music.youtube.com fetch karne ki zaroorat nahi.
                    val date = cv.substringAfter('.').substringBefore('.')
                    if (date.length == 8 && date.all { it.isDigit() }) {
                        ExtractorConfig.musicClientVersion = "1.$date.01.00"
                    }
                }
            Regex("\"visitorData\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private val apiUrl: String
        get() = "https://www.youtube.com/youtubei/v1/player?key=${ExtractorConfig.playerApiKey}&prettyPrint=false"

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
            val micro = (root["microformat"] as? Map<*, *>)?.get("playerMicroformatRenderer") as? Map<*, *>
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
                hlsUserAgent = c.userAgent,
                keywords = (details["keywords"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                isLive = details["isLive"] as? Boolean ?: false,
                category = micro?.get("category") as? String ?: "",
                publishDate = micro?.get("publishDate") as? String ?: "",
                likeCount = (micro?.get("likeCount") as? String)?.toLongOrNull() ?: -1L,
                subtitles = parseSubtitles(root)
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
    
    private fun codecFamily(s: Stream) = when {
        s.isAvc -> "avc"
        s.isVp9 -> "vp9"
        s.isAv1 -> "av1"
        else -> "other"
    }

    /**
     * Har codec family (avc/vp9/av1) mein sabse ooncha height pehle test hota hai; jo pehla chal jaye
     * uske neeche wale sab theek maane jate hain. Families, muxed aur audio saath saath test hote hain.
     */
    private fun verify(c: ClientConfig, v: Video): Verified {
        val h = mapOf("User-Agent" to c.userAgent)

        val muxedTask = pool.submit(Callable<Int?> {
            v.muxedStreams.maxByOrNull { it.height }?.let { downloader.probe(it.url, h) }
        })
        val audTask = pool.submit(Callable<Int?> {
            v.pickAudio()?.let { deepProbe(it, h) }
        })

        val okUrls = ConcurrentHashMap.newKeySet<String>()
        val okCodes = ConcurrentHashMap<String, Int>()
        val badCodes = ConcurrentHashMap<String, Int>()
        val famTasks = v.videoOnlyStreams.groupBy { codecFamily(it) }.map { (fam, list) ->
            pool.submit(Runnable {
                val byHeight = list.groupBy { it.height }.toSortedMap(compareByDescending<Int> { it })
                for ((height, g) in byHeight) {
                    val probe = g.maxByOrNull { it.bitrate } ?: g.first()
                    val code = deepProbe(probe, h)
                    if (isOk(code)) {
                        list.filter { it.height <= height }.forEach { okUrls.add(it.url) }
                        okCodes[fam] = code
                        break
                    } else {
                        badCodes[fam] = code
                    }
                }
            })
        }
        famTasks.forEach { it.get() }
        val muxedCode: Int? = muxedTask.get()
        val audCode: Int? = audTask.get()
        val vidCode: Int? = okCodes.values.firstOrNull() ?: badCodes.values.firstOrNull()

        val audioOk = isOk(audCode)
        val adaptiveOk = audioOk && okUrls.isNotEmpty()
        val keep = v.streams.filter { s ->
            when {
                s.isVideo && s.adaptive -> audioOk && s.url in okUrls
                s.isVideo -> isOk(muxedCode)
                else -> adaptiveOk
            }
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

    private class Outcome(val index: Int, val line: String, val video: Video?)

    private fun runClient(index: Int, c: ClientConfig, videoId: String, audioOnly: Boolean): Outcome {
        return try {
            val a = attempt(c, videoId)
            val v = a.video
            if (v == null || v.streams.isEmpty()) return Outcome(index, logLine(c, a, null), null)
            if (audioOnly) {
                val au = v.pickAudio()
                val code = au?.let { deepProbe(it, mapOf("User-Agent" to c.userAgent)) }
                val line = "${c.name}: audio ${au?.let { audioLabel(it) } ?: "-"} (aud ${code ?: "-"})"
                return if (isOk(code)) Outcome(index, line, v.copy(streams = v.audioStreams, log = line))
                else Outcome(index, line, null)
            }
            val ver = verify(c, v)
            val line = logLine(c, a, ver)
            Outcome(index, line, if (ver.video.streams.isEmpty()) null else ver.video)
        } catch (e: Exception) {
            Outcome(index, "${c.name}: error ${e.message}", null)
        }
    }

    /**
     * Saare clients ek saath. Jaise hi achi quality + 2 clients mil jayein (ya pehle ke baad thodi mohlat)
     * baaki ka intezar nahi hota.
     */
    fun getVideo(videoId: String): Video {
        val t0 = System.currentTimeMillis()
        val ecs = ExecutorCompletionService<Outcome>(pool)
        val futures = clients.mapIndexed { i, c -> ecs.submit(Callable { runClient(i, c, videoId, false) }) }
        val results = mutableListOf<Outcome>()
        var firstGoodAt = 0L
        var pending = clients.size
        try {
            while (pending > 0) {
                val now = System.currentTimeMillis()
                var wait = t0 + 25_000 - now
                if (firstGoodAt > 0) {
                    val top = results.mapNotNull { it.video }.maxOfOrNull { it.bestHeight } ?: 0
                    wait = minOf(wait, firstGoodAt + (if (top >= 1080) 2_000 else 5_000) - now)
                }
                if (wait <= 0) break
                val f = ecs.poll(wait, TimeUnit.MILLISECONDS) ?: break
                pending--
                val o = try { f.get() } catch (e: Exception) { continue }
                results.add(o)
                if (o.video != null && firstGoodAt == 0L) firstGoodAt = System.currentTimeMillis()
                val g = results.mapNotNull { it.video }
                val top = g.maxOfOrNull { it.bestHeight } ?: 0
                if (top >= 2160 || (g.size >= 2 && top >= 1080)) break
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        results.sortBy { it.index }
        val good = results.mapNotNull { it.video }
        val log = results.map { it.line }
        if (good.isEmpty()) {
            throw Exception("Video load fail ($videoId) -> " + log.joinToString(" | "))
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
            log = (log + "total ${System.currentTimeMillis() - t0}ms").joinToString("\n"),
            subtitles = good.flatMap { it.subtitles }.distinctBy { it.languageCode + it.auto },
            alternates = alts
        )
        // HLS sirf tab dekho jab adaptive se 720p se kam mili
        return if (merged.bestHeight >= 720) merged else withHls(merged, videoId)
    }

    /**
     * Music ke liye tez raasta: sirf audio verify hoti hai (video ke 20 probes nahi).
     * Jo client pehle chalti audio de, wahi jeet jata hai.
     */
    fun getAudio(videoId: String): Video {
        val ecs = ExecutorCompletionService<Outcome>(pool)
        val futures = clients.mapIndexed { i, c -> ecs.submit(Callable { runClient(i, c, videoId, true) }) }
        val lines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 20_000
        var pending = clients.size
        try {
            while (pending > 0) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) break
                val f = ecs.poll(wait, TimeUnit.MILLISECONDS) ?: break
                pending--
                val o = try { f.get() } catch (e: Exception) { continue }
                val v = o.video
                if (v != null) return v
                lines.add(o.line)
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        throw Exception("Audio load fail ($videoId) -> " + lines.joinToString(" | "))
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
    private val webClientJson: Map<String, String>
        get() {
            // Search/playlist/channel/related/radio never touched `visitorData` before
            // (only getVideo()/getAudio() did), so the live-version fetch above never
            // ran for them — they kept using ExtractorConfig's stale default forever.
            // Just reading `visitorData` here forces that one-time fetch (and is free
            // after the first call, since it's cached by `lazy`).
            visitorData
            return mapOf(
                "clientName" to "WEB",
                "clientVersion" to ExtractorConfig.webClientVersion,
                "hl" to ExtractorConfig.hl,
                "gl" to ExtractorConfig.gl
            )
        }

    fun search(query: String): List<VideoItem> = searchPage(query, null).items

    fun searchPage(query: String, continuation: String?, filter: SearchFilter = SearchFilter.ALL): SearchPage {
        val ctx = mapOf("client" to webClientJson)
        val body: Map<String, Any> =
            if (continuation == null) {
                val m = mutableMapOf<String, Any>("context" to ctx, "query" to query)
                filter.params?.let { m["params"] = it }
                m
            }
            else mapOf("context" to ctx, "continuation" to continuation)
        val payload = moshi.adapter(Map::class.java).toJson(body)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "X-YouTube-Client-Name" to "1",
            "X-YouTube-Client-Version" to ExtractorConfig.webClientVersion,
            "Origin" to "https://www.youtube.com"
        )
        val url = "https://www.youtube.com/youtubei/v1/search?key=${ExtractorConfig.searchApiKey}&prettyPrint=false"

        val response = downloader.postJson(url, payload, headers)
        val root = moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>

        val items = itemsOf(root, "videoRenderer")
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

    private fun collectRenderers(node: Any?, key: String, out: MutableList<Map<*, *>>) {
        when (node) {
            is Map<*, *> -> {
                (node[key] as? Map<*, *>)?.let { out.add(it) }
                node.values.forEach { collectRenderers(it, key, out) }
            }
            is List<*> -> node.forEach { collectRenderers(it, key, out) }
        }
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
                    audioSampleRate = (f["audioSampleRate"] as? String)?.toIntOrNull() ?: 0,
                    audioChannels = (f["audioChannels"] as? Number)?.toInt() ?: 0,
                    averageBitrate = (f["averageBitrate"] as? Number)?.toInt() ?: 0,
                    loudnessDb = (f["loudnessDb"] as? Number)?.toDouble(),
                    isHdr = ((f["colorInfo"] as? Map<*, *>)?.get("transferCharacteristics") as? String)
                        ?.let { it.contains("SMPTEST2084") || it.contains("ARIB_STD_B67") } ?: false,
                    durationMs = (f["approxDurationMs"] as? String)?.toLongOrNull() ?: 0L,
                    audioTrackName = (f["audioTrack"] as? Map<*, *>)?.get("displayName") as? String ?: "",
                    audioIsDefault = (f["audioTrack"] as? Map<*, *>)?.get("audioIsDefault") as? Boolean ?: true,
                    chunkSize = c.chunkSize
                )
            )
        }

        formats.forEach { add(it, false) }
        adaptive.forEach { add(it, true) }
        return result
    }

    // ---------- Playlist / Channel / Related ----------
    private fun postWeb(endpoint: String, body: Map<String, Any>): Map<*, *> {
        val payload = moshi.adapter(Map::class.java).toJson(body + ("context" to mapOf("client" to webClientJson)))
        val headers = mapOf(
            "User-Agent" to ExtractorConfig.webUserAgent,
            "X-YouTube-Client-Name" to "1",
            "X-YouTube-Client-Version" to ExtractorConfig.webClientVersion,
            "Origin" to "https://www.youtube.com"
        )
        val url = "https://www.youtube.com/youtubei/v1/$endpoint?key=${ExtractorConfig.searchApiKey}&prettyPrint=false"
        val response = downloader.postJson(url, payload, headers)
        return moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>
    }

    private fun toItem(r: Map<*, *>, fallbackChannel: String = ""): VideoItem? {
        val id = r["videoId"] as? String ?: return null
        val owner = r["ownerText"] ?: r["longBylineText"] ?: r["shortBylineText"]
        return VideoItem(
            id = id,
            title = textOf(r["title"]),
            channelName = textOf(owner).ifEmpty { fallbackChannel },
            duration = textOf(r["lengthText"]),
            views = textOf(r["viewCountText"]).ifEmpty { textOf(r["shortViewCountText"]) },
            published = textOf(r["publishedTimeText"]),
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            channelId = channelIdOf(owner),
            channelThumbnail = avatarOf(r)
        )
    }

    private fun collectMulti(node: Any?, keys: Set<String>, out: MutableList<Pair<String, Map<*, *>>>) {
        when (node) {
            is Map<*, *> -> {
                for (k in keys) (node[k] as? Map<*, *>)?.let { out.add(k to it) }
                node.values.forEach { collectMulti(it, keys, out) }
            }
            is List<*> -> node.forEach { collectMulti(it, keys, out) }
        }
    }

    /**
     * YouTube ab naye "lockupViewModel" format mein bhi videos deta hai (search, related, channel).
     * Purana renderer aur naya lockup dono parse hote hain.
     */
    private fun lockupToItem(m: Map<*, *>, fallbackChannel: String): VideoItem? {
        val type = m["contentType"] as? String
        if (type != null && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = m["contentId"] as? String ?: return null
        if (id.length != 11) return null
        val lm = (m["metadata"] as? Map<*, *>)?.get("lockupMetadataViewModel") as? Map<*, *> ?: return null
        val title = (lm["title"] as? Map<*, *>)?.get("content") as? String ?: return null

        val rows = ((lm["metadata"] as? Map<*, *>)?.get("contentMetadataViewModel") as? Map<*, *>)
            ?.get("metadataRows") as? List<*> ?: emptyList<Any>()
        val parts = mutableListOf<Pair<String, Any?>>()
        for (row in rows) {
            ((row as? Map<*, *>)?.get("metadataParts") as? List<*>)?.forEach { p ->
                val txt = ((p as? Map<*, *>)?.get("text") as? Map<*, *>)?.get("content") as? String
                if (txt != null) parts.add(txt to p)
            }
        }
        val viewRe = Regex("view|watching", RegexOption.IGNORE_CASE)
        val agoRe = Regex("\\bago\\b|streamed|premiere", RegexOption.IGNORE_CASE)
        val views = parts.firstOrNull { viewRe.containsMatchIn(it.first) }?.first ?: ""
        val published = parts.firstOrNull { agoRe.containsMatchIn(it.first) }?.first ?: ""
        val channelPart = parts.firstOrNull { !viewRe.containsMatchIn(it.first) && !agoRe.containsMatchIn(it.first) }
        val channelId = channelPart?.second?.let { findFirst(it, "browseId") as? String } ?: ""

        val avatarSources = findFirst(lm["image"], "sources") as? List<*>
        var avatar = (avatarSources?.firstOrNull() as? Map<*, *>)?.get("url") as? String ?: ""
        if (avatar.startsWith("//")) avatar = "https:$avatar"
        val duration = (findFirst(m["contentImage"], "thumbnailBadgeViewModel") as? Map<*, *>)?.get("text") as? String ?: ""

        return VideoItem(
            id = id,
            title = title,
            channelName = channelPart?.first ?: fallbackChannel,
            duration = duration,
            views = views,
            published = published,
            thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            channelId = channelId,
            channelThumbnail = if (channelPart != null) avatar else ""
        )
    }

    private fun itemsOf(
        root: Map<*, *>,
        rendererKey: String,
        fallbackChannel: String = "",
        lockups: Boolean = true
    ): List<VideoItem> {
        val found = mutableListOf<Pair<String, Map<*, *>>>()
        collectMulti(root, if (lockups) setOf(rendererKey, "lockupViewModel") else setOf(rendererKey), found)
        val seen = HashSet<String>()
        return found.mapNotNull { (k, r) ->
            if (k == "lockupViewModel") lockupToItem(r, fallbackChannel) else toItem(r, fallbackChannel)
        }.filter { seen.add(it.id) }
    }

    /** Playlist ke videos. Agla page: nextToken ko continuation mein do. */
    fun getPlaylist(playlistId: String, continuation: String? = null): ItemPage {
        val body: Map<String, Any> =
            if (continuation == null) mapOf("browseId" to "VL" + playlistId.removePrefix("VL"))
            else mapOf("continuation" to continuation)
        val root = postWeb("browse", body)
        val title = ((root["metadata"] as? Map<*, *>)?.get("playlistMetadataRenderer") as? Map<*, *>)
            ?.get("title") as? String ?: ""
        return ItemPage(title, itemsOf(root, "playlistVideoRenderer", lockups = false), findContinuationToken(root))
    }

    /** Channel ke videos (Videos tab). channelId "UC..." hona chahiye. */
    fun getChannelVideos(channelId: String, continuation: String? = null): ItemPage {
        val body: Map<String, Any> =
            if (continuation == null) mapOf("browseId" to channelId, "params" to "EgZ2aWRlb3PyBgQKAjoA")
            else mapOf("continuation" to continuation)
        val root = postWeb("browse", body)
        val name = ((root["metadata"] as? Map<*, *>)?.get("channelMetadataRenderer") as? Map<*, *>)
            ?.get("title") as? String ?: ""
        return ItemPage(name, itemsOf(root, "videoRenderer", name), findContinuationToken(root))
    }

    /** Is video ke saath dikhne wali related videos */
    fun getRelated(videoId: String): List<VideoItem> {
        val root = postWeb("next", mapOf("videoId" to videoId))
        return itemsOf(root, "compactVideoRenderer")
    }

    // ---------- Comments ----------
    // YouTube ne comments ka format badal diya hai: purana "commentRenderer" chhod kar ab
    // "frameworkUpdates.entityBatchUpdate.mutations[].payload.commentEntityPayload" use
    // karta hai. Fetch wahi /next endpoint hai jo getRelated() bhi use karta hai, bas
    // continuation token "comments" section ke liye dhoondna padta hai (generic
    // findContinuationToken() galat token utha sakta hai — related-videos "load more" bhi
    // continuationItemRenderer hi hai — isliye yahan specifically top-level "results.results"
    // ke andar comments-section ka hi token dhoondte hain).

    /** Video ke comments ka pehla page (top comments). Agla page getCommentsPage(nextToken) se. */
    fun getComments(videoId: String): CommentPage {
        val root = postWeb("next", mapOf("videoId" to videoId))
        val token = findCommentsEntryToken(root) ?: return CommentPage(emptyList(), null)
        return getCommentsPage(token)
    }

    /** Agla comments page — getComments()/getCommentsPage() se mile nextToken se. */
    fun getCommentsPage(continuationToken: String): CommentPage {
        val root = postWeb("next", mapOf("continuation" to continuationToken))
        return CommentPage(parseCommentsFromMutations(root), findNextCommentsContinuation(root))
    }

    private fun findCommentsEntryToken(root: Map<*, *>): String? {
        val twoCol = (root["contents"] as? Map<*, *>)?.get("twoColumnWatchNextResults") as? Map<*, *>
        val results = (twoCol?.get("results") as? Map<*, *>)?.get("results") as? Map<*, *>
        val contents = results?.get("contents") as? List<*> ?: return null
        for (item in contents) {
            val sc = (item as? Map<*, *>)?.get("itemSectionRenderer")?.let { it as? Map<*, *> }
                ?.get("contents") as? List<*> ?: continue
            for (c in sc) {
                val cir = (c as? Map<*, *>)?.get("continuationItemRenderer") as? Map<*, *> ?: continue
                val token = ((cir["continuationEndpoint"] as? Map<*, *>)?.get("continuationCommand") as? Map<*, *>)
                    ?.get("token") as? String
                if (token != null) return token
            }
        }
        return null
    }

    private fun findNextCommentsContinuation(root: Map<*, *>): String? {
        val endpoints = root["onResponseReceivedEndpoints"] as? List<*> ?: return null
        for (ep in endpoints) {
            val e = ep as? Map<*, *> ?: continue
            val items = (e["reloadContinuationItemsCommand"] as? Map<*, *>)?.get("continuationItems") as? List<*>
                ?: (e["appendContinuationItemsAction"] as? Map<*, *>)?.get("continuationItems") as? List<*>
                ?: continue
            for (it in items) {
                val cir = (it as? Map<*, *>)?.get("continuationItemRenderer") as? Map<*, *> ?: continue
                val direct = ((cir["continuationEndpoint"] as? Map<*, *>)?.get("continuationCommand") as? Map<*, *>)
                    ?.get("token") as? String
                val viaButton = (((cir["button"] as? Map<*, *>)?.get("buttonRenderer") as? Map<*, *>)
                    ?.get("command") as? Map<*, *>)?.get("continuationCommand")?.let { it as? Map<*, *> }
                    ?.get("token") as? String
                (direct ?: viaButton)?.let { return it }
            }
        }
        return null
    }

    private fun parseCommentsFromMutations(root: Map<*, *>): List<Comment> {
        val mutations = ((root["frameworkUpdates"] as? Map<*, *>)?.get("entityBatchUpdate") as? Map<*, *>)
            ?.get("mutations") as? List<*> ?: return emptyList()
        val out = mutableListOf<Comment>()
        for (mu in mutations) {
            val m = mu as? Map<*, *> ?: continue
            val payload = (m["payload"] as? Map<*, *>)?.get("commentEntityPayload") as? Map<*, *> ?: continue
            val props = payload["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val toolbar = payload["toolbar"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val author = payload["author"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val commentId = (props["commentId"] as? String) ?: (m["entityKey"] as? String) ?: continue
            val text = (props["content"] as? Map<*, *>)?.get("content") as? String ?: continue
            if (text.isBlank()) continue
            val heart = toolbar["heartState"] as? String
            out.add(
                Comment(
                    commentId = commentId,
                    author = props["authorButtonA11y"] as? String ?: author["displayName"] as? String ?: "",
                    authorChannelId = author["channelId"] as? String ?: "",
                    authorAvatarUrl = author["avatarThumbnailUrl"] as? String ?: "",
                    text = text,
                    likeCount = (toolbar["likeCountLiked"] as? String)
                        ?: (toolbar["likeCountNotliked"] as? String) ?: "",
                    publishedTime = props["publishedTime"] as? String ?: "",
                    replyCount = (toolbar["replyCount"] as? String)?.toIntOrNull() ?: 0,
                    isHearted = heart != null && heart != "TOOLBAR_HEART_STATE_UNHEARTED" && heart != "HEART_STATE_DEFAULT",
                    isVerified = author["isVerified"] as? Boolean ?: false,
                    isPinned = props["pinnedText"] != null
                )
            )
        }
        return out
    }

    // ---------- Subtitles ----------
    private fun parseSubtitles(root: Map<*, *>): List<Subtitle> {
        val tracks = ((root["captions"] as? Map<*, *>)?.get("playerCaptionsTracklistRenderer") as? Map<*, *>)
            ?.get("captionTracks") as? List<*> ?: return emptyList()
        return tracks.mapNotNull { t ->
            val m = t as? Map<*, *> ?: return@mapNotNull null
            val url = m["baseUrl"] as? String ?: return@mapNotNull null
            Subtitle(
                url = url,
                languageCode = m["languageCode"] as? String ?: "",
                name = textOf(m["name"]),
                auto = m["kind"] == "asr"
            )
        }
    }

    // ---------- Search suggestions ----------
    /** Search box ke autocomplete suggestions */
    fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube" +
            "&hl=${ExtractorConfig.hl}&gl=${ExtractorConfig.gl}&ds=yt&q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val text = downloader.getText(url, mapOf("User-Agent" to ExtractorConfig.webUserAgent)) ?: return emptyList()
        val a = text.indexOf('(')
        val b = text.lastIndexOf(')')
        if (a < 0 || b <= a) return emptyList()
        return try {
            val arr = moshi.adapter(List::class.java).fromJson(text.substring(a + 1, b)) ?: return emptyList()
            val list = arr.getOrNull(1) as? List<*> ?: return emptyList()
            list.mapNotNull { (it as? List<*>)?.firstOrNull() as? String }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Radio / Autoplay ----------
    /** Is gaane jaisa "mix" (YouTube Radio). Pehla item wahi gaana hota hai; includeSeed=false se hat jata hai. */
    fun getRadio(videoId: String, includeSeed: Boolean = false): List<VideoItem> {
        val root = postWeb("next", mapOf("videoId" to videoId, "playlistId" to "RD$videoId"))
        val items = itemsOf(root, "playlistPanelVideoRenderer", lockups = false)
        return if (includeSeed) items else items.filter { it.id != videoId }
    }

    // ---------- StreamX app ke liye extra: channel resolve, shorts, playlist search, RSS feed ----------

    /** "UC..." id, ya @handle / channel URL se channel id */
    fun resolveChannelId(input: String): String {
        Regex("UC[0-9A-Za-z_-]{22}").find(input)?.let { return it.value }
        val url = if (input.startsWith("http")) input else "https://www.youtube.com/" + input.trimStart('/')
        val root = postWeb("navigation/resolve_url", mapOf("url" to url))
        val browse = (root["endpoint"] as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>
        return browse?.get("browseId") as? String ?: ""
    }

    /** Channel ka Shorts tab */
    fun getChannelShorts(channelId: String): List<VideoItem> {
        val root = postWeb("browse", mapOf("browseId" to channelId, "params" to "EgZzaG9ydHPyBgUKA5oBAA=="))
        val found = mutableListOf<Pair<String, Map<*, *>>>()
        collectMulti(root, setOf("reelItemRenderer", "shortsLockupViewModel"), found)
        val seen = HashSet<String>()
        val out = ArrayList<VideoItem>()
        for ((k, r) in found) {
            var id: String? = null
            var title = ""
            var views = ""
            if (k == "reelItemRenderer") {
                id = r["videoId"] as? String
                title = textOf(r["headline"])
                views = textOf(r["viewCountText"])
            } else {
                val tap = (r["onTap"] as? Map<*, *>)?.get("innertubeCommand") as? Map<*, *>
                id = (tap?.get("reelWatchEndpoint") as? Map<*, *>)?.get("videoId") as? String
                val om = r["overlayMetadata"] as? Map<*, *>
                title = (om?.get("primaryText") as? Map<*, *>)?.get("content") as? String ?: ""
                views = (om?.get("secondaryText") as? Map<*, *>)?.get("content") as? String ?: ""
            }
            if (id == null || id.isEmpty() || !seen.add(id)) continue
            out.add(
                VideoItem(
                    id = id, title = title, channelName = "", duration = "", views = views,
                    published = "", thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg", isShort = true
                )
            )
        }
        return out
    }

    /** Playlists search */
    fun searchPlaylists(query: String): List<PlaylistSummary> {
        val root = postWeb("search", mapOf("query" to query, "params" to (SearchFilter.PLAYLISTS.params ?: "")))
        val found = mutableListOf<Pair<String, Map<*, *>>>()
        collectMulti(root, setOf("playlistRenderer", "lockupViewModel"), found)
        val seen = HashSet<String>()
        val out = ArrayList<PlaylistSummary>()
        for ((k, r) in found) {
            if (k == "playlistRenderer") {
                val id = r["playlistId"] as? String ?: continue
                if (!seen.add(id)) continue
                val thumbs = ((r["thumbnails"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("thumbnails") as? List<*>
                var thumb = (thumbs?.lastOrNull() as? Map<*, *>)?.get("url") as? String ?: ""
                if (thumb.startsWith("//")) thumb = "https:$thumb"
                out.add(
                    PlaylistSummary(
                        id = id,
                        title = textOf(r["title"]),
                        thumbnailUrl = thumb,
                        videoCount = (r["videoCount"] as? String)?.replace(",", "")?.toLongOrNull() ?: 0L,
                        uploader = textOf(r["shortBylineText"]).ifEmpty { textOf(r["longBylineText"]) }
                    )
                )
            } else {
                if ((r["contentType"] as? String) != "LOCKUP_CONTENT_TYPE_PLAYLIST") continue
                val id = r["contentId"] as? String ?: continue
                if (!seen.add(id)) continue
                val lm = (r["metadata"] as? Map<*, *>)?.get("lockupMetadataViewModel") as? Map<*, *>
                val title = (lm?.get("title") as? Map<*, *>)?.get("content") as? String ?: ""
                val badge = findFirst(r["contentImage"], "thumbnailBadgeViewModel") as? Map<*, *>
                val count = (badge?.get("text") as? String)?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
                val srcs = findFirst(r["contentImage"], "sources") as? List<*>
                var thumb = (srcs?.firstOrNull() as? Map<*, *>)?.get("url") as? String ?: ""
                if (thumb.startsWith("//")) thumb = "https:$thumb"
                val rows = ((lm?.get("metadata") as? Map<*, *>)?.get("contentMetadataViewModel") as? Map<*, *>)
                    ?.get("metadataRows") as? List<*>
                val firstPart = (((rows?.firstOrNull() as? Map<*, *>)?.get("metadataParts") as? List<*>)?.firstOrNull() as? Map<*, *>)
                val uploader = (firstPart?.get("text") as? Map<*, *>)?.get("content") as? String ?: ""
                out.add(PlaylistSummary(id = id, title = title, thumbnailUrl = thumb, videoCount = count, uploader = uploader))
            }
        }
        return out
    }

    /** Search me channel dhoondh ke uska avatar (URL khali = nahi mila) */
    fun searchChannelAvatar(query: String): String {
        val root = postWeb("search", mapOf("query" to query, "params" to (SearchFilter.CHANNELS.params ?: "")))
        val found = mutableListOf<Pair<String, Map<*, *>>>()
        collectMulti(root, setOf("channelRenderer"), found)
        val r = found.firstOrNull()?.second ?: return ""
        val thumbs = (r["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>
        val url = (thumbs?.lastOrNull() as? Map<*, *>)?.get("url") as? String ?: return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    /**
     * Channel ke naye uploads (YouTube ka public RSS feed: ~15 sabse naye videos, asli date ke saath).
     * Duration nahi milti. Shorts /shorts/ link se pehchane jate hain.
     */
    fun channelFeed(channelId: String): List<VideoItem> {
        val xml = downloader.getText(
            "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId",
            mapOf("User-Agent" to ExtractorConfig.webUserAgent)
        ) ?: return emptyList()
        fun tag(src: String, re: String): String =
            Regex(re, RegexOption.DOT_MATCHES_ALL).find(src)?.groupValues?.get(1)?.trim() ?: ""
        fun unesc(t: String): String = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        val channelName = unesc(tag(xml, "<author>.*?<name>(.*?)</name>"))
        val out = ArrayList<VideoItem>()
        for (m in Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val e = m.groupValues[1]
            val id = tag(e, "<yt:videoId>(.*?)</yt:videoId>")
            if (id.isEmpty()) continue
            out.add(
                VideoItem(
                    id = id,
                    title = unesc(tag(e, "<title>(.*?)</title>")),
                    channelName = channelName,
                    duration = "",
                    views = "",
                    published = "",
                    thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    channelId = channelId,
                    publishedIso = tag(e, "<published>(.*?)</published>"),
                    viewCount = tag(e, "<media:statistics views=\"(\\d+)\"").toLongOrNull() ?: -1L,
                    isShort = e.contains("/shorts/")
                )
            )
        }
        return out
    }

    // ---------- Channel info ----------
    private fun findFirst(node: Any?, key: String): Any? {
        when (node) {
            is Map<*, *> -> {
                if (node.containsKey(key)) return node[key]
                for (v in node.values) findFirst(v, key)?.let { return it }
            }
            is List<*> -> for (x in node) findFirst(x, key)?.let { return it }
        }
        return null
    }

    fun getChannelInfo(channelId: String): ChannelInfo {
        val root = postWeb("browse", mapOf("browseId" to channelId))
        val md = (root["metadata"] as? Map<*, *>)?.get("channelMetadataRenderer") as? Map<*, *>
            ?: throw Exception("Channel nahi mila: $channelId")
        val avatar = ((md["avatar"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
            ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } ?: ""
        return ChannelInfo(
            id = md["externalId"] as? String ?: channelId,
            name = md["title"] as? String ?: "",
            description = md["description"] as? String ?: "",
            avatarUrl = avatar,
            url = md["channelUrl"] as? String ?: "https://www.youtube.com/channel/$channelId",
            subscribers = textOf(findFirst(root, "subscriberCountText")),
            keywords = md["keywords"] as? String ?: ""
        )
    }

    // ---------- YouTube Music search (best-effort, layout badle to update chahiye) ----------
    private fun postMusic(endpoint: String, body: Map<String, Any>): Map<*, *> {
        visitorData // same live-version fetch as webClientJson — see its comment above
        val client = mapOf(
            "clientName" to "WEB_REMIX",
            "clientVersion" to ExtractorConfig.musicClientVersion,
            "hl" to ExtractorConfig.hl,
            "gl" to ExtractorConfig.gl
        )
        val payload = moshi.adapter(Map::class.java).toJson(body + ("context" to mapOf("client" to client)))
        val headers = mapOf(
            "User-Agent" to ExtractorConfig.webUserAgent,
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/",
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to ExtractorConfig.musicClientVersion
        )
        val response = downloader.postJson("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", payload, headers)
        return moshi.adapter(Map::class.java).fromJson(response) as Map<*, *>
    }

    fun searchMusic(query: String, filter: MusicFilter = MusicFilter.SONGS): List<MusicItem> {
        val body = mutableMapOf<String, Any>("query" to query)
        filter.params?.let { body["params"] = it }
        val root = postMusic("search", body)
        val rs = mutableListOf<Map<*, *>>()
        collectRenderers(root, "musicResponsiveListItemRenderer", rs)
        val seen = HashSet<String>()
        return rs.mapNotNull { toMusicItem(it) }.filter { seen.add(it.id) }
    }

    private val typeWords = setOf("Song", "Video", "Album", "Single", "EP", "Artist", "Playlist", "Profile")
    private val clockRegex = Regex("^\\d+:\\d{2}(:\\d{2})?$")

    private fun toMusicItem(r: Map<*, *>): MusicItem? {
        val cols = (r["flexColumns"] as? List<*>)?.mapNotNull {
            ((it as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)?.get("text")
        } ?: return null
        if (cols.isEmpty()) return null

        val title = textOf(cols[0])
        val subtitle = cols.drop(1).joinToString(" • ") { textOf(it) }
        val parts = subtitle.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        val typeWord = parts.firstOrNull()?.takeIf { it in typeWords }
        val rest = parts.drop(if (typeWord != null) 1 else 0)
        val duration = rest.lastOrNull { clockRegex.matches(it) } ?: ""
        val names = rest.filter { it != duration }

        val videoId = (r["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String
            ?: findFirst(r["overlay"], "videoId") as? String
        val browseId = findFirst(r["navigationEndpoint"], "browseId") as? String

        val type = when {
            videoId != null -> if (typeWord == "Video") MusicType.VIDEO else MusicType.SONG
            browseId?.startsWith("MPRE") == true -> MusicType.ALBUM
            browseId?.startsWith("UC") == true -> MusicType.ARTIST
            browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")) -> MusicType.PLAYLIST
            else -> return null
        }
        val id = when (type) {
            MusicType.SONG, MusicType.VIDEO -> videoId!!
            MusicType.PLAYLIST -> browseId!!.removePrefix("VL")
            else -> browseId!!
        }
        val thumbs = findFirst(r["thumbnail"], "thumbnails") as? List<*>
        val thumb = (thumbs?.lastOrNull() as? Map<*, *>)?.get("url") as? String ?: ""
        return MusicItem(
            type = type,
            id = id,
            title = title,
            artist = if (type == MusicType.ARTIST) title else names.getOrNull(0) ?: "",
            album = if (type == MusicType.SONG) names.getOrNull(1) ?: "" else "",
            duration = duration,
            subtitle = subtitle,
            thumbnailUrl = thumb
        )
    }
}
