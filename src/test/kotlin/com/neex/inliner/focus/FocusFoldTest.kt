package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusFoldTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class G {
            int run(int alpha) {
                int a = al<caret>pha + 1;
                int noise1 = 1;
                int noise2 = 2;
                int noise3 = 3;
                if (a > 0) {
                    noise1 += 2;
                    noise2 += alpha;
                }
                int noise4 = 4;
                return noise4;
            }
        }
    """.trimIndent()

    private fun lineOf(text: String): Int =
        myFixture.editor.document.getLineNumber(myFixture.editor.document.text.indexOf(text))

    private fun lineFolded(line: Int): Boolean {
        val doc = myFixture.editor.document
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        return FocusFolder.ourRegions(myFixture.editor)
            .any { !it.isExpanded && it.startOffset <= start && end <= it.endOffset }
    }

    private fun trackAlphaAndFold(): FocusSet {
        myFixture.configureByText("G.java", sample)
        FocusService.toggleAtCaret(myFixture.editor, project)
        val set = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!
        set.foldToFocus = true
        FocusService.refreshAll(set)
        return set
    }

    fun testUntouchedRunsAreFoldedTouchedAndHeadersAreNot() {
        trackAlphaAndFold()
        assertTrue(FocusFolder.ourRegions(myFixture.editor).isNotEmpty())

        assertTrue("noise run should fold", lineFolded(lineOf("int noise1 = 1")))
        assertTrue(lineFolded(lineOf("int noise3 = 3")))
        assertTrue("untouched line inside if should fold", lineFolded(lineOf("noise1 += 2")))
        assertTrue("trailing noise should fold", lineFolded(lineOf("int noise4 = 4")))

        assertFalse("touched line must stay", lineFolded(lineOf("int a = alpha + 1")))
        assertFalse("touched line inside if must stay", lineFolded(lineOf("noise2 += alpha")))
        assertFalse("control-flow header above a touched line must stay", lineFolded(lineOf("if (a > 0)")))
        assertFalse("signature must stay", lineFolded(lineOf("int run(int alpha)")))
    }

    fun testToggleOffRemovesOurRegionsOnly() {
        val set = trackAlphaAndFold()
        set.foldToFocus = false
        FocusService.refreshAll(set)
        assertTrue(FocusFolder.ourRegions(myFixture.editor).isEmpty())
        assertFalse(lineFolded(lineOf("int noise1 = 1")))
    }

    fun testRemovingLastEntryDropsFolds() {
        trackAlphaAndFold()
        FocusService.toggleAtCaret(myFixture.editor, project) // untrack alpha
        assertTrue(FocusFolder.ourRegions(myFixture.editor).isEmpty())
    }

    private val flowSample = """
        class W {
            void caller() {
                int va<caret>lue = 2;
                int noise = 9;
                int r = grow(value);
                System.out.println(r);
            }
            int grow(int x) {
                int result = x + 1;
                int junk = 3;
                return result + junk;
            }
        }
    """.trimIndent()

    fun testTransitiveFoldNeverHidesTouchedLines() {
        myFixture.configureByText("W.java", flowSample)
        FocusService.toggleAtCaret(myFixture.editor, project)
        val set = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!
        set.transitive = true
        set.foldToFocus = true
        FocusService.refreshAll(set)

        // the invariant: every touched line (direct or propagated) stays visible
        val picture = FocusPicture.of(set, myFixture.file, myFixture.editor.document)
        for (line in picture.byLine.keys) {
            assertFalse("touched line $line must never fold", lineFolded(line))
        }
        // and concretely: the propagated println line is visible, pure noise is not
        assertFalse(lineFolded(lineOf("System.out.println(r)")))
        assertTrue(lineFolded(lineOf("int noise = 9")))
    }

    fun testWithoutTransitiveDownstreamLineFolds() {
        myFixture.configureByText("W.java", flowSample)
        FocusService.toggleAtCaret(myFixture.editor, project)
        val set = myFixture.editor.getUserData(FocusKeys.FOCUS_SET)!!
        set.foldToFocus = true
        FocusService.refreshAll(set)
        assertTrue("downstream line folds in direct-only mode", lineFolded(lineOf("System.out.println(r)")))
    }
}
