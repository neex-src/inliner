package com.neex.inliner.replay

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.FrameRows
import com.neex.inliner.InlineExpansionService
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue

/** A frame under a call shows that call's recorded run. */
class ReplayFramesTest : LightJavaCodeInsightFixtureTestCase() {

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
        hit("caller", 4)                                           // 0
        hit("caller", 5, "a" to "1"); twice(1)                     // 1, 2-5
        hit("caller", 6, "a" to "1", "r" to "2")                   // 6
        hit("caller", 7, "a" to "1", "r" to "2", "k" to "0"); twice(0)   // 7, 8-11
        hit("caller", 6, "a" to "1", "r" to "2", "k" to "1")       // 12
        hit("caller", 7, "a" to "1", "r" to "2", "k" to "1"); twice(1)   // 13, 14-17
        hit("caller", 6, "a" to "1", "r" to "4", "k" to "2")       // 18
        hit("caller", 9, "a" to "1", "r" to "4")                   // 19
        hit("caller", 9, "a" to "1", "r" to "4", returns = true)   // 20
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
        replay.moveTo(replay.recording.hits[seq])
        service.move(Step.BACK_INTO, null)
        service.move(Step.INTO, null)
        assertEquals(seq, replay.current.seq)
    }

    /** Expands the call [text] in the host and returns the frame's editor. */
    private fun expand(text: String): Editor {
        val editor = host
        editor.caretModel.moveToOffset(editor.document.text.indexOf(text) + 1)
        InlineExpansionService.toggle(editor, project)
        return FrameRows.framesIn(editor).single().fragmentEditor
    }

    private fun line(text: String) = host.document.text.lines().indexOfFirst { text in it }

    fun testFrameShowsTheCallMadeFromItsLine() {
        cursorAt(6)
        val frame = expand("twice(a)")
        val view = service.currentView!!
        assertEquals(
            mapOf(line("int twice(int x)") to "x = 1", line("int y = x * 2") to "y = 2"),
            view.inlayTexts(frame),
        )
        val grey = view.highlighters(frame).map { frame.document.getText(it.textRange) }
        assertEquals(listOf("y = 100;"), grey)
    }

    fun testInALoopTheCallUpToTheCursor() {
        cursorAt(12)
        val frame = expand("twice(k)")
        assertEquals("x = 0", service.currentView!!.inlayTexts(frame)[line("int twice(int x)")])
        service.move(Step.INTO, null)
        assertEquals("the line about to make the call shows that call", 13, replay.current.seq)
        val again = FrameRows.framesIn(host).single().fragmentEditor
        assertEquals("x = 1", service.currentView!!.inlayTexts(again)[line("int twice(int x)")])
    }

    fun testCollapseAndCloseLeaveNothing() {
        cursorAt(6)
        val frame = expand("twice(a)")
        assertTrue(service.currentView!!.inlayTexts(frame).isNotEmpty())
        InlineExpansionService.collapse(FrameRows.framesIn(host).single(), focus = true)
        assertTrue(frame.isDisposed)
        assertTrue("the host is repainted", service.currentView!!.inlayTexts().isNotEmpty())
        service.close()
        assertTrue(host.inlayModel.getAfterLineEndElementsInRange(0, host.document.textLength).isEmpty())
    }

    fun testReplayKeysWorkWithTheCaretInAFrame() {
        cursorAt(6)
        val frame = expand("twice(a)")
        assertTrue("a frame of the painted editor is part of the run", ReplayAction.live(project, frame))
        val stranger = com.intellij.openapi.editor.EditorFactory.getInstance().createViewer(frame.document, project)
        try {
            assertFalse("an editor that is not a frame of it is not", ReplayAction.live(project, stranger))
        } finally {
            com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(stranger)
        }
        frame.caretModel.moveToOffset(frame.document.text.indexOf("return y;") + 3)
        ReplayToLineAction().perform(service, frame)
        assertEquals("the next run of the frame's line", 10, replay.current.seq)
        frame.caretModel.moveToOffset(frame.document.text.indexOf("int y = x") + 5)
        assertEquals("y", variableAtCaret(frame))
    }
}
