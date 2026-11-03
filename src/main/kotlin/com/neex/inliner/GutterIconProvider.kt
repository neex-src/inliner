package com.neex.inliner

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiDocumentManager
import com.neex.inliner.focus.FocusSettings
import javax.swing.Icon

/**
 * A gutter icon on each line that has a call the plugin can expand; clicking it does what the
 * toggle action does with the caret on that call. Resolving a call is slow, so this runs in the
 * slow pass; on a line with several calls the first one that resolves gets the icon.
 * Switched by [FocusSettings.Options.gutterIcons].
 */
class GutterIconProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Inline Reading Mode: expand call"

    override fun getIcon(): Icon = AllIcons.General.ExpandComponent

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        if (!FocusSettings.getInstance().gutterIcons) return
        val first = elements.firstOrNull() ?: return
        val doc = PsiDocumentManager.getInstance(first.project).getDocument(first.containingFile) ?: return
        val marked = HashSet<Int>()
        for (leaf in elements) {
            val call = callOf(leaf) ?: continue
            val offset = leaf.textRange.startOffset
            if (offset > doc.textLength) continue
            val line = doc.getLineNumber(offset)
            if (line in marked) continue
            val resolved = try {
                call.resolveMethod()
            } catch (_: IndexNotReadyException) {
                null
            } ?: continue
            marked += line
            result += LineMarkerInfo(
                leaf, leaf.textRange, AllIcons.General.ExpandComponent, { "Expand ${resolved.name}() inline" },
                GutterIconNavigationHandler<PsiElement> { event, element ->
                    val target = callOf(element) ?: return@GutterIconNavigationHandler
                    val project = element.project
                    val file = element.containingFile.viewProvider.virtualFile
                    val editors = FileEditorManager.getInstance(project).getAllEditors(file)
                        .filterIsInstance<TextEditor>().map { it.editor }
                    // the editor whose gutter was clicked; no event (a test): the first one
                    val editor = editors.firstOrNull { it.gutter === event?.component } ?: editors.firstOrNull()
                    if (editor != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
                        InlineExpansionService.toggleCall(editor, project, target)
                    }
                },
                GutterIconRenderer.Alignment.LEFT, { "Expand call inline" },
            )
        }
    }

    /** The call whose name token [leaf] is: the identifier of a method call, the `new` of a constructor call. */
    private fun callOf(leaf: PsiElement): PsiCallExpression? = when {
        leaf is PsiIdentifier -> (leaf.parent?.parent as? PsiMethodCallExpression)
            ?.takeIf { it.methodExpression.referenceNameElement === leaf }
        leaf is PsiJavaToken && leaf.text == "new" -> leaf.parent as? PsiNewExpression
        else -> null
    }
}
