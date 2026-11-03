package com.neex.inliner

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.PlatformTestUtil
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

    fun testEscapeClearsSelectionBeforeCollapsing() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        myFixture.editor.selectionModel.setSelection(0, 5)

        myFixture.performEditorAction("EditorEscape")
        assertFalse(myFixture.editor.selectionModel.hasSelection())
        assertEquals("first Esc only clears the selection", 1, state.creationOrder.size)

        myFixture.performEditorAction("EditorEscape")
        assertTrue(state.creationOrder.isEmpty())
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

        InlineExpansionService.collapse(root, focus = true)

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

    // ---- who gets the keyboard when a frame collapses ----

    private val focusAsked = mutableListOf<Editor>()
    private val realRequester = InlineExpansionService.focusRequester

    override fun setUp() {
        super.setUp()
        focusAsked.clear()
        InlineExpansionService.focusRequester = { focusAsked.add(it) }
    }

    override fun tearDown() {
        try {
            InlineExpansionService.focusRequester = realRequester
        } finally {
            super.tearDown()
        }
    }

    fun testToggleCollapseAsksForFocus() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        InlineExpansionService.toggle(myFixture.editor, project)
        assertEquals(listOf(myFixture.editor), focusAsked)
    }

    fun testEscapeCollapseAsksForFocus() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        myFixture.performEditorAction("EditorEscape")
        assertEquals(listOf(myFixture.editor), focusAsked)
    }

    fun testCollapseCausedByEmptyingCalleeDoesNotAskForFocus() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        val node = state.roots.single()

        WriteCommandAction.runWriteCommandAction(project) {
            node.fragmentEditor.document.deleteString(node.methodRange.startOffset, node.methodRange.endOffset)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue("the edit should have collapsed the frame", node.disposed)
        assertTrue("an edit must not take the keyboard", focusAsked.isEmpty())
    }

    fun testCollapseCausedByDeletingCallLineDoesNotAskForFocus() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val node = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots.single()

        WriteCommandAction.runWriteCommandAction(project) {
            val doc = myFixture.editor.document
            val line = doc.getLineNumber(node.callRange.startOffset)
            doc.deleteString(doc.getLineStartOffset(line), doc.getLineEndOffset(line) + 1)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue("the edit should have collapsed the frame", node.disposed)
        assertTrue("an edit must not take the keyboard", focusAsked.isEmpty())
    }

    fun testAMethodTypedRightAfterTheCalleeStaysOutOfTheFrame() {
        myFixture.configureByText("A.java", sample)
        InlineExpansionService.toggle(myFixture.editor, project)
        val node = myFixture.editor.getUserData(Keys.HOST_STATE)!!.roots[0]
        val at = node.methodRange.endOffset
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(at, " int extra() { return 1; }")
        }
        PlatformTestUtil.waitWithEventsDispatching(
            "the frame is the callee again, not the callee and what was typed after it",
            { node.methodRange.endOffset == node.methodPtr.element!!.textRange.endOffset },
            5,
        )
        assertEquals(at, node.methodRange.endOffset)
        val hidden = node.fragmentEditor.foldingModel.getCollapsedRegionAtOffset(at + 5)
        assertNotNull("the typed method is clipped away", hidden)
    }

    fun testEscapeFromInsideAFrameCollapsesTheMostRecentOfItsOwnTree() {
        myFixture.configureByText(
            "E.java",
            """
            class E {
                void host() {
                    <caret>a();
                    b();
                }
                void a() { c(); }
                void b() { int q = 1; }
                void c() { int w = 2; }
            }
            """.trimIndent(),
        )
        val host = myFixture.editor
        InlineExpansionService.toggle(host, project)                                   // A, from the host
        val state = host.getUserData(Keys.HOST_STATE)!!
        val a = state.roots[0]
        a.fragmentEditor.caretModel.moveToOffset(a.fragmentEditor.document.text.indexOf("c()"))
        InlineExpansionService.toggle(a.fragmentEditor, project)                        // B, inside A
        host.caretModel.moveToOffset(host.document.text.indexOf("b();"))
        InlineExpansionService.toggle(host, project)                                    // C, from the host, the newest of all
        val (b, c) = a.children.single() to state.roots[1]
        assertEquals(listOf(a, b, c), state.creationOrder.toList())

        var delegated = 0
        val escape = InlineEscapeHandler(object : com.intellij.openapi.editor.actionSystem.EditorActionHandler() {
            override fun doExecute(editor: Editor, caret: com.intellij.openapi.editor.Caret?, dataContext: com.intellij.openapi.actionSystem.DataContext?) {
                delegated++
            }
        })
        escape.execute(a.fragmentEditor, null, null)
        assertTrue("from A, the newest of A's tree: B, not the host's newer C", b.disposed)
        assertFalse(a.disposed)
        assertFalse(c.disposed)
        escape.execute(a.fragmentEditor, null, null)
        assertTrue("then A itself", a.disposed)
        assertFalse("C is not in A's tree: untouched", c.disposed)
        escape.execute(host, null, null)
        assertTrue("from the host: the newest", c.disposed)
        assertEquals(0, delegated)
        escape.execute(host, null, null)
        assertEquals("nothing left: Esc falls through", 1, delegated)
    }
}
