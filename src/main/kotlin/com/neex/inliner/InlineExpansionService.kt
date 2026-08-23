package com.neex.inliner

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase
import com.intellij.psi.util.PsiTreeUtil

object InlineExpansionService {

    fun toggle(editor: Editor, project: Project) {
        val call = findCallAtCaret(editor, project)
        if (call == null) {
            hint(editor, "No method call at caret")
            return
        }
        val frame = editor.getUserData(Keys.FRAME_NODE)
        val siblings = frame?.children ?: editor.getUserData(Keys.HOST_STATE)?.roots
        val existing = siblings?.firstOrNull {
            it.callRange.isValid &&
                it.callRange.startOffset == call.textRange.startOffset &&
                it.callRange.endOffset == call.textRange.endOffset
        }
        if (existing != null) {
            collapse(existing)
        } else {
            expand(editor as EditorEx, project, call, frame)
        }
    }

    private fun expand(invokingEditor: EditorEx, project: Project, call: PsiCallExpression, frame: ExpansionNode?) {
        val resolved = try {
            call.resolveMethod()
        } catch (_: IndexNotReadyException) {
            hint(invokingEditor, "Indexing in progress — try again in a moment")
            return
        }
        if (resolved == null) {
            hint(invokingEditor, "Cannot resolve call target")
            return
        }
        // Prefer attached sources over a compiled stub.
        val method = resolved.navigationElement as? PsiMethod ?: resolved
        if (method.body == null) {
            hint(invokingEditor, "No source body for ${method.name}()")
            return
        }
        val calleeFile = method.containingFile
        val calleeVFile = calleeFile?.virtualFile
        val calleeDoc = calleeFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        if (calleeVFile == null || calleeDoc == null) {
            hint(invokingEditor, "No document for ${method.name}()")
            return
        }

        val hostEditor = frame?.hostState?.hostEditor ?: invokingEditor
        val state = hostEditor.getUserData(Keys.HOST_STATE) ?: HostState(hostEditor).also {
            hostEditor.putUserData(Keys.HOST_STATE, it)
            EditorUtil.disposeWithEditor(hostEditor, it)
        }

        val doc = invokingEditor.document
        val callRange = doc.createRangeMarker(call.textRange)
        val methodRange = calleeDoc.createRangeMarker(method.textRange).apply { isGreedyToRight = true }
        val methodPtr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method)

        val node = ExpansionNode(state, frame, invokingEditor, callRange, methodPtr, methodRange)

        val fragEditor = FragmentEditorFactory.createFragmentViewer(project, calleeDoc, calleeVFile, method.textRange)
        fragEditor.putUserData(Keys.FRAME_NODE, node)
        node.fragmentEditor = fragEditor
        FragmentEditorFactory.redirectWheelTo(fragEditor, state.hostEditor)

        val header = FragmentEditorFactory.createHeader(breadcrumbText(node, method)) { collapse(node) }
        node.wrapper = FragmentEditorFactory.createWrapper(
            header, fragEditor.component, callIndentPx(invokingEditor, callRange.startOffset),
        )

        val inlayOffset = doc.getLineEndOffset(doc.getLineNumber(callRange.endOffset))
        val inlay = FragmentEditorFactory.embed(invokingEditor, inlayOffset, node.wrapper)
        if (inlay == null) {
            EditorFactory.getInstance().releaseEditor(fragEditor)
            callRange.dispose()
            methodRange.dispose()
            hint(invokingEditor, "Could not create inline view here")
            return
        }
        node.inlay = inlay

        (frame?.children ?: state.roots).add(node)
        state.creationOrder.add(node)
        Disposer.register(frame ?: state, node)

        installInvalidationListeners(node, inlay)
        relayoutUp(node)
    }

    fun collapse(node: ExpansionNode) {
        if (node.disposed) return
        val parentEditor = node.parentEditor
        val parent = node.parent
        val caretOffset = if (node.callRange.isValid) node.callRange.startOffset else null
        Disposer.dispose(node)
        if (!parentEditor.isDisposed) {
            if (caretOffset != null) parentEditor.caretModel.moveToOffset(caretOffset)
            IdeFocusManager.getGlobalInstance().requestFocus(parentEditor.contentComponent, true)
        }
        relayoutUp(parent)
    }

    /** Belt-and-suspenders resize propagation: a nested change must grow/shrink every ancestor inlay. */
    private fun relayoutUp(node: ExpansionNode?) {
        var n = node
        while (n != null) {
            if (!n.disposed) {
                n.wrapper.revalidate()
                n.wrapper.repaint()
                if (n.inlay.isValid) n.inlay.update()
                n.parentEditor.contentComponent.revalidate()
            }
            n = n.parent
        }
    }

    private fun installInvalidationListeners(node: ExpansionNode, inlay: Inlay<*>) {
        // Call line deleted → the platform removes the inlay → drop the node (unless we removed it ourselves).
        node.parentEditor.inlayModel.addListener(object : com.intellij.openapi.editor.InlayModel.SimpleAdapter() {
            override fun onRemoved(removed: Inlay<*>) {
                if (removed === inlay && !node.disposed) {
                    ApplicationManager.getApplication().invokeLater { if (!node.disposed) collapse(node) }
                }
            }
        }, node)
        // Callee method deleted / emptied by an edit in its own file → collapse.
        node.fragmentEditor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (node.disposed) return
                if (!node.methodRange.isValid || node.methodRange.startOffset == node.methodRange.endOffset) {
                    ApplicationManager.getApplication().invokeLater { if (!node.disposed) collapse(node) }
                }
            }
        }, node)
    }

    private fun findCallAtCaret(editor: Editor, project: Project): PsiCallExpression? {
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.commitDocument(editor.document)
        val psiFile = pdm.getPsiFile(editor.document) ?: return null
        val offset = editor.caretModel.offset
        val el = psiFile.findElementAt(offset)
            ?: (if (offset > 0) psiFile.findElementAt(offset - 1) else null)
        return PsiTreeUtil.getParentOfType(el, PsiCallExpression::class.java)
    }

    private fun breadcrumbText(node: ExpansionNode, method: PsiMethod): String {
        val self = PsiFormatUtil.formatMethod(
            method, PsiSubstitutor.EMPTY,
            PsiFormatUtilBase.SHOW_CONTAINING_CLASS or PsiFormatUtilBase.SHOW_NAME or PsiFormatUtilBase.SHOW_PARAMETERS,
            PsiFormatUtilBase.SHOW_TYPE,
        )
        val chain = generateSequence(node.parent) { it.parent }
            .mapNotNull { it.methodPtr.element?.name }
            .joinToString(" ⟵ ") { "$it()" }
        return if (chain.isEmpty()) "▾ $self" else "▾ $self ⟵ $chain"
    }

    private fun callIndentPx(editor: EditorEx, callStartOffset: Int): Int {
        val doc = editor.document
        val line = doc.getLineNumber(callStartOffset)
        val end = doc.getLineEndOffset(line)
        var i = doc.getLineStartOffset(line)
        val text = doc.charsSequence
        while (i < end && text[i].isWhitespace()) i++
        return editor.offsetToXY(i).x
    }

    private fun hint(editor: Editor, message: String) {
        if (editor.component.isShowing) {
            HintManager.getInstance().showInformationHint(editor, message)
        }
    }
}
