package com.neex.inliner.focus

import com.intellij.openapi.editor.markup.EffectType
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusAccessTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class K {
            int run(int p) {
                int a;
                a = p;
                int b = a + 1;
                a++;
                a += b;
                System.out.println(a);
                if (b > 0) {
                    a = 0;
                }
                int noise = 1;
                return a;
            }
        }
    """.trimIndent()

    override fun tearDown() {
        try {
            FocusSettings.getInstance().loadState(FocusSettings.Options())
        } finally {
            super.tearDown()
        }
    }

    private val doc get() = myFixture.editor.document

    private fun lineOf(text: String): Int = doc.getLineNumber(doc.text.indexOf(text))

    private fun track(text: String, transitive: Boolean = false): FocusSet {
        myFixture.configureByText("K.java", sample)
        myFixture.editor.caretModel.moveToOffset(doc.text.indexOf(text))
        FocusService.toggleAtCaret(myFixture.editor, project)
        val set = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!
        set.transitive = transitive
        FocusService.refreshAll(set)
        return set
    }

    private fun picture(set: FocusSet) = FocusPicture.of(set, myFixture.file, doc)

    private fun fold(set: FocusSet, rule: FoldRule) {
        set.foldRule = rule
        set.foldToFocus = true
        FocusService.refreshAll(set)
    }

    private fun folded(line: Int): Boolean {
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        return FocusFolder.ourRegions(myFixture.editor).any { !it.isExpanded && it.startOffset <= start && end <= it.endOffset }
    }

    fun testEveryOccurrenceKnowsItsAccess() {
        val set = track("a;")
        val byLine = picture(set).touches.groupBy({ doc.getLineNumber(it.range.startOffset) }, { it.access })

        assertEquals(listOf(Access.DECLARATION), byLine[lineOf("int a;")])
        assertEquals(listOf(Access.WRITE), byLine[lineOf("a = p;")])
        assertEquals(listOf(Access.READ), byLine[lineOf("int b = a + 1")])
        assertEquals(listOf(Access.READ_WRITE), byLine[lineOf("a++")])
        assertEquals(listOf(Access.READ_WRITE), byLine[lineOf("a += b")])
        assertEquals(listOf(Access.READ), byLine[lineOf("println(a)")])
        assertEquals(listOf(Access.WRITE), byLine[lineOf("a = 0")])
    }

    fun testDeclarationWithAValueIsAWrite() {
        val set = track("b = a")
        val decl = picture(set).touches.single { doc.getLineNumber(it.range.startOffset) == lineOf("int b = a + 1") }
        assertEquals(Access.WRITE, decl.access)
    }

    fun testWritesRuleKeepsOnlyLinesThatChangeTheValue() {
        val set = track("a;")
        fold(set, FoldRule.WRITES)

        for (write in listOf("a = p;", "a++", "a += b", "a = 0")) {
            assertFalse("write '$write' must stay", folded(lineOf(write)))
        }
        assertFalse("header of a kept write stays", folded(lineOf("if (b > 0)")))
        for (read in listOf("int a;", "int b = a + 1", "println(a)", "int noise")) {
            assertTrue("'$read' doesn't change a: folds", folded(lineOf(read)))
        }
    }

    fun testTouchesRuleKeepsReadsToo() {
        val set = track("a;")
        fold(set, FoldRule.TOUCHES)
        assertFalse(folded(lineOf("println(a)")))
        assertFalse(folded(lineOf("int b = a + 1")))
        assertTrue(folded(lineOf("int noise")))
    }

    fun testNoRuleEverFoldsALineItKeeps() {
        for (transitive in listOf(false, true)) {
            for (rule in FoldRule.entries) {
                val set = track("p)", transitive)
                fold(set, rule)
                val picture = picture(set)
                for ((line, touches) in picture.byLine) {
                    if (rule.keeps(touches)) assertFalse("$rule transitive=$transitive folded line $line", folded(line))
                }
            }
        }
    }

    fun testTransitiveWritesFollowTheValue() {
        val set = track("p)", transitive = true)
        val writes = picture(set).touches.filter { it.access.writes }
        val lines = writes.map { doc.getLineNumber(it.range.startOffset) }.toSet()
        assertTrue("p flows into a here", lineOf("a = p;") in lines)
        assertTrue("and on into b", lineOf("int b = a + 1") in lines)
        assertTrue(writes.all { it.relation == Relation.DOWNSTREAM })
    }

    fun testNextRuleActionCyclesAndFolds() {
        val set = track("a;")
        assertEquals(FoldRule.TOUCHES, set.foldRule)
        myFixture.performEditorAction("com.neex.inliner.FocusFoldRule")
        assertEquals(FoldRule.WRITES, set.foldRule)
        assertTrue(set.foldToFocus)
        assertTrue(folded(lineOf("println(a)")))
        myFixture.performEditorAction("com.neex.inliner.FocusFoldRule")
        assertEquals(FoldRule.TOUCHES, set.foldRule)
    }

    fun testNewViewStartsFromTheSettingsDefault() {
        FocusSettings.getInstance().state.foldRule = FoldRule.WRITES
        val set = track("a;")
        assertEquals(FoldRule.WRITES, set.foldRule)
    }

    fun testWritesAreBoxedUnlessTurnedOff() {
        val set = track("a;")
        fun boxed() = FocusHighlighter.ourHighlighters(myFixture.editor)
            .filter { it.getTextAttributes(null)?.effectType == EffectType.ROUNDED_BOX }
            .map { doc.getLineNumber(it.startOffset) }
            .toSet()
        assertEquals(setOf("a = p;", "a++", "a += b", "a = 0").map(::lineOf).toSet(), boxed())

        FocusSettings.getInstance().state.markWrites = false
        FocusService.refreshAll(set)
        assertTrue(boxed().isEmpty())
    }
}
