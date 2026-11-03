package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.SingleAlarm
import com.neex.inliner.ExpansionNode
import com.neex.inliner.InlineExpansionService
import com.neex.inliner.InlinerLifetime
import com.neex.inliner.Keys

object FocusService {

    fun hostEditorOf(editor: Editor): Editor =
        editor.getUserData(Keys.FRAME_NODE)?.hostState?.hostEditor ?: editor

    fun focusSet(editor: Editor, project: Project, create: Boolean): FocusSet? {
        val host = hostEditorOf(editor)
        var set = host.getUserData(FocusKeys.FOCUS_SET)
        if (set == null && create) {
            set = FocusSet(host, project)
            host.putUserData(FocusKeys.FOCUS_SET, set)
            InlinerLifetime.bind(host, set)
            installRefreshListener(set)
        }
        return set
    }

    fun toggleAtCaret(editor: Editor, project: Project) {
        if (editor.getUserData(Keys.FRAME_NODE)?.pinnedPsiFile == null) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        val psiFile = InlineExpansionService.psiFileFor(editor, project) ?: return
        val offset = editor.caretModel.offset
        val leaf = psiFile.findElementAt(offset)
            ?: (if (offset > 0) psiFile.findElementAt(offset - 1) else null)
        val target = variableAt(leaf)
        if (target == null) {
            hint(editor, "No parameter or local variable at caret")
            return
        }
        if (target is PsiField) {
            hint(editor, "Fields are tracked from the Focus dialog (Alt+;)")
            return
        }
        val scope = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java)
            ?: PsiTreeUtil.getParentOfType(target, PsiMethod::class.java)
        if (scope == null) {
            hint(editor, "Caret is not inside a method")
            return
        }
        val set = focusSet(editor, project, create = true)!!
        val existing = set.findEntry(target)
        if (existing != null) remove(set, existing) else add(set, target, scope)
        refreshAll(set)
    }

    private fun variableAt(leaf: com.intellij.psi.PsiElement?): PsiVariable? {
        val id = leaf as? PsiIdentifier ?: return null
        return when (val parent = id.parent) {
            is PsiReferenceExpression -> parent.resolve() as? PsiVariable
            is PsiVariable -> parent.takeIf { it.nameIdentifier == id }
            else -> null
        }
    }

    fun add(
        set: FocusSet,
        target: PsiVariable,
        scope: PsiMethod,
        parent: FocusEntry? = null,
        colorIndex: Int = set.nextColorIndex(),
    ): FocusEntry {
        val spm = SmartPointerManager.getInstance(set.project)
        val entry = FocusEntry(
            spm.createSmartPsiElementPointer(target),
            spm.createSmartPsiElementPointer(scope),
            colorIndex,
            target.name ?: "?",
            focusKindOf(target),
            parent,
        )
        set.entries += entry
        return entry
    }

    /** Removing an entry also removes everything derived from it (transitively). */
    fun remove(set: FocusSet, entry: FocusEntry) {
        val doomed = mutableSetOf(entry)
        var grew = true
        while (grew) {
            grew = set.entries.any { it.parent in doomed && doomed.add(it) }
        }
        set.entries.removeAll(doomed)
    }

    fun removeDerivedFor(set: FocusSet, node: ExpansionNode) {
        val doomed = set.entries.filter { it.sourceNode === node }.toMutableSet()
        var grew = true
        while (grew) {
            grew = set.entries.any { it.parent in doomed && doomed.add(it) }
        }
        set.entries.removeAll(doomed)
    }

    /** Recompute highlights (and folds, when enabled) on the host editor and every live frame. */
    fun refreshAll(set: FocusSet) {
        val host = set.hostEditor
        if (host.isDisposed) return
        FocusFrames.propagateAll(set)
        val editors = buildList {
            add(host)
            host.getUserData(Keys.HOST_STATE)?.let { collectFragments(it.roots, this) }
        }
        for (editor in editors) {
            if (editor.isDisposed) continue
            val psiFile = InlineExpansionService.psiFileFor(editor, set.project)
            if (psiFile == null) {
                FocusHighlighter.clear(editor)
                continue
            }
            val picture = FocusPicture.of(set, psiFile, editor.document, editor)
            FocusHighlighter.apply(editor, picture)
            FocusFolder.refresh(editor, set, psiFile, picture)
        }
        if (!set.project.isDisposed) set.project.messageBus.syncPublisher(FocusListener.TOPIC).focusChanged(host)
    }

    private fun collectFragments(nodes: List<ExpansionNode>, out: MutableList<Editor>) {
        for (n in nodes) {
            if (!n.disposed && n.fragmentEditor.isDisposed.not()) {
                out.add(n.fragmentEditor)
                collectFragments(n.children, out)
            }
        }
    }

    private fun installRefreshListener(set: FocusSet) {
        val alarm = SingleAlarm(
            {
                if (!set.hostEditor.isDisposed) {
                    PsiDocumentManager.getInstance(set.project).performLaterWhenAllCommitted {
                        if (!set.hostEditor.isDisposed) refreshAll(set)
                    }
                }
            },
            300,
            set,
        )
        set.refreshAlarm = alarm
        set.hostEditor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (set.entries.isNotEmpty()) alarm.cancelAndRequest()
                }
            },
            set,
        )
    }

    /**
     * A frame's document is the callee's own, often another file than the host's: edits made in the
     * frame (an intention, typing) must repaint focus like edits in the host do. Called for every
     * new frame, whether or not anything is tracked yet; goes with the frame.
     */
    fun watchFrame(node: ExpansionNode) {
        val document = node.fragmentEditor.document
        if (document === node.hostState.hostEditor.document) return // the host's own listener has it
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val set = node.hostState.hostEditor.getUserData(FocusKeys.FOCUS_SET) ?: return
                    if (set.entries.isNotEmpty()) set.refreshAlarm?.cancelAndRequest()
                }
            },
            node,
        )
    }

    private fun hint(editor: Editor, message: String) {
        if (editor.component.isShowing) {
            HintManager.getInstance().showInformationHint(editor, message)
        }
    }
}
