package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.InlineExpansionService
import com.neex.inliner.Keys

class FocusFramesTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class H {
            void caller() {
                int value = 5;
                int unrelated = 7;
                compute(value);
            }
            int compute(int x) {
                int doubled = twice(x);
                return doubled;
            }
            int twice(int y) { return y * 2; }
        }
    """.trimIndent()

    private fun set() = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!

    private fun moveTo(text: String, delta: Int = 1) {
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(text) + delta)
    }

    private fun trackValue() {
        moveTo("value = 5")
        FocusService.toggleAtCaret(myFixture.editor, project)
    }

    private fun expandCompute() {
        moveTo("compute(value)")
        InlineExpansionService.toggle(myFixture.editor, project)
    }

    fun testTrackThenExpandDerivesCalleeParam() {
        myFixture.configureByText("H.java", sample)
        trackValue()
        expandCompute()

        val set = set()
        assertEquals(2, set.entries.size)
        val root = set.entries.first { !it.derived }
        val derived = set.entries.first { it.derived }
        assertEquals("value", root.name)
        assertEquals("x", derived.name)
        assertSame(root, derived.parent)
        assertEquals(root.colorIndex, derived.colorIndex)
        assertEquals("compute", derived.scopePtr.element?.name)

        val node = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots[0]
        assertSame(node, derived.sourceNode)
        assertTrue(
            "fragment editor should be painted",
            FocusHighlighter.ourHighlighters(node.fragmentEditor).isNotEmpty(),
        )
    }

    fun testExpandFirstThenTrackAlsoPropagates() {
        myFixture.configureByText("H.java", sample)
        expandCompute()
        trackValue()
        assertEquals(2, set().entries.size)
        assertTrue(set().entries.any { it.derived && it.name == "x" })
    }

    fun testChainThroughNestedFrames() {
        myFixture.configureByText("H.java", sample)
        trackValue()
        expandCompute()
        val node = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots[0]
        val frag = node.fragmentEditor
        frag.caretModel.moveToOffset(frag.document.text.indexOf("twice(x)") + 1)
        InlineExpansionService.toggle(frag, project)

        val names = set().entries.map { it.name }.toSet()
        assertEquals(setOf("value", "x", "y"), names)
        assertTrue(set().entries.all { it.colorIndex == 0 })
    }

    fun testCollapseRemovesDerivedKeepsRoot() {
        myFixture.configureByText("H.java", sample)
        trackValue()
        expandCompute()
        val node = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots[0]
        InlineExpansionService.collapse(node, focus = true)

        val entries = set().entries
        assertEquals(1, entries.size)
        assertEquals("value", entries[0].name)
        assertFalse(entries[0].derived)
    }

    fun testRemovingRootRemovesDerivedChain() {
        myFixture.configureByText("H.java", sample)
        trackValue()
        expandCompute()
        val set = set()
        val root = set.entries.first { !it.derived }
        FocusService.remove(set, root)
        assertTrue(set.entries.isEmpty())
    }

    fun testEditInsideAFrameRefreshesFocusAfterTheDebounce() {
        // the callee lives in another file: its document is not the host's
        myFixture.addFileToProject("C.java", "class C {\n    static int compute(int x) {\n        return x;\n    }\n}")
        myFixture.configureByText(
            "H.java",
            "class H {\n    void caller() {\n        int value = 5;\n        C.compute(value);\n    }\n}",
        )
        trackValue()
        moveTo("compute(value)")
        InlineExpansionService.toggle(myFixture.editor, project)
        val frame = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots[0].fragmentEditor
        val before = FocusHighlighter.ourHighlighters(frame).size
        assertTrue("x is painted in the frame", before > 0)

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            frame.document.insertString(frame.document.text.indexOf("return x"), "int y = x + x;\n        ")
        }
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(
            "the two new x are painted once the debounce has passed",
            { FocusHighlighter.ourHighlighters(frame).size >= before + 2 },
            5,
        )
    }
}
