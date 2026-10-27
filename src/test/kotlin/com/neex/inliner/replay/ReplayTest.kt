package com.neex.inliner.replay

import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moves and what a call has done so far, on a hand-made recording. */
class ReplayTest {

    private val hits = ArrayList<Hit>()

    private fun hit(method: String, line: Int, depth: Int, vararg values: Pair<String, String>, returns: Boolean = false, startsLine: Boolean = true, thread: Long = 1) {
        hits += Hit(hits.size, thread, Site("p.A", method, "()V", line, "p/A.java"), startsLine, returns, depth, values.map { VarValue(it.first, it.second) })
    }

    private fun replay(more: () -> Unit = {}): Replay {
        hit("main", 10, 2, "x" to "0")
        hit("main", 11, 2, "x" to "1")                                  // calls f
        hit("f", 20, 3, "a" to "1")
        hit("f", 21, 3, "a" to "1", "b" to "2")
        hit("f", 21, 3, "a" to "1", "b" to "2", "r" to "3", returns = true, startsLine = false)
        hit("main", 12, 2, "x" to "1", "y" to "3")
        hit("main", 13, 2, "i" to "0")                                  // loop
        hit("main", 14, 2, "i" to "0", "s" to "0")
        hit("main", 13, 2, "i" to "1", "s" to "5")
        hit("main", 14, 2, "i" to "1", "s" to "5")
        hit("main", 13, 2, "i" to "2", "s" to "10")
        hit("main", 15, 2, "s" to "10")
        hit("main", 15, 2, "s" to "10", returns = true, startsLine = false)
        more()
        return Replay(Recording(mapOf(1L to "main"), hits, emptySet(), false))
    }

    private fun Replay.at() = current.seq

    private fun Replay.goTo(seq: Int) = assertTrue(moveTo(hits[seq]))

    @Test
    fun returnsAreNotStops() {
        val r = replay()
        assertEquals(11, r.stopCount)
        r.goTo(3)
        assertTrue(r.move(Step.INTO))
        assertEquals("from f's last line into main, past f's return", 5, r.at())
    }

    @Test
    fun forwardMoves() {
        val r = replay()
        r.goTo(1)
        assertTrue(r.move(Step.INTO)); assertEquals(2, r.at())
        r.goTo(1)
        assertTrue(r.move(Step.OVER)); assertEquals(5, r.at())
        r.goTo(2)
        assertTrue(r.move(Step.OUT)); assertEquals(5, r.at())
        r.goTo(11)
        assertFalse(r.move(Step.OVER)); assertEquals("stays", 11, r.at())
    }

    @Test
    fun backwardMoves() {
        val r = replay()
        r.goTo(2)
        assertTrue("back over from a call's first line: the call site", r.move(Step.BACK_OVER)); assertEquals(1, r.at())
        r.goTo(5)
        assertTrue(r.move(Step.BACK_INTO)); assertEquals(3, r.at())
        r.goTo(5)
        assertTrue(r.move(Step.BACK_OVER)); assertEquals(1, r.at())
        r.goTo(0)
        assertFalse(r.move(Step.BACK_INTO))
    }

    @Test
    fun runsOfALine() {
        val r = replay()
        r.goTo(7)
        assertTrue(r.toLine("p.A", 14, forward = true)); assertEquals(9, r.at())
        assertTrue(r.toLine("p.A", 14, forward = false)); assertEquals(7, r.at())
        assertFalse(r.toLine("p.A", 14, forward = false)); assertEquals(7, r.at())
        assertFalse(r.toLine("p.A", 99, forward = true))
    }

    @Test
    fun whatTheCallChangedSoFar() {
        val r = replay()
        r.goTo(5)
        val atCall = r.changesSoFar().mapValues { (_, v) -> v.joinToString { "${it.name}=${it.text}" } }
        assertEquals(mapOf(10 to "x=1", 11 to "y=3"), atCall)
        r.goTo(10)
        val inLoop = r.changesSoFar().mapValues { (_, v) -> v.joinToString { "${it.name}=${it.text}" } }
        assertEquals("the line's last run before the cursor", "i=2, s=10", inLoop[14])
        assertEquals("", inLoop[13])
        assertTrue("the cursor's line hasn't run", 13 in inLoop && 15 !in inLoop)
    }

    @Test
    fun pathOfTheCall() {
        val r = replay()
        r.goTo(0)
        assertEquals(setOf(10, 11, 12, 13, 14, 15), r.linesOfCall())
        r.goTo(2)
        assertEquals(setOf(20, 21), r.linesOfCall())
    }

    @Test
    fun threadsAreReplayedOneAtATime() {
        val r = replay { hit("work", 30, 1, "k" to "1", thread = 2) }
        assertEquals(1L, r.thread)
        r.selectThread(2)
        assertEquals(1, r.stopCount)
        assertEquals("work", r.current.site.methodName)
    }

    @Test
    fun whereAValueCameFrom() {
        val r = replay()
        r.goTo(5)
        assertEquals("line 10 set x", 0, r.lastChange("x")!!.seq)
        assertEquals("line 11 brought y in", 1, r.lastChange("y")!!.seq)
        r.goTo(3)
        assertEquals("a parameter: the call never changed it", null, r.lastChange("a"))
        r.goTo(6)
        assertEquals(7, r.nextChange("s")!!.seq)
        r.goTo(10)
        assertEquals(null, r.nextChange("s"))
    }

    @Test
    fun historyOfAVariableInACall() {
        val r = replay()
        r.goTo(10)
        val main = r.callOf(r.current)
        assertEquals(listOf("0", "5", "10") to 2, r.history(main, "s", 10))
        assertEquals(listOf("0", "1", "2") to 1, r.history(main, "i", 8))
        assertEquals("whole call, no cursor", listOf("0", "1", "2") to null, r.history(main, "i", null))
    }

    @Test
    fun callListInOrderWithDepth() {
        val r = replay()
        assertEquals(listOf("main" to 0, "f" to 1), r.callList().map { (call, level) -> call.method.methodName to level })
    }
}
