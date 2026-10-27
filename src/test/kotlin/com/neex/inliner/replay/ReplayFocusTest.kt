package com.neex.inliner.replay

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.focus.FocusService
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue

/** The replay with focus: histories of tracked variables, where a value came from, folding what didn't run. */
class ReplayFocusTest : LightJavaCodeInsightFixtureTestCase() {

    private val source = """
        package p;
        public class B {
            int caller() {
                int a = 1;
                int r = twice(a);
                for (int k = 0; k < 2; k++) {
                    r += twice(k);
                }
                return r;
            }
            int twice(int x) {
                int y = x * 2;
                if (y > 100) {
                    y = 100;
                }
                return y;
            }
        }
    """.trimIndent()

    private fun recording(): Recording {
        val hits = ArrayList<Hit>()
        fun hit(method: String, line: Int, vararg values: Pair<String, String>, returns: Boolean = false) {
            val depth = if (method == "caller") 2 else 3
            hits += Hit(hits.size, 1, Site("p.B", method, "(I)I", line, "p/B.java"), !returns, returns, depth, values.map { VarValue(it.first, it.second) })
        }
        fun twice(x: Int) {
            hit("twice", 12, "x" to "$x")
            hit("twice", 13, "x" to "$x", "y" to "${x * 2}")
            hit("twice", 16, "x" to "$x", "y" to "${x * 2}")
            hit("twice", 16, "x" to "$x", "y" to "${x * 2}", returns = true)
        }
        hit("caller", 4)                                                 // 0
        hit("caller", 5, "a" to "1"); twice(1)                           // 1, 2-5
        hit("caller", 6, "a" to "1", "r" to "2")                         // 6
        hit("caller", 7, "a" to "1", "r" to "2", "k" to "0"); twice(0)   // 7, 8-11
        hit("caller", 6, "a" to "1", "r" to "2", "k" to "1")             // 12
        hit("caller", 7, "a" to "1", "r" to "2", "k" to "1"); twice(1)   // 13, 14-17
        hit("caller", 6, "a" to "1", "r" to "4", "k" to "2")             // 18
        hit("caller", 9, "a" to "1", "r" to "4")                         // 19
        hit("caller", 9, "a" to "1", "r" to "4", returns = true)         // 20
        return Recording(mapOf(1L to "main"), hits, emptySet(), false)
    }

    private lateinit var replay: Replay
    private val service get() = ReplayService.of(project)
    private val host: Editor get() = FileEditorManager.getInstance(project).selectedTextEditor!!

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("p/B.java", source)
        replay = Replay(recording())
        val sources = ReadAction.compute<Map<String, VirtualFile>, Throwable> { ReplayService.sourcesOf(project, replay) }
        service.start(replay, "test", sources)
    }

    override fun tearDown() {
        try {
            service.close()
        } finally {
            super.tearDown()
        }
    }

    private fun cursorAt(seq: Int) {
        service.moveTo(replay.recording.hits[seq])
        assertEquals(seq, replay.current.seq)
    }

    private fun line(text: String) = host.document.text.lines().indexOfFirst { text in it }

    fun testTrackedVariableShowsItsHistory() {
        cursorAt(13)
        host.caretModel.moveToOffset(host.document.text.indexOf("int r =") + 4)
        FocusService.toggleAtCaret(host, project)
        assertEquals("what line 5 did, then r's history with the value now in brackets", "r = 2 | r: [2] → 4", service.currentView!!.inlayTexts()[line("int r =")])
        service.move(Step.OVER, null)
        assertEquals(18, replay.current.seq)
        assertEquals("r = 2 | r: 2 → [4]", service.currentView!!.inlayTexts()[line("int r =")])
    }

    fun testWhereTheValueCameFrom() {
        cursorAt(19)
        host.caretModel.moveToOffset(host.document.text.indexOf("return r;") + 7)
        service.toChange("r", forward = false, host)
        assertEquals("the line that made it 4", 13, replay.current.seq)
        service.toChange("r", forward = false, host)
        assertEquals(1, replay.current.seq)
    }

    fun testParameterComesFromTheCallSite() {
        cursorAt(3)
        service.toChange("x", forward = false, host)
        assertEquals("the line that called twice(a)", 1, replay.current.seq)
    }

    fun testFoldWhatDidntRun() {
        cursorAt(3)
        service.toggleFold()
        val folds = service.currentView!!.folds()
        assertEquals(listOf("y = 100;"), folds.map { host.document.getText(it.textRange) })
        assertEquals("⋯ didn't run", folds.single().placeholderText)
        service.move(Step.INTO, null)
        assertEquals("kept while moving", 1, service.currentView!!.folds().size)
        service.toggleFold()
        assertTrue(service.currentView!!.folds().isEmpty())
    }
}
