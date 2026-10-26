package com.neex.inliner

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TextExpression
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl

class FrameIntentionsTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class A {
            void caller() {
                callee();
            }
            void callee() {
                int x = 7;
                x++;
            }
        }
    """.trimIndent()

    private val split = "Split into declaration and assignment"

    private fun frameOnDeclaration(): EditorEx {
        myFixture.configureByText("A.java", sample)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("callee();"))
        InlineExpansionService.toggle(myFixture.editor, project)
        val frame = FrameRows.framesIn(myFixture.editor).single().fragmentEditor
        frame.caretModel.moveToOffset(frame.document.text.indexOf("x = 7"))
        return frame
    }

    private fun intentions(frame: EditorEx): List<IntentionAction> =
        CodeInsightTestFixtureImpl.getAvailableIntentions(frame, myFixture.file)

    private fun typeInto(frame: EditorEx) =
        ActionManagerEx.getInstanceEx().fireBeforeEditorTyping('q', SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, frame))

    fun testFrameOutsideASessionIsAViewerWithoutIntentions() {
        val frame = frameOnDeclaration()
        assertTrue(frame.isViewer)
        assertFalse(intentions(frame).any { it.text == split })
    }

    fun testSessionListsAndAppliesTheCalleesIntentions() {
        val frame = frameOnDeclaration()
        FrameEditSession.open(FrameRows.frameOf(frame)!!)
        val action = intentions(frame).single { it.text == split }
        ShowIntentionActionsHandler.chooseActionAndInvoke(myFixture.file, frame, action, action.text)
        assertTrue(frame.document.text.contains("int x;\n        x = 7;"))
    }

    fun testUserActingOnTheFrameEndsTheSession() {
        val frame = frameOnDeclaration()
        FrameEditSession.open(FrameRows.frameOf(frame)!!)
        assertFalse(frame.isViewer)
        typeInto(frame)
        assertTrue(frame.isViewer)
        assertFalse(FrameEditSession.isOpen(frame))
        assertFalse(EditorModificationUtil.checkModificationAllowed(frame))
    }

    fun testHostTypingLeavesTheSessionAlone() {
        val frame = frameOnDeclaration()
        FrameEditSession.open(FrameRows.frameOf(frame)!!)
        ActionManagerEx.getInstanceEx().fireBeforeEditorTyping('q', SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor))
        assertTrue(FrameEditSession.isOpen(frame))
    }

    fun testATemplateTheFlowStartedKeepsTheFrameWritable() {
        val frame = frameOnDeclaration()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        FrameEditSession.open(FrameRows.frameOf(frame)!!)
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.addVariable("NAME", TextExpression("x"), true)
        TemplateManager.getInstance(project).startTemplate(frame, template)
        typeInto(frame)
        assertTrue("typing into the template is part of the flow", FrameEditSession.isOpen(frame))
        TemplateManagerImpl.getTemplateState(frame)!!.gotoEnd(false)
        typeInto(frame)
        assertFalse(FrameEditSession.isOpen(frame))
        assertTrue(frame.isViewer)
    }

    fun testCollapsingTheFrameEndsTheSession() {
        val frame = frameOnDeclaration()
        val node = FrameRows.frameOf(frame)!!
        FrameEditSession.open(node)
        InlineExpansionService.collapse(node)
        assertTrue(node.disposed)
    }

    fun testPromoterPutsFrameIntentionsFirstInAFrameOnly() {
        val frame = frameOnDeclaration()
        val platform = ActionManager.getInstance().getAction("ShowIntentionActions")
        val ours = ActionManager.getInstance().getAction("com.neex.inliner.FrameIntentions")
        val both = listOf<AnAction>(platform, ours)
        val promoter = FrameIntentionsPromoter()
        assertEquals(listOf(ours, platform), promoter.promote(both, SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, frame)))
        assertNull(promoter.promote(both, SimpleDataContext.getSimpleContext(CommonDataKeys.EDITOR, myFixture.editor)))
    }

    fun testCaretTheFlowMovesOutsideComesBackWhenTheSessionEnds() {
        val frame = frameOnDeclaration()
        val node = FrameRows.frameOf(frame)!!
        FrameEditSession.open(node)
        frame.caretModel.moveToOffset(0)
        assertEquals("the flow owns the caret", 0, frame.caretModel.offset)
        typeInto(frame)
        assertTrue(frame.caretModel.offset >= frame.document.getLineStartOffset(frame.document.getLineNumber(node.methodRange.startOffset)))
    }
}
