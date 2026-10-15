package com.streamx.extractor.client

import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class Downloader {

    private val client = (com.streamx.extractor.ExtractorConfig.httpClient ?: OkHttpClient.Builder().build()).newBuilder()
        // OkHttp ek host par sirf 5 request ek saath chalata hai; probes ke liye zyada chahiye
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            if (original.header("User-Agent") == null) {
                builder.header(
                    "User-Agent",
                    "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
                )
            }
            builder.header("Accept-Language", "en-US,en;q=0.9")
            chain.proceed(builder.build())
        }
        .build()

    // Probes jaldi fail hone chahiye (slow/block client ka 10+ second intezar nahi)
    private val probeClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = jsonBody.toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = try { response.body?.string()?.take(500) } catch (e: Exception) { null }
                    throw IOException("HTTP ${response.code}: ${response.message} ${errBody ?: ""}".trim())
                }
                response.body?.string() ?: throw IOException("Empty response body")
            }
        } catch (e: IOException) {
            throw IOException("Network error: ${e.message}", e)
        }
    }

    // Stream URL chalta hai ya nahi: sirf pehle 2 bytes maangta hai, HTTP code wapas deta hai (-1 = network fail)
    fun probe(url: String, headers: Map<String, String> = emptyMap()): Int {
        val builder = Request.Builder().url(url).get().header("Range", "bytes=0-1")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            probeClient.newCall(builder.build()).execute().use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    // Simple GET (HLS manifest padhne ke liye). Fail ho to null.
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            client.newCall(builder.build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Player jaisa asli test: Range maang kar kuch bytes padhta hai. 200/206 = theek, 403 = block, -1 = network fail, -2 = khali jawab
    fun probeRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        endInclusive: Long,
        readBytes: Int = 4096
    ): Int {
        val builder = Request.Builder().url(url).get().header("Range", "bytes=$start-$endInclusive")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            probeClient.newCall(builder.build()).execute().use { r ->
                if (r.code == 200 || r.code == 206) {
                    val buf = ByteArray(readBytes)
                    val n = r.body?.byteStream()?.read(buf) ?: -1
                    if (n <= 0) -2 else r.code
                } else r.code
            }
        } catch (e: Exception) {
            -1
        }
    }
}
