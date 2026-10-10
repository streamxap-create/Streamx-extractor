package com.streamx.extractor

import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Spotify jaisi queue: next / previous, shuffle (current gaana pehle rehta hai),
 * repeat (off / all / one), add next, remove, move. UI-free, kisi bhi player ke saath chalegi.
 */
class PlayQueue<T>(
    initial: List<T> = emptyList(),
    startIndex: Int = 0,
    private val random: Random = Random.Default
) {
    private class Entry<V>(val value: V)

    private var original = initial.map { Entry(it) }.toMutableList()   // user ka order
    private var play = original.toMutableList()                         // asli chalne ka order
    private var pos = if (play.isEmpty()) -1 else startIndex.coerceIn(0, play.lastIndex)

    var repeat: RepeatMode = RepeatMode.OFF
    var shuffled: Boolean = false
        private set

    val size: Int get() = play.size
    val isEmpty: Boolean get() = play.isEmpty()
    val current: T? get() = play.getOrNull(pos)?.value
    val currentIndex: Int get() = pos
    /** Chalne ke order mein saare items */
    val items: List<T> get() = play.map { it.value }
    val hasNext: Boolean get() = play.isNotEmpty() && (pos < play.lastIndex || repeat != RepeatMode.OFF)

    /** Agla item bina badle (stream URL prefetch ke liye) */
    fun peekNext(): T? = when {
        play.isEmpty() -> null
        repeat == RepeatMode.ONE -> current
        pos < play.lastIndex -> play[pos + 1].value
        repeat == RepeatMode.ALL -> play.first().value
        else -> null
    }

    /** auto = true jab gaana khud khatam hua (repeat ONE ho to wahi dobara). Button dabane par auto = false. */
    fun next(auto: Boolean = false): T? {
        if (play.isEmpty()) return null
        if (auto && repeat == RepeatMode.ONE) return current
        return when {
            pos < play.lastIndex -> { pos++; current }
            repeat == RepeatMode.ALL -> { pos = 0; current }
            else -> null
        }
    }

    /** Pehle item par ho to wahi dobara shuru (repeat ALL ho to aakhri par jata hai) */
    fun previous(): T? {
        if (play.isEmpty()) return null
        return when {
            pos > 0 -> { pos--; current }
            repeat == RepeatMode.ALL -> { pos = play.lastIndex; current }
            else -> current
        }
    }

    fun jumpTo(index: Int): T? {
        if (index !in play.indices) return null
        pos = index
        return current
    }

    fun add(item: T) {
        val e = Entry(item)
        original.add(e)
        play.add(e)
        if (pos < 0) pos = 0
    }

    fun addAll(list: List<T>) = list.forEach { add(it) }

    /** "Play next": abhi wale gaane ke baad */
    fun addNext(item: T) {
        val e = Entry(item)
        if (play.isEmpty()) {
            original.add(e); play.add(e); pos = 0
            return
        }
        play.add(pos + 1, e)
        val curEntry = play[pos]
        val oi = original.indexOf(curEntry)
        original.add(if (oi >= 0) oi + 1 else original.size, e)
    }

    fun remove(index: Int): Boolean {
        if (index !in play.indices) return false
        val e = play.removeAt(index)
        original.remove(e)
        when {
            play.isEmpty() -> pos = -1
            index < pos -> pos--
            pos > play.lastIndex -> pos = play.lastIndex
        }
        return true
    }

    fun move(from: Int, to: Int) {
        if (from !in play.indices || to !in play.indices || from == to) return
        val curEntry = play.getOrNull(pos)
        val e = play.removeAt(from)
        play.add(to, e)
        if (curEntry != null) pos = play.indexOf(curEntry)
        if (!shuffled) original = play.toMutableList()
    }

    fun clear() {
        original.clear(); play.clear(); pos = -1
    }

    fun shuffle() {
        val cur = play.getOrNull(pos)
        val rest = play.filter { it !== cur }.shuffled(random)
        play = ((if (cur != null) listOf(cur) else emptyList()) + rest).toMutableList()
        pos = if (cur != null) 0 else -1
        shuffled = true
    }

    fun unshuffle() {
        val cur = play.getOrNull(pos)
        play = original.toMutableList()
        pos = if (cur != null) play.indexOf(cur) else if (play.isEmpty()) -1 else 0
        shuffled = false
    }

    fun toggleShuffle() { if (shuffled) unshuffle() else shuffle() }

    fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }
}
