package com.neex.inliner

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class InlineExpansionTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class A {
            void caller() {
                cal<caret>lee();
            }
            void callee() {
                int x = helper();
            }
            int helper() {
                return 42;
            }
        }
    """.trimIndent()

    private fun blockInlays() =
        myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength)

    fun testExpandResolvesCalleeAndAddsInlay() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)

        val state = myFixture.editor.getUserData(Keys.HOST_STATE)
        assertNotNull("expansion state missing on host editor", state)
        assertEquals(1, state!!.roots.size)
        val node = state.roots[0]
        assertEquals("callee", node.methodPtr.element?.name)
        assertTrue("method range should span signature + body", node.methodRange.endOffset > node.methodRange.startOffset)
        assertFalse(node.fragmentEditor.isDisposed)
        assertTrue("block inlay expected in host editor", blockInlays().isNotEmpty())
    }

    fun testToggleCollapsesAndReleasesEditor() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        val node = state.roots[0]

        InlineExpansionService.toggle(myFixture.editor, project)

        assertTrue(state.roots.isEmpty())
        assertTrue(node.disposed)
        assertTrue(node.fragmentEditor.isDisposed)
        assertTrue(blockInlays().isEmpty())
    }

    fun testEscapeCollapsesExpansion() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        assertEquals(1, state.creationOrder.size)

        myFixture.performEditorAction("EditorEscape")

        assertTrue(state.creationOrder.isEmpty())
        assertTrue(blockInlays().isEmpty())
    }

    fun testEscapeWithoutExpansionsFallsThrough() {
        myFixture.configureByText("A.java", sample)
        myFixture.editor.selectionModel.setSelection(0, 5)
        myFixture.performEditorAction("EditorEscape")
        assertFalse("stock Esc should still clear the selection", myFixture.editor.selectionModel.hasSelection())
    }

    fun testRecursiveExpansionAndSubtreeDisposal() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        val root = state.roots[0]
        val frag = root.fragmentEditor

        val helperCall = frag.document.text.indexOf("helper()")
        assertTrue(helperCall >= 0)
        frag.caretModel.moveToOffset(helperCall + 1)
        InlineExpansionService.toggle(frag, project)

        assertEquals(1, root.children.size)
        val child = root.children[0]
        assertEquals("helper", child.methodPtr.element?.name)
        assertEquals(2, state.creationOrder.size)

        InlineExpansionService.collapse(root)

        assertTrue(child.disposed)
        assertTrue(child.fragmentEditor.isDisposed)
        assertTrue(state.roots.isEmpty())
        assertTrue(state.creationOrder.isEmpty())
    }

    fun testRegisteredActionIdExpandsAndCollapses() {
        myFixture.configureByText("A.java", sample)
        myFixture.performEditorAction("com.neex.inliner.Toggle")
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)
        assertNotNull("action from plugin.xml should have expanded", state)
        assertEquals(1, state!!.roots.size)
        myFixture.performEditorAction("com.neex.inliner.Toggle")
        assertTrue(state.roots.isEmpty())
    }

    fun testUnresolvableCallCreatesNothing() {
        myFixture.configureByText("B.java", "class B { void m() { unk<caret>nown(); } }")
        InlineExpansionService.toggle(myFixture.editor, project)
        assertNull(myFixture.editor.getUserData(Keys.HOST_STATE))
    }

    fun testCaretNotOnCallCreatesNothing() {
        myFixture.configureByText("C.java", "class C { int <caret>field; void m() {} }")
        InlineExpansionService.toggle(myFixture.editor, project)
        assertNull(myFixture.editor.getUserData(Keys.HOST_STATE))
    }
}
