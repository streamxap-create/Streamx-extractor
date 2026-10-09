package com.streamx.extractor

/**
 * YouTube badalta rehta hai: versions / keys yahan ek jagah hain.
 * App start par override kar sakte ho, library dobara build karne ki zaroorat nahi.
 */
object ExtractorConfig {
    @Volatile var playerApiKey: String = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    @Volatile var searchApiKey: String = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    @Volatile var webClientVersion: String = "2.20250101.00.00"
    @Volatile var webUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    @Volatile var hl: String = "en"
    @Volatile var gl: String = "IN"
    @Volatile var musicClientVersion: String = "1.20250101.01.00"
    /** getVideo() ka cache (seconds). 0 = band. Stream URLs ~6 ghante chalte hain, 20 min safe hai. */
    @Volatile var cacheSeconds: Int = 1200
}
