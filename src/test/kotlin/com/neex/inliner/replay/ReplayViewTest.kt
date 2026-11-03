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

    private fun recording(exhausted: Set<Site> = emptySet()): Recording {
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
        return Recording(mapOf(1L to "main"), hits, exhausted, false)
    }

    private fun open(exhausted: Set<Site> = emptySet()): Pair<ReplayService, Replay> {
        myFixture.addFileToProject("p/A.java", source)
        val service = ReplayService.of(project)
        val replay = Replay(recording(exhausted))
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

    fun testFoldingFoldsWhatThisCallNeverRan() {
        val (service, replay) = open()
        replay.moveTo(replay.recording.hits[6])
        service.move(Step.INTO, null)
        if (!service.foldNotRun) service.toggleFold()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(listOf("total = 100;"), service.currentView!!.folds().map { editor.document.getText(it.textRange) })
    }

    fun testLinesThatRanOutOfBudgetAreUnknownNotGreyedNorFolded() {
        // `total = 100;` (line 9) was cut off by the budget: this call may well have run it
        val cut = setOf(Site("p.A", "run", "(I)I", 9, "p/A.java"))
        val (service, replay) = open(cut)
        replay.moveTo(replay.recording.hits[6])
        service.move(Step.INTO, null)
        if (!service.foldNotRun) service.toggleFold()
        val view = service.currentView!!
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(emptyList<String>(), view.highlighters().filter { it.textAttributesKey != DebuggerColors.EXECUTIONPOINT_ATTRIBUTES }.map { editor.document.getText(it.textRange) })
        assertTrue(view.folds().isEmpty())
        assertEquals(setOf(9), replay.unknownLines(replay.callOf(replay.current)))
        assertTrue("a line the call did run is not unknown", 9 !in replay.linesOf(replay.callOf(replay.current)))
    }

    fun testCloseRemovesEverything() {
        val (service, _) = open()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertTrue(editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).isNotEmpty())
        service.close()
        assertTrue(editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).isEmpty())
        assertFalse(service.isOpen)
    }

    fun testAClosedReplayLeavesNothingFoldedBehind() {
        val (service, replay) = open()
        replay.moveTo(replay.recording.hits[6])
        service.move(Step.INTO, null)
        service.toggleFold()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("the call's unrun lines are folded", 1, editor.foldingModel.allFoldRegions.size)

        service.close()
        assertTrue("its folds go with it", editor.foldingModel.allFoldRegions.isEmpty())
        assertFalse("and so does its fold toggle: the next replay starts unfolded", service.foldNotRun)

        val second = Replay(recording())
        val sources = com.intellij.openapi.application.ReadAction.compute<Map<String, com.intellij.openapi.vfs.VirtualFile>, Throwable> { ReplayService.sourcesOf(project, second) }
        val again = service
        again.start(second, "again", sources)
        second.moveTo(second.recording.hits[6])
        again.move(Step.INTO, null)
        assertTrue("a new replay shows the lines it didn't run, greyed, not folded", again.currentView!!.folds().isEmpty())
        assertTrue(editor.foldingModel.allFoldRegions.isEmpty())
    }
}
