package com.neex.inliner.replay

import com.neex.inliner.recorder.CallTree
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Invocation
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.VarValue

/** Where the cursor can go from where it is. */
enum class Step { INTO, OVER, OUT, BACK_INTO, BACK_OVER }

/**
 * A recording being read: one thread at a time, a cursor on its stops. No UI: [ReplayView]
 * paints it, [ReplayService] owns it.
 *
 * A stop is a hit at the start of a line — what a debugger stops on. Return hits are not stops;
 * they carry a call's last values (what its last lines changed). Moves are a debugger's, in both
 * directions; backwards over from a call's first line lands on the line that made the call.
 */
class Replay(val recording: Recording) {

    val calls: Map<Long, List<Invocation>> = CallTree.build(recording)

    private val callOf: Map<Int, Invocation> = HashMap<Int, Invocation>().also { index ->
        fun walk(call: Invocation) {
            call.hits.forEach { index[it.seq] = call }
            call.children.forEach(::walk)
        }
        calls.values.flatten().forEach(::walk)
    }

    var thread: Long = recording.hits.firstOrNull()?.thread ?: 0
        private set

    private var stops: List<Hit> = stopsOf(thread)

    /** Index into this thread's stops. */
    var position: Int = 0
        private set

    val isEmpty: Boolean get() = stops.isEmpty()

    val stopCount: Int get() = stops.size

    val current: Hit get() = stops[position]

    /** This thread's stops, in order. */
    fun stops(): List<Hit> = stops

    fun callOf(hit: Hit): Invocation = callOf.getValue(hit.seq)

    private fun stopsOf(thread: Long) = recording.hits.filter { it.thread == thread && it.startsLine }

    fun selectThread(id: Long) {
        thread = id
        stops = stopsOf(id)
        position = 0
    }

    /** Moves the cursor; false when there is nowhere to go (it stays). */
    fun move(step: Step): Boolean {
        if (isEmpty) return false
        val depth = current.depth
        val target = when (step) {
            Step.INTO -> (position + 1).takeIf { it < stops.size }
            Step.BACK_INTO -> (position - 1).takeIf { it >= 0 }
            Step.OVER -> (position + 1 until stops.size).firstOrNull { stops[it].depth <= depth }
            Step.OUT -> (position + 1 until stops.size).firstOrNull { stops[it].depth < depth }
            Step.BACK_OVER -> (position - 1 downTo 0).firstOrNull { stops[it].depth <= depth }
        } ?: return false
        position = target
        return true
    }

    /** To the next ([forward]) or previous run of a line; false when it has none that way. */
    fun toLine(className: String, line: Int, forward: Boolean): Boolean {
        val range = if (forward) (position + 1 until stops.size) else (position - 1 downTo 0)
        val target = range.firstOrNull { stops[it].site.className == className && stops[it].site.line == line } ?: return false
        position = target
        return true
    }

    fun moveTo(hit: Hit): Boolean {
        if (hit.thread != thread) selectThread(hit.thread)
        val i = stops.indexOfFirst { it.seq >= hit.seq }.takeIf { it >= 0 } ?: return false
        position = i
        return true
    }

    /**
     * What each line of the current call has done so far, by line: the values its last run (up to
     * the cursor) changed. The cursor's own line hasn't run yet: it is not in here.
     */
    fun changesSoFar(): Map<Int, List<VarValue>> = changes(callOf(current), current.seq)

    /** The lines the current call ran, start to end — not only up to the cursor: its path. */
    fun linesOfCall(): Set<Int> = linesOf(callOf(current))

    /**
     * What each line of [call] changed in its last run before [upTo] (a hit's seq; null: the whole
     * call), by line — told by the next hit of the call, which may be its return.
     */
    fun changes(call: Invocation, upTo: Int?): Map<Int, List<VarValue>> {
        val hits = call.hits
        val out = HashMap<Int, List<VarValue>>()
        for ((i, hit) in hits.withIndex()) {
            if (upTo != null && hit.seq >= upTo) break
            if (!hit.startsLine) continue
            val after = hits.getOrNull(i + 1)?.takeIf { upTo == null || it.seq <= upTo } ?: continue
            val before = hit.values.associate { it.name to it.text }
            out[hit.site.line] = after.values.filter { it.name != "this" && before[it.name] != it.text }
        }
        return out
    }

    fun linesOf(call: Invocation): Set<Int> = call.hits.filter { it.startsLine }.mapTo(HashSet()) { it.site.line }

    /**
     * The line that gave [name] the value it has at the cursor: the last line of the current call
     * that changed it — null when the call never did (it came in as an argument, or before the
     * recording started).
     */
    fun lastChange(name: String): Hit? {
        val hits = callOf(current).hits
        val at = hits.indexOfFirst { it.seq == current.seq }
        return (at - 1 downTo 0).map { hits[it] }.firstOrNull { changes(it, hits, name) }
    }

    /** The next line of the current call that changes [name], after the cursor's own. */
    fun nextChange(name: String): Hit? {
        val hits = callOf(current).hits
        val at = hits.indexOfFirst { it.seq == current.seq }
        return (at + 1 until hits.size).map { hits[it] }.firstOrNull { changes(it, hits, name) }
    }

    private fun changes(hit: Hit, hits: List<Hit>, name: String): Boolean {
        if (!hit.startsLine) return false
        val next = hits.getOrNull(hits.indexOf(hit) + 1) ?: return false
        return valueOf(next, name) != valueOf(hit, name) && valueOf(next, name) != null
    }

    fun valueOf(hit: Hit, name: String): String? = hit.values.firstOrNull { it.name == name }?.text

    /** The values [name] took in [call], each change once; with the index of the one current at [cursor] (a seq), if any. */
    fun history(call: Invocation, name: String, cursor: Int?): Pair<List<String>, Int?> {
        val values = ArrayList<String>()
        var now: Int? = null
        for (hit in call.hits) {
            val value = valueOf(hit, name) ?: continue
            if (values.lastOrNull() != value) values += value
            if (cursor != null && hit.seq <= cursor) now = values.size - 1
        }
        return values to now
    }

    /** This thread's calls in the order they were made, each with its nesting depth. */
    fun callList(): List<Pair<Invocation, Int>> {
        val out = ArrayList<Pair<Invocation, Int>>()
        fun walk(call: Invocation, level: Int) {
            out += call to level
            call.children.forEach { walk(it, level + 1) }
        }
        calls[thread].orEmpty().forEach { walk(it, 0) }
        return out
    }
}
