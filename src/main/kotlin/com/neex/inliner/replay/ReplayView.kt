package com.neex.inliner.replay

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.xdebugger.ui.DebuggerColors
import com.neex.inliner.recorder.VarValue
import java.awt.Graphics
import java.awt.Rectangle

/**
 * The replay's cursor painted into one editor, the way a paused debugger looks: the execution
 * line; at the end of each line the current call ran so far, what that line changed; at the
 * cursor's line, every value as it is now; the call's lines that never ran in this call greyed —
 * the path it took. Everything here is ours to remove: [clear] before each move.
 */
class ReplayView(val editor: Editor) : Disposable {

    private val inlays = ArrayList<Inlay<*>>()
    private val highlighters = ArrayList<RangeHighlighter>()

    fun render(replay: Replay) {
        clear()
        val hit = replay.current
        val doc = editor.document
        val line = hit.site.line - 1
        if (line !in 0 until doc.lineCount) return
        highlighters += editor.markupModel.addLineHighlighter(DebuggerColors.EXECUTIONPOINT_ATTRIBUTES, line, DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER)
        for ((at, changed) in replay.changesSoFar()) {
            if (changed.isNotEmpty() && at != hit.site.line) values(at - 1, changed, DebuggerColors.INLINED_VALUES_MODIFIED)
        }
        values(line, hit.values.filter { it.name != "this" }, DebuggerColors.INLINED_VALUES_EXECUTION_LINE)
        if (!hit.site.methodName.startsWith("lambda$")) greyNotRun(line, replay.linesOfCall())
    }

    private fun values(line: Int, values: List<VarValue>, key: TextAttributesKey) {
        if (values.isEmpty() || line !in 0 until editor.document.lineCount) return
        val text = values.joinToString(", ") { "${it.name} = ${it.text}" }.let { if (it.length > MAX) it.take(MAX) + "…" else it }
        editor.inlayModel.addAfterLineEndElement(editor.document.getLineEndOffset(line), true, ValuesRenderer(text, key))?.let { inlays += it }
    }

    /** Statements of the current method on lines this call never reached. Not for lambdas: their lines are the method's. */
    private fun greyNotRun(line: Int, ran: Set<Int>) {
        val project = editor.project ?: return
        val doc = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return
        val start = doc.getLineStartOffset(line)
        val at = file.findElementAt(start + (doc.charsSequence.substring(start, doc.getLineEndOffset(line)).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)))
        val method = PsiTreeUtil.getParentOfType(at, PsiMethod::class.java) ?: return
        val body = method.body ?: return
        val grey = editor.colorsScheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)?.takeIf { it.foregroundColor != null }
            ?: TextAttributes().apply { foregroundColor = JBColor.GRAY }
        for (statement in PsiTreeUtil.findChildrenOfType(body, PsiStatement::class.java)) {
            if (statement is PsiBlockStatement) continue
            // Lambda bodies and local / anonymous classes run as calls of their own.
            val owner = PsiTreeUtil.getParentOfType(statement, PsiMethod::class.java, PsiLambdaExpression::class.java, PsiClass::class.java)
            if (owner != method) continue
            val first = doc.getLineNumber(statement.textRange.startOffset)
            if (first + 1 in ran) continue
            val from = statement.textRange.startOffset
            val to = minOf(doc.getLineEndOffset(first), statement.textRange.endOffset)
            highlighters += editor.markupModel.addRangeHighlighter(from, to, HighlighterLayer.ADDITIONAL_SYNTAX, grey, HighlighterTargetArea.EXACT_RANGE)
        }
    }

    fun clear() {
        inlays.forEach { if (it.isValid) it.dispose() }
        inlays.clear()
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
    }

    override fun dispose() = clear()

    /** What [ReplayView] put at the ends of lines, by line (0-based): for tests. */
    internal fun inlayTexts(): Map<Int, String> =
        inlays.filter { it.isValid }.associate { editor.document.getLineNumber(it.offset) to (it.renderer as ValuesRenderer).text }

    internal fun highlighters(): List<RangeHighlighter> = highlighters.toList()

    internal class ValuesRenderer(val text: String, private val key: TextAttributesKey) : EditorCustomElementRenderer {
        private val shown get() = "   $text"

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN)).stringWidth(shown)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val attributes = editor.colorsScheme.getAttributes(key)
            g.font = editor.colorsScheme.getFont(EditorFontType.forJavaStyle(attributes?.fontType ?: 0))
            g.color = attributes?.foregroundColor ?: JBColor.GRAY
            g.drawString(shown, targetRegion.x, targetRegion.y + editor.ascent)
        }
    }

    private companion object {
        const val MAX = 160
    }
}
