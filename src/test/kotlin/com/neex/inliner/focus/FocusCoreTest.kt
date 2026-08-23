package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusCoreTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class F {
            int field1;
            int work(int alpha, int beta) {
                int local1 = alpha + 1;
                int other = beta * 2;
                if (local1 > 0) {
                    other += local1;
                }
                return other + field1;
            }
        }
    """.trimIndent()

    private fun set() = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)

    private fun lineOf(text: String): Int =
        myFixture.editor.document.getLineNumber(myFixture.editor.document.text.indexOf(text))

    private fun highlightedLines(): Set<Int> {
        val doc = myFixture.editor.document
        return FocusHighlighter.ourHighlighters(myFixture.editor)
            .filter { it.targetArea == com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE }
            .map { doc.getLineNumber(it.startOffset) }
            .toSet()
    }

    fun testToggleParamTracksAndHighlights() {
        myFixture.configureByText("F.java", sample.replace("alpha + 1", "al<caret>pha + 1"))
        FocusService.toggleAtCaret(myFixture.editor, project)

        val set = set()
        assertNotNull(set)
        assertEquals(1, set!!.entries.size)
        val entry = set.entries[0]
        assertEquals("alpha", entry.name)
        assertEquals(FocusKind.PARAMETER, entry.kind)
        assertEquals("work", entry.scopePtr.element?.name)

        val lines = highlightedLines()
        assertTrue("declaration line should be lit", lineOf("int work(") in lines)
        assertTrue("usage line should be lit", lineOf("int local1") in lines)
        assertFalse("untouched line must not be lit", lineOf("int other = beta") in lines)
    }

    fun testToggleTwiceRemoves() {
        myFixture.configureByText("F.java", sample.replace("alpha + 1", "al<caret>pha + 1"))
        FocusService.toggleAtCaret(myFixture.editor, project)
        FocusService.toggleAtCaret(myFixture.editor, project)
        assertTrue(set()!!.entries.isEmpty())
        assertTrue(FocusHighlighter.ourHighlighters(myFixture.editor).isEmpty())
    }

    fun testLocalFromDeclarationIdentifier() {
        myFixture.configureByText("F.java", sample.replace("int local1 =", "int loc<caret>al1 ="))
        FocusService.toggleAtCaret(myFixture.editor, project)
        val entry = set()!!.entries.single()
        assertEquals("local1", entry.name)
        assertEquals(FocusKind.LOCAL, entry.kind)
        val lines = highlightedLines()
        assertTrue(lineOf("if (local1") in lines)
        assertTrue(lineOf("other += local1") in lines)
    }

    fun testFieldAtCaretIsRejected() {
        myFixture.configureByText("F.java", sample.replace("other + field1", "other + fie<caret>ld1"))
        FocusService.toggleAtCaret(myFixture.editor, project)
        assertTrue(set()?.entries.isNullOrEmpty())
    }

    fun testTwoSymbolsGetDistinctColorsAndSharedLineExists() {
        myFixture.configureByText("F.java", sample.replace("alpha + 1", "al<caret>pha + 1"))
        FocusService.toggleAtCaret(myFixture.editor, project)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("beta * 2") + 1)
        FocusService.toggleAtCaret(myFixture.editor, project)

        val entries = set()!!.entries
        assertEquals(2, entries.size)
        assertEquals(setOf(0, 1), entries.map { it.colorIndex }.toSet())
        // both params share the signature line
        assertTrue(lineOf("int work(") in highlightedLines())
    }

    fun testDisabledEntryNotHighlighted() {
        myFixture.configureByText("F.java", sample.replace("alpha + 1", "al<caret>pha + 1"))
        FocusService.toggleAtCaret(myFixture.editor, project)
        val set = set()!!
        set.entries[0].enabled = false
        FocusService.refreshAll(set)
        assertTrue(FocusHighlighter.ourHighlighters(myFixture.editor).isEmpty())
    }
}
