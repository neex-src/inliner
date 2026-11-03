package com.neex.inliner

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.util.SingleAlarm
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.inliner.focus.FocusKeys
import com.neex.inliner.focus.FocusService
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase
import com.intellij.psi.util.PsiTreeUtil

object InlineExpansionService {

    fun toggle(editor: Editor, project: Project) {
        // Commit here, NOT in psiFileFor: this runs on EDT with write intent, while
        // psiFileFor is also called from update() on the BGT where committing is forbidden.
        if (editor.getUserData(Keys.FRAME_NODE)?.pinnedPsiFile == null) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        val call = findCallAtCaret(editor, project)
        if (call == null) {
            hint(editor, "No method call at caret")
            return
        }
        toggleCall(editor, project, call)
    }

    /** What the toggle action does with the caret on [call]: expand it, or collapse it when already expanded. */
    fun toggleCall(editor: Editor, project: Project, call: PsiCallExpression) {
        val frame = editor.getUserData(Keys.FRAME_NODE)
        val siblings = frame?.children ?: editor.getUserData(Keys.HOST_STATE)?.roots
        val existing = siblings?.firstOrNull {
            it.callRange.isValid &&
                it.callRange.startOffset == call.textRange.startOffset &&
                it.callRange.endOffset == call.textRange.endOffset
        }
        if (existing != null) {
            collapse(existing, focus = true)
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
        if (resolved.body == null && resolved.hasModifierProperty(PsiModifier.ABSTRACT)) {
            chooseImplementation(invokingEditor, project, call, frame, resolved)
            return
        }
        expandMethod(invokingEditor, project, call, frame, resolved)
    }

    /** Interface / abstract target: inline the only implementation, or let the user pick one. */
    private fun chooseImplementation(
        invokingEditor: EditorEx,
        project: Project,
        call: PsiCallExpression,
        frame: ExpansionNode?,
        declaration: PsiMethod,
    ) {
        val impls = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<ImplChoice>, RuntimeException>(
            {
                ApplicationManager.getApplication().runReadAction(
                    Computable { findImplementations(declaration).map(::ImplChoice) },
                )
            },
            "Searching for Implementations…", true, project,
        )
        fun inline(choice: ImplChoice) {
            val method = choice.method.element
            if (method == null) hint(invokingEditor, "${choice.label} no longer exists") else expandMethod(invokingEditor, project, call, frame, method)
        }
        when {
            impls.isEmpty() -> hint(invokingEditor, "No implementations found for ${declaration.name}()")
            impls.size == 1 -> inline(impls[0])
            else -> {
                testImplPicker?.let { pick ->
                    pick(impls)?.let(::inline)
                    return
                }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(impls)
                    .setTitle("Implementations of ${declaration.name}()")
                    .setRenderer(textListCellRenderer { it?.label })
                    .setNamerForFiltering { it.label }
                    // a Swing callback: no lock held on 262, and expanding reads PSI and edits markup;
                    // invokeLater gives it the write-intent lock (WriteIntentReadAction is experimental)
                    .setItemChosenCallback { ApplicationManager.getApplication().invokeLater { inline(it) } }
                    .createPopup()
                    .showInBestPositionFor(invokingEditor)
            }
        }
    }

    /**
     * A picker row. Plain data on purpose: PSI in a popup model gets read on the EDT without a
     * read action — by the renderer, the filter and the popup's data context — and the platform
     * reports each read as an IDE error ("Do not use PsiElement for popup model").
     */
    internal class ImplChoice(m: PsiMethod) {
        val label: String = implLabel(m)
        val method: SmartPsiElementPointer<PsiMethod> = SmartPointerManager.createPointer(m)
    }

    internal fun findImplementations(declaration: PsiMethod): List<PsiMethod> = try {
        OverridingMethodsSearch.search(declaration).findAll()
            .filter { !it.hasModifierProperty(PsiModifier.ABSTRACT) }
            .sortedBy { implLabel(it) }
            .take(50)
    } catch (_: IndexNotReadyException) {
        emptyList()
    }

    /** Test seam: the popup can't run headless; tests stub the choice instead. */
    internal var testImplPicker: ((List<ImplChoice>) -> ImplChoice?)? = null

    private fun implLabel(m: PsiMethod): String = m.containingClass?.qualifiedName ?: m.name

    internal fun expandMethod(
        invokingEditor: EditorEx,
        project: Project,
        call: PsiCallExpression,
        frame: ExpansionNode?,
        resolved: PsiMethod,
    ) {
        val method = sourceForm(resolved)
        if (method == null) {
            hint(invokingEditor, "No source or decompiled body for ${resolved.name}()")
            return
        }
        val calleeFile = method.containingFile
        if (calleeFile == null) {
            hint(invokingEditor, "No file for ${method.name}()")
            return
        }
        val pdm = PsiDocumentManager.getInstance(project)
        val calleeDoc = pdm.getDocument(calleeFile)
            ?: calleeFile.viewProvider.document
            ?: EditorFactory.getInstance().createDocument(calleeFile.text)
        // A frame whose document PsiDocumentManager can't map back to PSI (decompiled mirror)
        // must carry its PsiFile along, or caret→PSI inside it is dead and recursion with it.
        val pinnedPsiFile = if (pdm.getPsiFile(calleeDoc) != null) null else calleeFile
        if (calleeDoc.textLength < method.textRange.endOffset) {
            hint(invokingEditor, "Source for ${method.name}() is out of sync")
            return
        }

        val hostEditor = frame?.hostState?.hostEditor ?: invokingEditor
        val state = hostEditor.getUserData(Keys.HOST_STATE) ?: HostState(hostEditor).also {
            hostEditor.putUserData(Keys.HOST_STATE, it)
            InlinerLifetime.bind(hostEditor, it)
            FrameCaret.install(it)
        }

        val doc = invokingEditor.document
        val callRange = doc.createRangeMarker(call.textRange)
        val methodRange = calleeDoc.createRangeMarker(method.textRange).apply { isGreedyToRight = true }
        val methodPtr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method, calleeFile)

        val node = ExpansionNode(state, frame, invokingEditor, callRange, methodPtr, methodRange, pinnedPsiFile)

        val fragEditor = FragmentEditorFactory.createFragmentViewer(
            project, calleeDoc, calleeFile.virtualFile, calleeFile.fileType, method.textRange,
        )
        fragEditor.putUserData(Keys.FRAME_NODE, node)
        node.fragmentEditor = fragEditor
        FragmentEditorFactory.redirectWheelTo(fragEditor, state.hostEditor)

        val header = FragmentEditorFactory.createHeader(breadcrumbText(node, method)) { collapse(node, focus = true) }
        node.wrapper = FragmentEditorFactory.createWrapper(
            header, fragEditor.component, callIndentPx(invokingEditor, callRange.startOffset), FramePanel(node),
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
        FocusService.watchFrame(node)
        FrameCaret.install(node)
        relayoutUp(node)

        // Focus integration: propagate tracked arguments into the new frame and paint it.
        state.hostEditor.getUserData(FocusKeys.FOCUS_SET)?.let { FocusService.refreshAll(it) }
        project.messageBus.syncPublisher(FrameListener.TOPIC).framesChanged(state.hostEditor)
    }

    /** The one place collapse asks for the keyboard; a seam so tests can see whether it was asked. */
    internal var focusRequester: (Editor) -> Unit = {
        IdeFocusManager.getGlobalInstance().requestFocus(it.contentComponent, true)
    }

    /**
     * [focus]: take the keyboard to the parent editor. True when the user collapsed (toggle,
     * Esc, header click); false when an edit elsewhere did, so focus stays where the user is.
     */
    fun collapse(node: ExpansionNode, focus: Boolean) {
        if (node.disposed) return
        val parentEditor = node.parentEditor
        val parent = node.parent
        val caretOffset = if (node.callRange.isValid) node.callRange.startOffset else null
        val host = node.hostState.hostEditor
        Disposer.dispose(node)
        host.project?.takeUnless { it.isDisposed }?.messageBus?.syncPublisher(FrameListener.TOPIC)?.framesChanged(host)
        if (!parentEditor.isDisposed) {
            if (caretOffset != null) parentEditor.caretModel.moveToOffset(caretOffset)
            if (focus) focusRequester(parentEditor)
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
                    ApplicationManager.getApplication().invokeLater { if (!node.disposed) collapse(node, focus = false) }
                }
            }
        }, node)
        // methodRange is greedy to the right, so a method typed right after the callee's `}` ends up
        // inside it: once the edit is committed, the frame is clipped to the method PSI says again.
        val reclip = SingleAlarm(
            { PsiDocumentManager.getInstance(node.parentEditor.project!!).performLaterWhenAllCommitted { reclip(node) } },
            200, node,
        )
        // Callee method deleted / emptied by an edit in its own file → collapse.
        node.fragmentEditor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (node.disposed) return
                reclip.cancelAndRequest()
                if (!node.methodRange.isValid || node.methodRange.startOffset == node.methodRange.endOffset) {
                    ApplicationManager.getApplication().invokeLater { if (!node.disposed) collapse(node, focus = false) }
                }
            }
        }, node)
    }

    private fun reclip(node: ExpansionNode) {
        if (node.disposed || node.fragmentEditor.isDisposed) return
        val range = node.methodPtr.element?.textRange ?: return
        val old = node.methodRange
        if (old.isValid && old.startOffset == range.startOffset && old.endOffset == range.endOffset) return
        node.methodRange = node.fragmentEditor.document.createRangeMarker(range).apply { isGreedyToRight = true }
        old.dispose()
        FragmentEditorFactory.clipToRange(node.fragmentEditor, range)
        node.hostState.hostEditor.getUserData(FocusKeys.FOCUS_SET)?.let(FocusService::refreshAll) // clipping cleared the folds
        relayoutUp(node)
    }

    /**
     * The best readable form of a resolved method: itself or its source (attached -sources /
     * src.zip via navigationElement), else the decompiled/stub mirror of a compiled method.
     */
    private fun sourceForm(resolved: PsiMethod): PsiMethod? {
        val nav = resolved.navigationElement as? PsiMethod ?: resolved
        if (nav.body != null) return nav
        val mirror = (resolved as? PsiCompiledElement)?.mirror as? PsiMethod
        if (mirror?.body != null) return mirror
        return null
    }

    /** Read-only on purpose — called from update() on the background action-update thread. */
    internal fun psiFileFor(editor: Editor, project: Project): PsiFile? {
        editor.getUserData(Keys.FRAME_NODE)?.pinnedPsiFile?.let { return it }
        return PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
    }

    private fun findCallAtCaret(editor: Editor, project: Project): PsiCallExpression? {
        val psiFile = psiFileFor(editor, project) ?: return null
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
