package com.streamx.extractor

import com.streamx.extractor.model.Video
import com.streamx.extractor.model.VideoItem
import com.streamx.extractor.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Coroutine versions: main thread par seedha call kar sakte ho, kaam IO thread par hota hai.

suspend fun StreamxExtractor.getVideoAsync(videoId: String, useCache: Boolean = true): Video =
    withContext(Dispatchers.IO) { getVideo(videoId, useCache) }

suspend fun StreamxExtractor.getRadioAsync(videoId: String): List<VideoItem> =
    withContext(Dispatchers.IO) { getRadio(videoId) }

suspend fun StreamxExtractor.suggestionsAsync(query: String): List<String> =
    withContext(Dispatchers.IO) { suggestions(query) }

suspend fun StreamxExtractor.findLyricsAsync(title: String, artist: String, durationSec: Int = 0): Lyrics? =
    withContext(Dispatchers.IO) { findLyrics(title, artist, durationSec) }
