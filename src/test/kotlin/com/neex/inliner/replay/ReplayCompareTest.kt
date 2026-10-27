package com.neex.inliner.replay

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.recorder.Change
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue

/** A what-if replayed beside its original: what went differently, painted. */
class ReplayCompareTest : LightJavaCodeInsightFixtureTestCase() {

    private val source = """
        package p;
        public class C {
            int f(int items) {
                boolean bulk = items >= 3;
                int p = 0;
                if (bulk) {
                    p = 5;
                }
                int total = p * 10;
                return total;
            }
        }
    """.trimIndent()

    private fun run(bulk: Boolean): Recording {
        val hits = ArrayList<Hit>()
        val site = { line: Int -> Site("p.C", "f", "(I)I", line, "p/C.java") }
        fun hit(line: Int, vararg values: Pair<String, String>, returns: Boolean = false) {
            hits += Hit(hits.size, 1, site(line), !returns, returns, 2, values.map { VarValue(it.first, it.second) })
        }
        val p = if (bulk) "5" else "0"
        hit(5, "items" to "4", "bulk" to "$bulk")
        hit(6, "items" to "4", "bulk" to "$bulk", "p" to "0")
        if (bulk) hit(7, "items" to "4", "bulk" to "$bulk", "p" to "0")
        hit(9, "items" to "4", "bulk" to "$bulk", "p" to p)
        hit(10, "items" to "4", "bulk" to "$bulk", "p" to p, "total" to "${p.toInt() * 10}")
        hit(10, "items" to "4", "bulk" to "$bulk", "p" to p, "total" to "${p.toInt() * 10}", returns = true)
        return Recording(mapOf(1L to "main"), hits, emptySet(), false, site(5), if (bulk) null else Change("bulk", "true", "false"))
    }

    private val service get() = ReplayService.of(project)
    private val shown: Editor get() = FileEditorManager.getInstance(project).selectedTextEditor!!
    private lateinit var variant: Replay
    private lateinit var original: Replay

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("p/C.java", source)
        variant = Replay(run(bulk = false))
        original = Replay(run(bulk = true))
        val sources = ReadAction.compute<Map<String, VirtualFile>, Throwable> { ReplayService.sourcesOf(project, variant) }
        service.startCompared(variant, original, "what-if", sources)
    }

    override fun tearDown() {
        try {
            service.close()
        } finally {
            super.tearDown()
        }
    }

    private fun line(text: String) = shown.document.text.lines().indexOfFirst { text in it }

    private fun linesColored(key: com.intellij.openapi.editor.colors.TextAttributesKey) =
        service.currentView!!.highlighters().filter { it.textAttributesKey == key }.map { shown.document.getLineNumber(it.startOffset) }

    fun testWhatTheWhatIfDidDifferently() {
        service.moveTo(variant.recording.hits.last { it.startsLine })
        assertEquals("only the original ran the branch", listOf(line("p = 5;")), linesColored(DiffColors.DIFF_DELETED))
        assertTrue(linesColored(DiffColors.DIFF_INSERTED).isEmpty())
        val texts = service.currentView!!.inlayTexts()
        assertEquals("total isn't changed until the line runs; p kept its 0", "p = 0", texts[line("int p = 0")])
        assertTrue(texts[line("int total")]!!, texts[line("int total")]!!.startsWith("total = 0 (was 50)"))
    }

    fun testWhereTheRunsPart() {
        service.toDivergence(shown)
        assertEquals("the what-if goes from the if straight to line 9", 9, variant.current.site.line)
        val there = service.currentView!!.inlayTexts()[line("int total")]!!
        assertTrue(there, "⇠ the runs part here: the original was at line 7" in there)
    }

    fun testTheOriginalSideShowsTheOtherWayRound() {
        service.moveTo(variant.recording.hits.last { it.startsLine })
        service.toggleCompared()
        assertSame(original, service.replay)
        assertEquals(listOf(line("p = 5;")), linesColored(DiffColors.DIFF_INSERTED))
        assertEquals("on the same line in the original", 10, original.current.site.line)
        assertTrue(service.currentView!!.inlayTexts()[line("int total")]!!.startsWith("total = 50 (what-if 0)"))
        service.toggleCompared()
        assertSame(variant, service.replay)
    }
}
