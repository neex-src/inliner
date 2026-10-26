package com.neex.inliner.recorder

/** One call of a recorded method, with the hits it made and the calls it made, in order. */
class Invocation(
    val thread: Long,
    val method: MethodKey,
    val depth: Int,
    val parent: Invocation?,
    /** The caller's last hit before this call: the line the call was made from. */
    val callSite: Hit?,
) {
    val hits = ArrayList<Hit>()
    val children = ArrayList<Invocation>()

    /** A return of this call was recorded; false also when it ended by an exception. */
    var returned = false
        internal set
}

/**
 * The calls of a recording, per thread, rebuilt from the hits alone: their stack depth and the
 * recorded returns.
 *
 * A hit deeper than the open call is a new call made from it (with unrecorded framework frames in
 * between, possibly); a hit of the same method at the same depth continues the open call — a loop
 * back to the first line — unless that call already returned, then it is the next call; a hit
 * shallower than the open call means the calls in between are over (returned, or unwound by an
 * exception).
 */
object CallTree {

    fun build(recording: Recording): Map<Long, List<Invocation>> =
        recording.hits.groupBy { it.thread }.mapValues { (thread, hits) -> build(thread, hits) }

    private fun build(thread: Long, hits: List<Hit>): List<Invocation> {
        val roots = ArrayList<Invocation>()
        val open = ArrayDeque<Invocation>()
        for (hit in hits) {
            while (open.isNotEmpty() && (open.last().depth > hit.depth || open.last().returned)) open.removeLast()
            val top = open.lastOrNull()
            val current = if (top != null && top.depth == hit.depth && top.method == hit.site.method) {
                top
            } else {
                if (top != null && top.depth == hit.depth) open.removeLast()
                val parent = open.lastOrNull()
                Invocation(thread, hit.site.method, hit.depth, parent, parent?.hits?.lastOrNull()).also {
                    (parent?.children ?: roots) += it
                    open.addLast(it)
                }
            }
            current.hits += hit
            if (hit.returns) current.returned = true
        }
        return roots
    }
}
