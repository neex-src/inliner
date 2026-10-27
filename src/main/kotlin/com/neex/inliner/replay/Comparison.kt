package com.neex.inliner.replay

import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Invocation
import com.neex.inliner.recorder.MethodKey

/**
 * Two runs from the same breakpoint side by side: the original ([base]) and the one recorded
 * under a what-if ([variant]).
 *
 * Calls are matched through the call trees, level by level: the calls each call made, aligned by
 * method (longest common subsequence) — a call only one run made has no counterpart. The runs part
 * at the first stop, in step, where they are not on the same line.
 */
class Comparison(val base: Replay, val variant: Replay) {

    private val counterparts = HashMap<Invocation, Invocation>()

    init {
        align(base.calls[base.thread].orEmpty(), variant.calls[variant.thread].orEmpty())
    }

    fun other(of: Replay): Replay = if (of === variant) base else variant

    /** The same call in the other run, if it made it. */
    fun counterpart(call: Invocation): Invocation? = counterparts[call]

    /** In [of]'s run, the first stop where it is no longer on the other run's line; null if the paths are the same. */
    fun divergence(of: Replay): Hit? {
        val mine = of.stops()
        val theirs = other(of).stops()
        val common = minOf(mine.size, theirs.size)
        val at = (0 until common).firstOrNull { key(mine[it]) != key(theirs[it]) }
            ?: if (mine.size != theirs.size) common else return null
        return mine.getOrNull(at) ?: mine.lastOrNull()
    }

    private fun key(hit: Hit) = Triple(hit.site.className, hit.site.methodName, hit.site.line)

    private fun align(a: List<Invocation>, b: List<Invocation>) {
        for ((x, y) in lcs(a, b) { it.method }) {
            counterparts[x] = y
            counterparts[y] = x
            align(x.children, y.children)
        }
    }

    private fun lcs(a: List<Invocation>, b: List<Invocation>, key: (Invocation) -> MethodKey): List<Pair<Invocation, Invocation>> {
        val n = a.size
        val m = b.size
        val len = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            len[i][j] = if (key(a[i]) == key(b[j])) len[i + 1][j + 1] + 1 else maxOf(len[i + 1][j], len[i][j + 1])
        }
        val out = ArrayList<Pair<Invocation, Invocation>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                key(a[i]) == key(b[j]) -> { out += a[i] to b[j]; i++; j++ }
                len[i + 1][j] >= len[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
