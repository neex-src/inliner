package com.neex.inliner

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

object FragmentEditorFactory {

    fun createFragmentViewer(
        project: Project,
        calleeDoc: Document,
        calleeVFile: VirtualFile?,
        calleeFileType: FileType,
        methodRange: TextRange,
    ): EditorEx {
        // UNTYPED, not PREVIEW: the platform's editor data rule (PSI_ELEMENT, LANGUAGE, MODULE, …)
        // skips PREVIEW and CONSOLE editors, and without it Alt+F7 & co. find nothing at the caret.
        val editor = EditorFactory.getInstance().createViewer(calleeDoc, project, EditorKind.UNTYPED) as EditorEx
        // The rule also wants the editor's own file (createEditor(…, file, …) doesn't set it).
        calleeVFile?.let { editor.setFile(it) }
        // A detached document (decompiled mirror) has no VirtualFile — highlight by file type.
        editor.highlighter = if (calleeVFile != null) {
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, calleeVFile)
        } else {
            EditorHighlighterFactory.getInstance().createEditorHighlighter(project, calleeFileType)
        }
        editor.settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            additionalLinesCount = 0
            isAdditionalPageAtBottom = false
            isCaretRowShown = false
            isRightMarginShown = false
            isIndentGuidesShown = false
        }
        clipToRange(editor, methodRange)
        editor.backgroundColor = frameBackground()
        editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        editor.scrollPane.isWheelScrollingEnabled = false
        editor.scrollPane.border = JBUI.Borders.empty()
        return editor
    }

    /** Hide everything outside [range] with never-expanding, zero-placeholder fold regions. */
    fun clipToRange(editor: EditorEx, range: TextRange) {
        val doc = editor.document
        val clipStart = doc.getLineStartOffset(doc.getLineNumber(range.startOffset))
        val clipEnd = range.endOffset
        val fm = editor.foldingModel as FoldingModelEx
        fm.runBatchFoldingOperation {
            fm.clearFoldRegions()
            if (clipStart > 0) {
                fm.createFoldRegion(0, clipStart, "", null, true)?.apply { isExpanded = false }
            }
            if (clipEnd < doc.textLength) {
                fm.createFoldRegion(clipEnd, doc.textLength, "", null, true)?.apply { isExpanded = false }
            }
        }
    }

    fun createHeader(text: String, onClick: () -> Unit): JComponent {
        val label = JBLabel(text)
        label.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        val panel = JPanel(BorderLayout())
        panel.add(label, BorderLayout.WEST)
        panel.background = headerBackground()
        panel.isOpaque = true
        panel.border = JBUI.Borders.empty(2, 8)
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.toolTipText = "Click to collapse (Esc)"
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
        return panel
    }

    fun createWrapper(header: JComponent, editorComponent: JComponent, leftIndentPx: Int, inner: JPanel): JPanel {
        inner.add(header, BorderLayout.NORTH)
        inner.add(editorComponent, BorderLayout.CENTER)
        inner.border = BorderFactory.createLineBorder(JBColor.border())
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = BorderFactory.createEmptyBorder(2, leftIndentPx, 2, 0)
        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    /** The fragment never scrolls itself; the wheel always drives the outermost (host) editor. */
    fun redirectWheelTo(fragEditor: EditorEx, hostEditor: Editor) {
        fragEditor.scrollPane.addMouseWheelListener { e ->
            val target = (hostEditor as? EditorEx)?.scrollPane ?: return@addMouseWheelListener
            target.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, target))
        }
    }

    /**
     * Adapter over the embedding API — the one deliberately isolated call site.
     * Fallback if this impl API breaks in a future platform: ComponentInlayKt.addComponentInlay.
     */
    fun embed(parentEditor: EditorEx, offset: Int, component: JComponent): Inlay<*>? {
        val props = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(),
            null,
            true,   // relatesToPrecedingText
            false,  // showAbove
            true,   // showWhenFolded
            false,  // fullWidth
            0,      // priority
            offset,
        )
        return EditorEmbeddedComponentManager.getInstance().addComponent(parentEditor, component, props)
    }

    /** Slightly off the host editor background, so where the inline frame starts/ends is visible. */
    private fun frameBackground(): Color {
        val base = EditorColorsManager.getInstance().globalScheme.defaultBackground
        return if (ColorUtil.isDark(base)) ColorUtil.mix(base, Color.WHITE, 0.05) else ColorUtil.mix(base, Color.BLACK, 0.04)
    }

    private fun headerBackground(): Color {
        val base = EditorColorsManager.getInstance().globalScheme.defaultBackground
        return if (ColorUtil.isDark(base)) ColorUtil.mix(base, Color.WHITE, 0.11) else ColorUtil.mix(base, Color.BLACK, 0.08)
    }
}
