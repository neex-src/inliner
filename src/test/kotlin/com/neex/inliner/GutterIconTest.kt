package com.neex.inliner

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.focus.FocusSettings

class GutterIconTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class A {
            void caller() {
                int a = 1;
                callee();
                int b = 2;
            }
            void callee() {
                int x = 3;
            }
        }
    """.trimIndent()

    override fun tearDown() {
        try {
            FocusSettings.getInstance().state.gutterIcons = true
        } finally {
            super.tearDown()
        }
    }

    private fun icons(): List<LineMarkerInfo<PsiElement>> = myFixture.findAllGutters()
        .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
        .map { @Suppress("UNCHECKED_CAST") (it.lineMarkerInfo as LineMarkerInfo<PsiElement>) }
        .filter { it.createGutterRenderer()?.tooltipText?.startsWith("Expand ") == true }

    private fun iconLines(): List<Int> = icons().map { myFixture.editor.document.getLineNumber(it.startOffset) }

    fun testExpandableCallHasIconOthersNone() {
        myFixture.configureByText("A.java", sample)
        assertEquals("only the callee() line", listOf(3), iconLines())
    }

    fun testSettingOffRemovesIcons() {
        FocusSettings.getInstance().state.gutterIcons = false
        myFixture.configureByText("A.java", sample)
        assertEquals(emptyList<Int>(), iconLines())
    }

    fun testClickExpandsTheCall() {
        myFixture.configureByText("A.java", sample)
        val icon = icons().single()
        icon.navigationHandler!!.navigate(null, icon.element)
        assertEquals(1, myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots.size)
    }
}
