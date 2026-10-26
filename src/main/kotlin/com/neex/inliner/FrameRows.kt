package com.neex.inliner

import com.intellij.openapi.editor.Editor

/**
 * The host editor and its inline frames read as one column of rows, the way they are drawn: a
 * frame's rows sit between the line it hangs under and the next line of its parent. A [Row] is
 * one visual line of one editor — the host or a frame's fragment editor. [below] and [above]
 * step across frame borders: into a frame hanging under a line, out of a frame past its first
 * or last row, between frames hanging under the same line, through any depth of nesting.
 *
 * Pure geometry over live state (inlays, folds, frame nodes): nothing here moves a caret.
 */
object FrameRows {

    data class Row(val editor: Editor, val visualLine: Int)

    fun below(row: Row): Row? {
        framesUnder(row).firstOrNull()?.let { return firstRow(it) }
        return nextOrAfter(row.editor, row.visualLine)
    }

    fun above(row: Row): Row? {
        if (row.visualLine > bounds(row.editor).first) {
            val prev = Row(row.editor, row.visualLine - 1)
            return framesUnder(prev).lastOrNull()?.let(::lastRowDeep) ?: prev
        }
        return frameOf(row.editor)?.let(::before)
    }

    /** Visual lines of [editor] that are rows: everything for a host, the method for a frame. */
    fun bounds(editor: Editor): IntRange {
        val node = frameOf(editor)
        if (node == null || !node.methodRange.isValid) {
            return 0..editor.offsetToVisualPosition(editor.document.textLength).line
        }
        val doc = editor.document
        val start = doc.getLineStartOffset(doc.getLineNumber(node.methodRange.startOffset))
        return editor.offsetToVisualPosition(start).line..editor.offsetToVisualPosition(node.methodRange.endOffset).line
    }

    /** Live frames hanging under [row], top to bottom. */
    fun framesUnder(row: Row): List<ExpansionNode> {
        val frames = framesIn(row.editor)
        if (frames.isEmpty()) return emptyList()
        return row.editor.inlayModel.getBlockElementsForVisualLine(row.visualLine, false)
            .mapNotNull { inlay -> frames.firstOrNull { it.inlay === inlay } }
    }

    /** Frames embedded directly in [editor] (not their nested frames). */
    fun framesIn(editor: Editor): List<ExpansionNode> {
        val nodes = frameOf(editor)?.children ?: editor.getUserData(Keys.HOST_STATE)?.roots ?: return emptyList()
        return nodes.filter { !it.disposed && it.inlay.isValid && !it.fragmentEditor.isDisposed }
    }

    fun frameOf(editor: Editor): ExpansionNode? = editor.getUserData(Keys.FRAME_NODE)?.takeUnless { it.disposed }

    /** The row of the parent editor that [node] hangs under. */
    fun anchorRow(node: ExpansionNode): Row =
        Row(node.parentEditor, node.parentEditor.offsetToVisualPosition(node.inlay.offset).line)

    fun firstRow(node: ExpansionNode): Row = Row(node.fragmentEditor, bounds(node.fragmentEditor).first)

    /** The bottom row of [node] as drawn: its last line, or the last row of a frame hanging under it. */
    private fun lastRowDeep(node: ExpansionNode): Row {
        val last = Row(node.fragmentEditor, bounds(node.fragmentEditor).last)
        return framesUnder(last).lastOrNull()?.let(::lastRowDeep) ?: last
    }

    private fun nextOrAfter(editor: Editor, visualLine: Int): Row? =
        if (visualLine < bounds(editor).last) Row(editor, visualLine + 1) else frameOf(editor)?.let(::after)

    private fun after(node: ExpansionNode): Row? {
        val anchor = anchorRow(node)
        val siblings = framesUnder(anchor)
        val next = siblings.getOrNull(siblings.indexOf(node) + 1)
        return if (next != null) firstRow(next) else nextOrAfter(anchor.editor, anchor.visualLine)
    }

    private fun before(node: ExpansionNode): Row {
        val anchor = anchorRow(node)
        val siblings = framesUnder(anchor)
        val prev = siblings.getOrNull(siblings.indexOf(node) - 1)
        return if (prev != null) lastRowDeep(prev) else anchor
    }
}
