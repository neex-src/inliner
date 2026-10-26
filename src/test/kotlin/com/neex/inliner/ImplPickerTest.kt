package com.neex.inliner

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ImplPickerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun tearDown() {
        try {
            InlineExpansionService.testImplPicker = null
        } finally {
            super.tearDown()
        }
    }

    fun testInterfaceCallWithSingleImplAutoInlines() {
        myFixture.configureByText(
            "S.java",
            """
            interface I { void m(); }
            class A implements I { public void m() { int a = 1; } }
            class Use { void u(I i) { i.<caret>m(); } }
            """.trimIndent(),
        )
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)
        assertNotNull("single implementation should inline without a popup", state)
        assertEquals("A", state!!.roots[0].methodPtr.element?.containingClass?.name)
    }

    fun testInterfaceCallWithMultipleImplsOffersChoice() {
        myFixture.configureByText(
            "M.java",
            """
            interface I2 { void m(); }
            class B implements I2 { public void m() { int b = 2; } }
            class C implements I2 { public void m() { int c = 3; } }
            class Use2 { void u(I2 i) { i.<caret>m(); } }
            """.trimIndent(),
        )
        var offered: List<String> = emptyList()
        InlineExpansionService.testImplPicker = { choices ->
            offered = choices.map { it.label }
            choices.first { it.label == "C" }
        }
        InlineExpansionService.toggle(myFixture.editor, project)

        assertEquals(listOf("B", "C"), offered.sorted())
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        assertEquals("C", state.roots[0].methodPtr.element?.containingClass?.name)
    }

    fun testChoiceDeletedBeforePickIsNotInlined() {
        myFixture.configureByText(
            "G.java",
            """
            interface I4 { void m(); }
            class G1 implements I4 { public void m() { int g = 1; } }
            class G2 implements I4 { public void m() { int g = 2; } }
            class Use4 { void u(I4 i) { i.<caret>m(); } }
            """.trimIndent(),
        )
        InlineExpansionService.testImplPicker = { choices ->
            val g2 = myFixture.findClass("G2")
            WriteCommandAction.runWriteCommandAction(project) { g2.delete() }
            choices.first { it.label == "G2" }
        }
        InlineExpansionService.toggle(myFixture.editor, project)
        assertNull(myFixture.editor.getUserData(Keys.HOST_STATE))
    }

    fun testAbstractClassMethodInlinesOverride() {
        myFixture.configureByText(
            "D.java",
            """
            abstract class D { abstract void m(); }
            class E extends D { void m() { int e = 5; } }
            class Use3 { void u(D d) { d.<caret>m(); } }
            """.trimIndent(),
        )
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        assertEquals("E", state.roots[0].methodPtr.element?.containingClass?.name)
    }
}
