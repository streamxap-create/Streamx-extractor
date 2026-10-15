package com.streamx.extractor

import com.streamx.extractor.service.LyricsQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsQueryTest {
    @Test
    fun splitsArtistAndTitleAndDropsNoise() {
        val c = LyricsQuery.candidates("Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley")
        assertEquals("Never Gonna Give You Up" to "Rick Astley", c.first())
    }

    @Test
    fun topicChannelIsUsedAsArtist() {
        val c = LyricsQuery.candidates("Shape of You", "Ed Sheeran - Topic")
        assertEquals("Shape of You" to "Ed Sheeran", c.first())
    }

    @Test
    fun cleanTitleRemovesBracketsAndPipeSuffix() {
        assertEquals("Tum Hi Ho", LyricsQuery.cleanTitle("Tum Hi Ho [Official Video] | Arijit Singh"))
    }
}
