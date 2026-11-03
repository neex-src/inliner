package com.neex.inliner.focus

import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
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

    /** A row. Plain enough for the renderer: it runs without a read action, so it reads no PSI. */
    internal sealed class Item {
        class Tracked(val entry: FocusEntry, val scopeName: String?) : Item()
        class Candidate(val candidate: FocusCandidate) : Item()
    }

    /**
     * The rows for [pattern]: tracked entries first, then the untracked candidates (not those an edit has invalidated). A non-blank
     * pattern filters the tracked entries too. Reads PSI (smart pointers): call in a read action.
     */
    internal fun listing(set: FocusSet, candidates: List<FocusCandidate>, pattern: String): List<Item> {
        val matches = FocusCandidates.nameFilter(pattern)
        val tracked = set.entries.mapNotNull { it.ptr.element }.toSet()
        return set.entries.filter { matches(it.name) }.map { Item.Tracked(it, it.scopePtr.element?.name) } +
            FocusCandidates.filter(candidates.filter { it.variable.isValid }, pattern).filter { it.variable !in tracked }.map { Item.Candidate(it) }
    }

    /** Adds [candidate] as the dialog's Enter does; null when an edit since the dialog opened made it (or the method) stale. */
    internal fun addCandidate(set: FocusSet, candidate: FocusCandidate, method: PsiMethod): FocusEntry? =
        if (candidate.variable.isValid && method.isValid) FocusService.add(set, candidate.variable, method) else null

    /** Typing a filter means "find one to add": select the first matching candidate, not a tracked entry. */
    internal fun preferredIndex(items: List<Item>, pattern: String): Int =
        if (pattern.isBlank()) 0 else items.indexOfFirst { it is Item.Candidate }.coerceAtLeast(0)

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

        fun rebuild(select: (List<Item>) -> Int) {
            val items = ApplicationManager.getApplication().runReadAction(
                Computable { listing(set, candidates, searchField.text) },
            )
            listModel.replaceAll(items)
            if (items.isNotEmpty()) {
                list.selectedIndex = select(items).coerceIn(0, items.size - 1)
                list.ensureIndexIsVisible(list.selectedIndex)
            }
        }

        // Called from Swing listeners, which hold no lock on 262: adding creates smart pointers,
        // refreshAll reads PSI and rewrites highlighters and folds. invokeLater runs it under the
        // write-intent lock an action gets (WriteIntentReadAction would too, but is experimental API).
        fun act(delete: Boolean) {
            val item = list.selectedValue ?: return
            if (delete && item is Item.Candidate) return
            val keep = list.selectedIndex
            ApplicationManager.getApplication().invokeLater {
                if (set.hostEditor.isDisposed) return@invokeLater
                // the row to keep selected: the entry just toggled or added, so Enter again undoes it
                val acted: FocusEntry? = when (item) {
                    is Item.Tracked -> if (delete) {
                        FocusService.remove(set, item.entry)
                        null
                    } else {
                        item.entry.enabled = !item.entry.enabled
                        item.entry
                    }
                    is Item.Candidate -> addCandidate(set, item.candidate, method)
                }
                FocusService.refreshAll(set)
                rebuild { items -> items.indexOfFirst { it is Item.Tracked && it.entry === acted }.takeIf { it >= 0 } ?: keep }
            }
        }

        rebuild { 0 }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild { preferredIndex(it, searchField.text) }
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
                    value.scopeName?.let { append("  in $it()", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
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
