package com.streamx.extractor

import com.streamx.extractor.model.VideoItem
import com.streamx.extractor.model.parseClock
import com.streamx.extractor.model.toTrack
import com.streamx.extractor.service.LrcParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicTest {

    @Test
    fun queueNextPreviousAndRepeat() {
        val q = PlayQueue(listOf("a", "b", "c"))
        assertEquals("a", q.current)
        assertEquals("b", q.next())
        assertEquals("c", q.next())
        assertNull(q.next())
        q.repeat = RepeatMode.ALL
        assertEquals("a", q.next())
        assertEquals("c", q.previous())
        q.repeat = RepeatMode.ONE
        assertEquals("c", q.next(auto = true))
        assertEquals("c", q.peekNext())
    }

    @Test
    fun shuffleKeepsCurrentFirstAndUnshuffleRestores() {
        val q = PlayQueue((1..10).toList(), startIndex = 4, random = Random(1))
        q.shuffle()
        assertEquals(5, q.current)
        assertEquals(0, q.currentIndex)
        assertEquals((1..10).toList(), q.items.sorted())
        q.unshuffle()
        assertEquals((1..10).toList(), q.items)
        assertEquals(5, q.current)
        assertEquals(4, q.currentIndex)
    }

    @Test
    fun addNextAndRemove() {
        val q = PlayQueue(listOf("a", "b", "c"))
        q.addNext("x")
        assertEquals(listOf("a", "x", "b", "c"), q.items)
        q.next()
        q.remove(0)
        assertEquals("x", q.current)
        assertEquals(0, q.currentIndex)
        assertEquals(3, q.size)
    }

    @Test
    fun lrcParsing() {
        val lines = LrcParser.parse("[ar:Someone]\n[00:12.50]Hello\n[00:05.1][01:00.00]Chorus\n[00:20]End")
        assertEquals(4, lines.size)
        assertEquals(5100L, lines[0].timeMs)
        assertEquals(12500L, lines[1].timeMs)
        assertEquals("Chorus", lines[3].text)
        assertEquals(-1, LrcParser.lineAt(lines, 1000))
        assertEquals(1, LrcParser.lineAt(lines, 13000))
        assertEquals(3, LrcParser.lineAt(lines, 999999))
    }

    @Test
    fun trackFromVideoItem() {
        fun item(title: String, channel: String, dur: String) =
            VideoItem("id", title, channel, dur, "", "", "thumb")
        val a = item("Artist X - Song Y", "Some Channel", "3:45").toTrack()
        assertEquals("Artist X", a.artist)
        assertEquals("Song Y", a.title)
        assertEquals(225, a.durationSec)
        val b = item("Song Y", "Artist X - Topic", "1:02:03").toTrack()
        assertEquals("Artist X", b.artist)
        assertEquals("Song Y", b.title)
        assertEquals(3723, b.durationSec)
        assertTrue(parseClock("bad") == 0)
    }
}
