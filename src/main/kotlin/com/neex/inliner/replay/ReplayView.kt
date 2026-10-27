package com.neex.inliner.replay

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiBlockStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.xdebugger.ui.DebuggerColors
import com.neex.inliner.ExpansionNode
import com.neex.inliner.FrameRows
import com.neex.inliner.focus.FocusKeys
import com.neex.inliner.focus.FocusKind
import com.neex.inliner.recorder.Invocation
import com.neex.inliner.recorder.VarValue
import java.awt.Graphics
import java.awt.Rectangle

/**
 * The replay's cursor painted into one editor, the way a paused debugger looks: the execution
 * line; at the end of each line the current call ran so far, what that line changed; at the
 * cursor's line, every value as it is now; the call's lines that never ran in this call greyed —
 * the path it took.
 *
 * Frames inlined into that editor show the call made from their line: the child call of the
 * painted one with the frame's method — the last made up to the cursor, else the next — painted
 * whole (it is over, or hasn't started), its arguments on the signature line; nested frames the
 * same way down. Everything here is ours to remove: [clear] before each repaint.
 */
class ReplayView(val editor: Editor) : Disposable {

    private val inlays = ArrayList<Inlay<*>>()
    private val folds = ArrayList<FoldRegion>()
    private val highlighters = ArrayList<Pair<Editor, RangeHighlighter>>()

    /** [foldNotRun]: fold away what the cursor's call didn't run, too. */
    fun render(replay: Replay, foldNotRun: Boolean = false) {
        clear()
        val hit = replay.current
        val doc = editor.document
        val line = hit.site.line - 1
        if (line !in 0 until doc.lineCount) return
        highlighters += editor to editor.markupModel.addLineHighlighter(DebuggerColors.EXECUTIONPOINT_ATTRIBUTES, line, DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER)
        val call = replay.callOf(hit)
        for ((at, changed) in replay.changes(call, hit.seq)) {
            if (changed.isNotEmpty() && at != hit.site.line) values(editor, at - 1, changed, DebuggerColors.INLINED_VALUES_MODIFIED)
        }
        values(editor, line, hit.values.filter { it.name != "this" }, DebuggerColors.INLINED_VALUES_EXECUTION_LINE)
        if (!isLambda(call)) {
            val notRun = greyNotRun(editor, line, replay.linesOf(call))
            if (foldNotRun) fold(notRun)
        }
        histories(editor, replay, call, hit.seq)
        frames(editor, replay, call, hit.seq)
    }

    /**
     * For each variable tracked with focus in [call]'s method, the values it took there, at its
     * declaration: `total: 0 → 12 → [40] → 41` — in brackets the one current at [cursor].
     */
    private fun histories(target: Editor, replay: Replay, call: Invocation, cursor: Int?) {
        val project = target.project ?: return
        val host = FrameRows.frameOf(target)?.hostState?.hostEditor ?: target
        val set = host.getUserData(FocusKeys.FOCUS_SET) ?: return
        for (entry in set.enabledEntries()) {
            if (entry.kind == FocusKind.FIELD) continue
            val variable = entry.ptr.element ?: continue
            val file = variable.containingFile ?: continue
            if (PsiDocumentManager.getInstance(project).getDocument(file) !== target.document) continue
            val method = PsiTreeUtil.getParentOfType(variable, PsiMethod::class.java) ?: continue
            val name = if (method.isConstructor) "<init>" else method.name
            if (name != call.method.methodName || method.containingClass?.let(ClassUtil::getJVMClassName) != call.method.className) continue
            val (values, now) = replay.history(call, entry.name, cursor)
            if (values.isEmpty()) continue
            val text = entry.name + ": " + values.mapIndexed { i, v -> if (i == now) "[$v]" else v }.joinToString(" → ")
            text(target, target.document.getLineNumber(variable.textOffset), text, DebuggerColors.INLINED_VALUES)
        }
    }

    /** Folds away the given statements, whole lines, merged where they touch. */
    private fun fold(statements: List<TextRange>) {
        val doc = editor.document
        val lines = statements.map { doc.getLineNumber(it.startOffset)..doc.getLineNumber(it.endOffset) }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in lines) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
        }
        val folding = editor.foldingModel
        folding.runBatchFoldingOperation {
            for (r in merged) {
                val start = doc.getLineStartOffset(r.first) + doc.charsSequence.substring(doc.getLineStartOffset(r.first), doc.getLineEndOffset(r.first)).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                folding.addFoldRegion(start, doc.getLineEndOffset(r.last), FOLDED)?.let {
                    it.isExpanded = false
                    folds += it
                }
            }
        }
    }

    /** The frames of [parent] whose line made a recorded call of their method from [call]. */
    private fun frames(parent: Editor, replay: Replay, call: Invocation, cursor: Int) {
        for (node in FrameRows.framesIn(parent)) {
            val child = callOf(node, call, cursor) ?: continue
            val frame = node.fragmentEditor
            for ((at, changed) in replay.changes(child, null)) {
                if (changed.isNotEmpty()) values(frame, at - 1, changed, DebuggerColors.INLINED_VALUES_MODIFIED)
            }
            val first = child.hits.firstOrNull() ?: continue
            if (node.methodRange.isValid) {
                val signature = frame.document.getLineNumber(node.methodRange.startOffset)
                values(frame, signature, first.values.filter { it.name != "this" }, DebuggerColors.INLINED_VALUES)
            }
            if (!isLambda(child)) greyNotRun(frame, first.site.line - 1, replay.linesOf(child))
            histories(frame, replay, child, null)
            frames(frame, replay, child, cursor)
        }
    }

    private fun callOf(node: ExpansionNode, call: Invocation, cursor: Int): Invocation? {
        if (!node.callRange.isValid) return null
        val line = node.parentEditor.document.getLineNumber(node.callRange.startOffset) + 1
        val method = node.methodPtr.element ?: return null
        val name = if (method.isConstructor) "<init>" else method.name
        val made = call.children.filter { it.method.methodName == name && it.callSite?.site?.line == line }
        return made.lastOrNull { it.callSite!!.seq <= cursor } ?: made.firstOrNull()
    }

    private fun isLambda(call: Invocation) = call.method.methodName.startsWith("lambda$")

    private fun values(target: Editor, line: Int, values: List<VarValue>, key: TextAttributesKey) {
        if (values.isEmpty()) return
        text(target, line, values.joinToString(", ") { "${it.name} = ${it.text}" }, key)
    }

    private fun text(target: Editor, line: Int, text: String, key: TextAttributesKey) {
        if (line !in 0 until target.document.lineCount) return
        val shown = if (text.length > MAX) text.take(MAX) + "…" else text
        target.inlayModel.addAfterLineEndElement(target.document.getLineEndOffset(line), true, ValuesRenderer(shown, key))?.let { inlays += it }
    }

    /**
     * Greys the statements of the method at [line] whose first line the call never reached, and
     * returns them. Not for lambdas: their lines are the method's.
     */
    private fun greyNotRun(target: Editor, line: Int, ran: Set<Int>): List<TextRange> {
        val out = ArrayList<TextRange>()
        val project = target.project ?: return out
        val doc = target.document
        if (line !in 0 until doc.lineCount) return out
        val file = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return out
        val start = doc.getLineStartOffset(line)
        val at = file.findElementAt(start + doc.charsSequence.substring(start, doc.getLineEndOffset(line)).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
        val method = PsiTreeUtil.getParentOfType(at, PsiMethod::class.java) ?: return out
        val body = method.body ?: return out
        val grey = target.colorsScheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)?.takeIf { it.foregroundColor != null }
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
            highlighters += target to target.markupModel.addRangeHighlighter(from, to, HighlighterLayer.ADDITIONAL_SYNTAX, grey, HighlighterTargetArea.EXACT_RANGE)
            out += statement.textRange
        }
        return out
    }

    fun clear() {
        inlays.forEach { if (it.isValid) it.dispose() }
        inlays.clear()
        if (folds.isNotEmpty() && !editor.isDisposed) {
            editor.foldingModel.runBatchFoldingOperation { folds.forEach { if (it.isValid) editor.foldingModel.removeFoldRegion(it) } }
        }
        folds.clear()
        highlighters.forEach { (target, h) -> if (!target.isDisposed && h.isValid) target.markupModel.removeHighlighter(h) }
        highlighters.clear()
    }

    override fun dispose() = clear()

    /** What [ReplayView] put at the ends of lines of [target] (default: its own editor), by line (0-based), " | " between several: for tests. */
    internal fun inlayTexts(target: Editor = editor): Map<Int, String> =
        inlays.filter { it.isValid && it.editor === target }
            .groupBy({ target.document.getLineNumber(it.offset) }, { (it.renderer as ValuesRenderer).text })
            .mapValues { it.value.joinToString(" | ") }

    internal fun highlighters(target: Editor = editor): List<RangeHighlighter> =
        highlighters.filter { it.first === target }.map { it.second }

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

    internal fun folds(): List<FoldRegion> = folds.filter { it.isValid }

    private companion object {
        const val MAX = 160
        const val FOLDED = "⋯ didn't run"
    }
}
