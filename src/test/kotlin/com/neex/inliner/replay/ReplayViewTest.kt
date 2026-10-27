package com.neex.inliner.replay

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.xdebugger.ui.DebuggerColors
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue

class ReplayViewTest : LightJavaCodeInsightFixtureTestCase() {

    private val source = """
        package p;
        public class A {
            int run(int n) {
                int total = 0;
                for (int i = 0; i < n; i++) {
                    total += i;
                }
                if (total > 100) {
                    total = 100;
                }
                return total;
            }
        }
    """.trimIndent()

    private fun recording(): Recording {
        val hits = ArrayList<Hit>()
        fun hit(line: Int, vararg values: Pair<String, String>, returns: Boolean = false) {
            hits += Hit(hits.size, 1, Site("p.A", "run", "(I)I", line, "p/A.java"), !returns, returns, 2, values.map { VarValue(it.first, it.second) })
        }
        hit(4, "n" to "2")
        hit(5, "n" to "2", "total" to "0")
        hit(6, "n" to "2", "total" to "0", "i" to "0")
        hit(5, "n" to "2", "total" to "0", "i" to "1")
        hit(6, "n" to "2", "total" to "0", "i" to "1")
        hit(5, "n" to "2", "total" to "1", "i" to "2")
        hit(8, "n" to "2", "total" to "1", "this" to "A#1")
        hit(11, "n" to "2", "total" to "1")
        hit(11, "n" to "2", "total" to "1", returns = true)
        return Recording(mapOf(1L to "main"), hits, emptySet(), false)
    }

    private fun open(): Pair<ReplayService, Replay> {
        myFixture.addFileToProject("p/A.java", source)
        val service = ReplayService.of(project)
        val replay = Replay(recording())
        val sources = com.intellij.openapi.application.ReadAction.compute<Map<String, com.intellij.openapi.vfs.VirtualFile>, Throwable> { ReplayService.sourcesOf(project, replay) }
        service.start(replay, "test", sources)
        return service to replay
    }

    override fun tearDown() {
        try {
            ReplayService.of(project).close()
        } finally {
            super.tearDown()
        }
    }

    fun testCursorOpensTheSourceAtItsLine() {
        val (service, replay) = open()
        assertTrue(replay.moveTo(replay.recording.hits[6]))
        service.move(Step.BACK_INTO, null)
        service.move(Step.INTO, null)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("A.java", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertEquals(7, editor.caretModel.logicalPosition.line)
        val view = service.currentView!!
        val execution = view.highlighters().single { it.textAttributesKey == DebuggerColors.EXECUTIONPOINT_ATTRIBUTES }
        assertEquals(7, editor.document.getLineNumber(execution.startOffset))
    }

    fun testValuesAtLineEndsAndTheCursorLine() {
        val (service, replay) = open()
        replay.moveTo(replay.recording.hits[6])
        service.move(Step.BACK_INTO, null)
        service.move(Step.INTO, null)
        assertEquals(
            mapOf(3 to "total = 0", 5 to "total = 1, i = 2", 7 to "n = 2, total = 1"),
            service.currentView!!.inlayTexts(),
        )
    }

    fun testLinesThisCallNeverRanAreGreyed() {
        val (service, replay) = open()
        replay.moveTo(replay.recording.hits[6])
        service.move(Step.INTO, null)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        val grey = service.currentView!!.highlighters().filter { it.textAttributesKey != DebuggerColors.EXECUTIONPOINT_ATTRIBUTES }
        assertEquals(listOf("total = 100;"), grey.map { editor.document.getText(it.textRange) })
    }

    fun testCloseRemovesEverything() {
        val (service, _) = open()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertTrue(editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).isNotEmpty())
        service.close()
        assertTrue(editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).isEmpty())
        assertFalse(service.isOpen)
    }
}
