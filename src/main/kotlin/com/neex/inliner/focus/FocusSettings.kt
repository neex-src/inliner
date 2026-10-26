package com.neex.inliner.focus

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

/** Focus defaults, persisted per installation. A view ([FocusSet]) starts from these. */
@Service(Service.Level.APP)
@State(name = "InlineReadingModeFocus", storages = [Storage("inlineReadingMode.xml")])
class FocusSettings : SimplePersistentStateComponent<FocusSettings.Options>(Options()) {

    class Options : BaseState() {
        var foldRule by enum(FoldRule.TOUCHES)
        var markWrites by property(true)
    }

    val foldRule: FoldRule get() = state.foldRule
    val markWrites: Boolean get() = state.markWrites

    companion object {
        fun getInstance(): FocusSettings = service()
    }
}

/** Settings → Tools → Inline Reading Mode. Applying re-renders every open focus view. */
class FocusConfigurable : BoundConfigurable("Inline Reading Mode") {

    override fun createPanel(): DialogPanel {
        val options = FocusSettings.getInstance().state
        return panel {
            group("Focus") {
                row("Fold to focus keeps:") {
                    comboBox(FoldRule.entries, textListCellRenderer { it?.title })
                        .bindItem({ options.foldRule }, { options.foldRule = it ?: FoldRule.TOUCHES })
                }
                row {
                    checkBox("Mark where a tracked value is written (a box around the name)")
                        .bindSelected(options::markWrites)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        val rule = FocusSettings.getInstance().foldRule
        for (editor in EditorFactory.getInstance().allEditors) {
            val set = editor.getUserData(FocusKeys.FOCUS_SET) ?: continue
            set.foldRule = rule
            FocusService.refreshAll(set)
        }
    }
}
