package com.neex.inliner

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.neex.inliner.FrameRows.Row
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * The caret treats frames as more lines of the code around them. Up / Down / Page keys walk the
 * [FrameRows] column, Left at a line start and Right at a line end continue into the row above /
 * below; moving into another editor moves the keyboard focus there. Inside a frame the caret
 * can't leave the method, and the host scrolls to keep it in view — a frame never scrolls.
 *
 * Wherever frames are involved (a frame, or a host that has frames) vertical moves are all ours,
 * not only the ones that cross: the column to come back to ([HostState.desiredX], an x in the
 * host's coordinates) has to survive the trip through a short `}` in a frame, and the platform
 * keeps its own per editor, out of reach. Any caret move that isn't ours forgets it, as the
 * platform's does. Editors without frames, and multiple carets, are left to the platform.
 */
object FrameCaret {

    enum class Move { UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN }

    /** Set while we move a caret, so the listeners below don't take it for the user's move. */
    private var moving = false

    /** Performs [move] if frames are involved; false = not ours, let the platform do it. */
    fun move(editor: Editor, move: Move): Boolean {
        if (editor.caretModel.caretCount != 1) return false
        // Registered order="first", so we run before the completion popup's own Up/Down/Page handlers:
        // while a lookup is open the keys are its (it moves the selection, not the caret).
        if (LookupManager.getActiveLookup(editor) != null) return false
        if (FrameRows.frameOf(editor) == null && FrameRows.framesIn(editor).isEmpty()) return false
        val row = Row(editor, editor.caretModel.visualPosition.line)
        return when (move) {
            Move.DOWN -> vertical(editor, FrameRows.below(row))
            Move.UP -> vertical(editor, FrameRows.above(row))
            Move.RIGHT -> atRowEnd(editor) && cross(editor, FrameRows.below(row), Edge.START)
            Move.LEFT -> atRowStart(editor) && cross(editor, FrameRows.above(row), Edge.END)
            Move.PAGE_DOWN -> page(editor, row, FrameRows::below)
            Move.PAGE_UP -> page(editor, row, FrameRows::above)
        }
    }

    private enum class Edge { START, END }

    private fun vertical(from: Editor, target: Row?): Boolean {
        if (target == null) return false
        val state = hostStateOf(from) ?: return false
        val x = state.desiredX ?: caretXInHost(from)
        placeAt(target, x)
        state.desiredX = x
        reveal(target.editor)
        return true
    }

    private fun cross(from: Editor, target: Row?, edge: Edge): Boolean {
        if (target == null || target.editor === from) return false
        val to = target.editor
        ours(to) {
            when (edge) {
                Edge.START -> to.caretModel.moveToVisualPosition(VisualPosition(target.visualLine, 0))
                Edge.END -> to.caretModel.moveToOffset(EditorUtil.getVisualLineEndOffset(to, target.visualLine))
            }
        }
        focus(to)
        reveal(to)
        return true
    }

    /** A page is as many rows as fit the host's visible height; the caret keeps its place on screen. */
    private fun page(editor: Editor, start: Row, step: (Row) -> Row?): Boolean {
        val state = hostStateOf(editor) ?: return false
        val host = state.hostEditor
        var row = start
        var walked = 0
        while (walked + row.editor.lineHeight <= host.scrollingModel.visibleArea.height) {
            val next = step(row) ?: break
            walked += row.editor.lineHeight
            row = next
        }
        if (row == start) return false
        val x = state.desiredX ?: caretXInHost(editor)
        val before = caretYInHost(editor)
        placeAt(row, x)
        state.desiredX = x
        host.scrollingModel.scrollVertically(host.scrollingModel.verticalScrollOffset + caretYInHost(row.editor) - before)
        reveal(row.editor)
        return true
    }

    /** Caret of [row]'s editor on that row, as close to host-x [x] as the line allows; focus follows. */
    private fun placeAt(row: Row, x: Int) {
        val to = row.editor
        val local = SwingUtilities.convertPoint(hostOf(to).contentComponent, Point(x, 0), to.contentComponent).x
        val column = to.xyToVisualPosition(Point(local.coerceAtLeast(0), to.visualLineToY(row.visualLine))).column
        ours(to) { to.caretModel.moveToVisualPosition(VisualPosition(row.visualLine, column)) }
        focus(to)
    }

    private inline fun ours(editor: Editor, block: () -> Unit) {
        moving = true
        try {
            editor.caretModel.removeSecondaryCarets()
            editor.selectionModel.removeSelection()
            block()
        } finally {
            moving = false
        }
    }

    /** Test seam: headless there is no keyboard focus; tests follow the caret's editor here. */
    internal var testFocus: ((Editor) -> Unit)? = null

    private fun focus(editor: Editor) {
        testFocus?.let { it(editor); return }
        if (!editor.contentComponent.hasFocus()) {
            IdeFocusManager.getGlobalInstance().requestFocus(editor.contentComponent, true)
        }
    }

    private fun atRowStart(editor: Editor): Boolean {
        val caret = editor.caretModel.primaryCaret
        if (caret.hasSelection()) return false
        val line = caret.visualPosition.line
        val node = FrameRows.frameOf(editor)
        val start = if (node != null && line == FrameRows.bounds(editor).first) {
            editor.document.getLineStartOffset(editor.document.getLineNumber(node.methodRange.startOffset))
        } else {
            editor.visualPositionToOffset(VisualPosition(line, 0))
        }
        return caret.offset <= start
    }

    private fun atRowEnd(editor: Editor): Boolean {
        val caret = editor.caretModel.primaryCaret
        if (caret.hasSelection()) return false
        val node = FrameRows.frameOf(editor)
        val lineEnd = EditorUtil.getVisualLineEndOffset(editor, caret.visualPosition.line)
        val end = if (node != null) minOf(lineEnd, node.methodRange.endOffset) else lineEnd
        return caret.offset >= end
    }

    /** Make the caret visible: a frame's caret by scrolling the host, with a line of margin. */
    fun reveal(editor: Editor) {
        if (FrameRows.frameOf(editor) == null) {
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            return
        }
        val host = hostOf(editor)
        val p = editor.visualPositionToXY(editor.caretModel.visualPosition)
        val r = Rectangle(p.x, p.y - editor.lineHeight, 1, editor.lineHeight * 3)
        host.contentComponent.scrollRectToVisible(SwingUtilities.convertRectangle(editor.contentComponent, r, host.contentComponent))
    }

    /**
     * Keeps a frame's caret (and selection) on the method: End, Ctrl+End, a search hit or a
     * click on the clipped margins would otherwise land in the hidden rest of the file. Keeps
     * the caret in view while the frame has the focus, and forgets the column on moves not ours.
     *
     * Not while a [FrameEditSession] runs: the flow owns the caret then (an in-place template
     * parks it at the start of its scope, which may be the whole file, and builds from there).
     * The session clamps once more when it ends.
     */
    fun install(node: ExpansionNode) {
        node.fragmentEditor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = node.fragmentEditor
                if (node.disposed) return
                if (!moving) node.hostState.desiredX = null
                if (FrameEditSession.isOpen(editor)) return
                clamp(node)
                if (!moving && editor.contentComponent.hasFocus()) reveal(editor)
            }
        }, node)
    }

    /** Puts [node]'s caret and selection back on the method if they left it. */
    fun clamp(node: ExpansionNode) {
        if (!node.methodRange.isValid) return
        val doc = node.fragmentEditor.document
        val lo = doc.getLineStartOffset(doc.getLineNumber(node.methodRange.startOffset))
        val hi = node.methodRange.endOffset
        val caret = node.fragmentEditor.caretModel.primaryCaret
        if (caret.offset !in lo..hi) caret.moveToOffset(caret.offset.coerceIn(lo, hi))
        if (caret.hasSelection() && (caret.selectionStart < lo || caret.selectionEnd > hi)) {
            caret.setSelection(caret.selectionStart.coerceIn(lo, hi), caret.selectionEnd.coerceIn(lo, hi))
        }
    }

    /** The host's half of the column memory: its own caret moves forget the column too. */
    fun install(state: HostState) {
        state.hostEditor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (!moving) state.desiredX = null
            }
        }, state)
    }

    private fun hostStateOf(editor: Editor): HostState? =
        FrameRows.frameOf(editor)?.hostState ?: editor.getUserData(Keys.HOST_STATE)

    private fun hostOf(editor: Editor): Editor = FrameRows.frameOf(editor)?.hostState?.hostEditor ?: editor

    private fun caretXInHost(editor: Editor): Int {
        val p = editor.visualPositionToXY(editor.caretModel.visualPosition)
        return SwingUtilities.convertPoint(editor.contentComponent, p, hostOf(editor).contentComponent).x
    }

    private fun caretYInHost(editor: Editor): Int {
        val p = editor.visualPositionToXY(editor.caretModel.visualPosition)
        return SwingUtilities.convertPoint(editor.contentComponent, p, hostOf(editor).contentComponent).y
    }
}

/** EditorUp & co.: ours where frames are involved (see [FrameCaret]), else the platform's own move. */
abstract class FrameCaretHandler(
    private val original: EditorActionHandler,
    private val move: FrameCaret.Move,
) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!FrameCaret.move(editor, move)) original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        original.isEnabled(editor, caret, dataContext)
}

class FrameUpHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.UP)
class FrameDownHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.DOWN)
class FrameLeftHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.LEFT)
class FrameRightHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.RIGHT)
class FramePageUpHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.PAGE_UP)
class FramePageDownHandler(original: EditorActionHandler) : FrameCaretHandler(original, FrameCaret.Move.PAGE_DOWN)
