package com.neex.inliner

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.inliner.focus.FocusKeys
import com.neex.inliner.focus.FocusService
import com.neex.inliner.focus.FocusSettings
import com.neex.inliner.focus.FoldRule
import com.neex.inliner.recorder.ide.RecorderSettings

/** Settings → Tools → Inline Reading Mode. Applying re-renders every open focus view. */
class InlinerConfigurable : BoundConfigurable("Inline Reading Mode") {

    override fun createPanel(): DialogPanel {
        val focus = FocusSettings.getInstance().state
        val recorder = RecorderSettings.getInstance().state
        return panel {
            group("Expansion") {
                row {
                    checkBox("Show a gutter icon on lines with a call that can be expanded")
                        .bindSelected(focus::gutterIcons)
                }
            }
            group("Focus") {
                row("Fold to focus keeps:") {
                    comboBox(FoldRule.entries, textListCellRenderer { it?.title })
                        .bindItem({ focus.foldRule }, { focus.foldRule = it ?: FoldRule.TOUCHES })
                }
                row {
                    checkBox("Mark where a tracked value is written (a box around the name)")
                        .bindSelected(focus::markWrites)
                }
            }
            group("Recorder") {
                row("Also record packages:") {
                    textField().columns(40).bindText({ recorder.include.orEmpty() }, { recorder.include = it })
                        .comment("The project's own packages are always recorded. Comma-separated.")
                }
                row("Never record packages:") {
                    textField().columns(40).bindText({ recorder.exclude.orEmpty() }, { recorder.exclude = it })
                        .comment("On top of the built-in list: JDK, Spring, Apache, Hibernate, logging, …")
                }
                row("Lines kept per line of code:") {
                    intTextField(1..1_000_000).bindIntText(recorder::perLine)
                        .comment("Then that line stops being recorded: a hot loop costs this much, once.")
                }
                row("Lines kept per recording:") {
                    intTextField(1..10_000_000).bindIntText(recorder::total)
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
        for (project in ProjectManager.getInstance().openProjects) {
            DaemonCodeAnalyzer.getInstance(project).restart(this)
        }
    }
}
