package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusFlowTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class T {
            int field;
            void caller() {
                int value = 2;
                int noise = 9;
                int r = compute(value);
                int shout = r + 1;
                System.out.println(shout);
            }
            int compute(int x) {
                int result = twice(x) + 1;
                int junk = 3;
                return result + junk;
            }
            int twice(int y) { return y * 2; }
        }
    """.trimIndent()

    private fun track(text: String, delta: Int = 1) {
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(text) + delta)
        FocusService.toggleAtCaret(myFixture.editor, project)
    }

    private fun set() = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!

    private fun touches(): FocusPicture =
        FocusPicture.of(set(), myFixture.file, myFixture.editor.document)

    private fun lineOf(text: String): Int =
        myFixture.editor.document.getLineNumber(myFixture.editor.document.text.indexOf(text))

    fun testForwardChainThroughAssignments() {
        myFixture.configureByText("T.java", sample)
        track("value = 2")
        set().transitive = true

        val t = touches()
        assertTrue("direct: declaration", lineOf("int value = 2") in t.directLines)
        assertTrue("direct: call arg", lineOf("int r = compute(value)") in t.directLines)
        assertTrue("propagated: r flows to shout", lineOf("int shout = r + 1") in t.propagatedLines)
        assertTrue("propagated: shout printed", lineOf("System.out.println(shout)") in t.propagatedLines)
        assertFalse("unrelated stays dark", lineOf("int noise = 9") in t.byLine.keys)
    }

    fun testReturnLineReachedThroughDerivedVariable() {
        // the user's case: tracking x must reach `return result + junk;` because x → result → return
        myFixture.configureByText("T.java", sample)
        track("x) {", 0)
        set().transitive = true

        val t = touches()
        assertTrue("return line must be reached via result", lineOf("return result + junk") in t.propagatedLines)
        assertTrue(lineOf("int result = twice(x)") in t.directLines)
        assertFalse("junk's declaration is not part of the flow", lineOf("int junk = 3") in t.byLine.keys)
    }

    fun testBackwardContributors() {
        myFixture.configureByText("T.java", sample)
        track("shout = r + 1")
        set().transitive = true

        val t = touches()
        assertTrue("r writes into shout", lineOf("int r = compute(value)") in t.propagatedLines)
        assertTrue("value writes into r writes into shout", lineOf("int value = 2") in t.propagatedLines)
        assertFalse(lineOf("int noise = 9") in t.byLine.keys)
    }

    fun testToggleOffIsDirectOnly() {
        myFixture.configureByText("T.java", sample)
        track("value = 2")
        assertFalse(set().transitive)

        val t = touches()
        assertTrue(t.propagatedLines.isEmpty())
        assertFalse(lineOf("int shout = r + 1") in t.byLine.keys)
    }

    fun testPropagatedMarksAreFlaggedNotDirect() {
        myFixture.configureByText("T.java", sample)
        track("value = 2")
        set().transitive = true

        val t = touches()
        val directNames = t.touches.filter { it.direct }.map { rangeText(it) }.toSet()
        val propagated = t.touches.filter { !it.direct }.map { rangeText(it) }.toSet()
        assertTrue("value" in directNames)
        assertTrue("r" in propagated)
        assertTrue("shout" in propagated)
        assertFalse("value" in propagated)
    }

    private fun rangeText(touch: Touch): String =
        myFixture.editor.document.getText(touch.range)
}
