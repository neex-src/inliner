package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.neex.inliner.InlineExpansionService
import com.neex.inliner.Keys
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

class FocusPopupAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabled = editor != null && project != null &&
            InlineExpansionService.psiFileFor(editor, project) is PsiJavaFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        FocusPopup.show(editor, project)
    }
}

object FocusPopup {

    private sealed class Item {
        class Tracked(val entry: FocusEntry) : Item()
        class Candidate(val candidate: FocusCandidate) : Item()
    }

    fun show(editor: Editor, project: Project) {
        if (editor.getUserData(Keys.FRAME_NODE)?.pinnedPsiFile == null) {
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        val psiFile = InlineExpansionService.psiFileFor(editor, project) ?: return
        val collected = FocusCandidates.collect(psiFile, editor.caretModel.offset)
        if (collected == null) {
            if (editor.component.isShowing) {
                HintManager.getInstance().showInformationHint(editor, "Place the caret inside a method")
            }
            return
        }
        val (method, candidates) = collected
        val set = FocusService.focusSet(editor, project, create = true)!!

        val searchField = SearchTextField(false)
        val listModel = com.intellij.ui.CollectionListModel<Item>()
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ItemRenderer()

        fun rebuild() {
            val items = mutableListOf<Item>()
            set.entries.forEach { items += Item.Tracked(it) }
            val tracked = set.entries.mapNotNull { it.ptr.element }.toSet()
            FocusCandidates.filter(candidates, searchField.text)
                .filter { it.variable !in tracked }
                .forEach { items += Item.Candidate(it) }
            val keep = list.selectedIndex
            listModel.replaceAll(items)
            if (items.isNotEmpty()) list.selectedIndex = keep.coerceIn(0, items.size - 1)
        }

        fun act(delete: Boolean) {
            when (val item = list.selectedValue) {
                is Item.Tracked -> if (delete) FocusService.remove(set, item.entry) else item.entry.enabled = !item.entry.enabled
                is Item.Candidate -> if (!delete) FocusService.add(set, item.candidate.variable, method)
                null -> return
            }
            FocusService.refreshAll(set)
            rebuild()
        }

        rebuild()

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> { move(list, 1); e.consume() }
                    KeyEvent.VK_UP -> { move(list, -1); e.consume() }
                    KeyEvent.VK_ENTER -> { act(delete = false); e.consume() }
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { act(delete = false); e.consume() }
                    KeyEvent.VK_DELETE -> { act(delete = true); e.consume() }
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) act(delete = false)
            }
        })

        val hint = JBLabel("Enter — add / toggle on-off · Del (in list) — remove · Esc — close")
        hint.foreground = JBColorGray
        hint.border = JBUI.Borders.empty(2, 6)

        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(searchField, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(hint, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(440), JBUI.scale(320))

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField.textEditor)
            .setTitle("Focus Symbols — ${method.name}()")
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setCancelKeyEnabled(true)
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun move(list: JBList<*>, delta: Int) {
        val size = list.model.size
        if (size == 0) return
        list.selectedIndex = ((list.selectedIndex + delta).coerceIn(0, size - 1))
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private val JBColorGray = com.intellij.ui.JBColor.namedColor("Label.infoForeground", com.intellij.ui.JBColor.GRAY)

    private class ItemRenderer : ColoredListCellRenderer<Item>() {
        override fun customizeCellRenderer(list: JList<out Item>, value: Item?, index: Int, selected: Boolean, hasFocus: Boolean) {
            when (value) {
                is Item.Tracked -> {
                    icon = kindIcon(value.entry.kind)
                    append("● ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FocusPalette.color(value.entry.colorIndex)))
                    val style = if (value.entry.enabled) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    append(value.entry.name, style)
                    if (!value.entry.enabled) append("  (off)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    value.entry.scopePtr.element?.name?.let { append("  in $it()", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    if (value.entry.derived) append("  ↳ ${value.entry.parent?.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Item.Candidate -> {
                    icon = kindIcon(value.candidate.kind)
                    append(value.candidate.name)
                    append("  ${value.candidate.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                null -> {}
            }
        }

        private fun kindIcon(kind: FocusKind): Icon = when (kind) {
            FocusKind.PARAMETER -> AllIcons.Nodes.Parameter
            FocusKind.LOCAL -> AllIcons.Nodes.Variable
            FocusKind.FIELD -> AllIcons.Nodes.Field
        }
    }
}
