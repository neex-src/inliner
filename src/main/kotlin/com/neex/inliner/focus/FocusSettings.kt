package com.neex.inliner.focus

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Focus defaults, persisted per installation. A view ([FocusSet]) starts from these. */
@Service(Service.Level.APP)
@State(name = "InlineReadingModeFocus", storages = [Storage("inlineReadingMode.xml")])
class FocusSettings : SimplePersistentStateComponent<FocusSettings.Options>(Options()) {

    class Options : BaseState() {
        var foldRule by enum(FoldRule.TOUCHES)
        var markWrites by property(true)
        var gutterIcons by property(true)
    }

    val foldRule: FoldRule get() = state.foldRule
    val markWrites: Boolean get() = state.markWrites
    val gutterIcons: Boolean get() = state.gutterIcons

    companion object {
        fun getInstance(): FocusSettings = service()
    }
}
