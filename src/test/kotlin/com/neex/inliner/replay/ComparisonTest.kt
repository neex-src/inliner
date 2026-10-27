package com.neex.inliner.replay

import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The original run and a what-if run from the same breakpoint, matched. */
class ComparisonTest {

    private class Run {
        val hits = ArrayList<Hit>()
        fun hit(method: String, line: Int, depth: Int, vararg values: Pair<String, String>, returns: Boolean = false) {
            hits += Hit(hits.size, 1, Site("p.A", method, "()V", line, null), !returns, returns, depth, values.map { VarValue(it.first, it.second) })
        }
        fun replay() = Replay(Recording(mapOf(1L to "main"), hits, emptySet(), false))
    }

    /** f: line 10 decides, 12 only when bulk, 14 calls g; g: 20, 21. */
    private fun run(bulk: Boolean) = Run().apply {
        hit("f", 10, 2, "bulk" to "$bulk")
        hit("f", 11, 2, "bulk" to "$bulk", "p" to "0")
        if (bulk) hit("f", 12, 2, "bulk" to "$bulk", "p" to "0")
        hit("f", 14, 2, "bulk" to "$bulk", "p" to if (bulk) "5" else "0")
        hit("g", 20, 3, "v" to if (bulk) "5" else "0")
        hit("g", 21, 3, "v" to if (bulk) "5" else "0", "w" to if (bulk) "10" else "0")
        hit("g", 21, 3, returns = true)
        hit("f", 15, 2)
        hit("f", 15, 2, returns = true)
    }.replay()

    @Test
    fun callsAreMatchedAcrossRuns() {
        val base = run(bulk = true)
        val variant = run(bulk = false)
        val c = Comparison(base, variant)
        val f = variant.calls.getValue(1).single()
        assertSame(base.calls.getValue(1).single(), c.counterpart(f))
        assertSame(base.calls.getValue(1).single().children.single(), c.counterpart(f.children.single()))
        assertSame(f, c.counterpart(c.counterpart(f)!!))
    }

    @Test
    fun runsPartWhereTheBranchWent() {
        val base = run(bulk = true)
        val variant = run(bulk = false)
        val c = Comparison(base, variant)
        assertEquals("the variant skipped line 12: at its third stop it is on 14", 14, c.divergence(variant)!!.site.line)
        assertEquals("the original was on 12 there", 12, c.divergence(base)!!.site.line)
    }

    @Test
    fun samePathNoDivergence() {
        val c = Comparison(run(true), run(true))
        assertNull(c.divergence(c.variant))
    }

    @Test
    fun aCallOnlyOneRunMadeHasNoCounterpart() {
        val base = run(bulk = true)
        val variant = Run().apply {
            hit("f", 10, 2)
            hit("h", 30, 3)
            hit("h", 30, 3, returns = true)
            hit("f", 15, 2)
        }.replay()
        val c = Comparison(base, variant)
        val h = variant.calls.getValue(1).single().children.single()
        assertNull(c.counterpart(h))
        assertNull("g was only in the original", c.counterpart(base.calls.getValue(1).single().children.single()))
    }
}
