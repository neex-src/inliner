package com.neex.inliner.focus

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.JBColor
import com.neex.inliner.ExpansionNode
import java.awt.Color

object FocusKeys {
    /** Lives on the host (real file) editor; single source of truth per file tab. */
    val FOCUS_SET: Key<FocusSet> = Key.create("inliner.focus.set")

    /** Our range highlighters on an editor, so a refresh clears exactly ours. */
    val HIGHLIGHTERS: Key<MutableList<RangeHighlighter>> = Key.create("inliner.focus.highlighters")

    /** Our fold regions on an editor (fold-to-focus), cleared the same way. */
    val FOLD_REGIONS: Key<MutableList<FoldRegion>> = Key.create("inliner.focus.foldRegions")
}

enum class FocusKind { PARAMETER, LOCAL, FIELD }

fun focusKindOf(v: PsiVariable): FocusKind = when (v) {
    is PsiParameter -> FocusKind.PARAMETER
    is PsiField -> FocusKind.FIELD
    else -> FocusKind.LOCAL
}

class FocusEntry(
    val ptr: SmartPsiElementPointer<PsiVariable>,
    /** Highlighting is scoped to one method — for fields, the method the dialog was opened from. */
    val scopePtr: SmartPsiElementPointer<PsiMethod>,
    val colorIndex: Int,
    val name: String,
    val kind: FocusKind,
    /** Auto-created by arg→param propagation into an inlined frame; removed with its parent/frame. */
    val parent: FocusEntry? = null,
) {
    var enabled: Boolean = true

    /** The expansion frame whose creation derived this entry (null for user-created entries). */
    var sourceNode: ExpansionNode? = null

    val derived: Boolean get() = parent != null
}

class FocusSet(val hostEditor: Editor, val project: Project) : Disposable {
    val entries = mutableListOf<FocusEntry>()
    var foldToFocus = false

    /** Follow value flow (assignments, returns, call args) instead of just direct references. */
    var transitive = false

    /** Expansion frames that already carry a dispose-time cleanup for derived entries. */
    val cleanupNodes = mutableSetOf<ExpansionNode>()

    fun enabledEntries(): List<FocusEntry> = entries.filter { it.enabled }

    fun nextColorIndex(): Int {
        val used = entries.map { it.colorIndex }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    fun findEntry(variable: PsiVariable): FocusEntry? =
        entries.firstOrNull { it.ptr.element == variable }

    override fun dispose() {
        entries.clear()
        hostEditor.putUserData(FocusKeys.FOCUS_SET, null)
    }
}

object FocusPalette {
    private val base = listOf(
        JBColor(Color(0x3574F0), Color(0x548AF7)), // blue
        JBColor(Color(0x208A3C), Color(0x5FAD65)), // green
        JBColor(Color(0xC27D04), Color(0xD6AE58)), // amber
        JBColor(Color(0x955AE0), Color(0xB189F1)), // purple
        JBColor(Color(0x0E7C8E), Color(0x24A5B8)), // teal
        JBColor(Color(0xC84E6B), Color(0xE46A8A)), // pink
    )

    fun color(index: Int): JBColor = base[index % base.size]
}
