package com.neex.inliner.recorder.ide

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.neex.inliner.recorder.Budget
import com.neex.inliner.recorder.RecordScope

/** What the recorder records and how much, persisted per installation. */
@Service(Service.Level.APP)
@State(name = "InlineReadingModeRecorder", storages = [Storage("inlineReadingMode.xml")])
class RecorderSettings : SimplePersistentStateComponent<RecorderSettings.Options>(Options()) {

    class Options : BaseState() {
        /** Packages recorded besides the project's own, comma-separated. */
        var include by string("")

        /** Packages never recorded, besides the built-in framework list, comma-separated. */
        var exclude by string("")
        var perLine by property(Budget().perLocation)
        var total by property(Budget().total)
    }

    /**
     * The project's packages and the extra ones; the built-in framework list only where it doesn't
     * cover one of those (a project living under `org.apache.*` is still recorded), the user's own
     * exclusions always.
     */
    fun scope(projectPackages: List<String>): RecordScope {
        val include = projectPackages + split(state.include)
        val builtIn = RecordScope.DEFAULT_EXCLUDES.filter { framework -> include.none { RecordScope(listOf(framework), emptyList()).covers(it) } }
        return RecordScope(include, builtIn + split(state.exclude))
    }

    fun budget(): Budget = Budget(perLocation = state.perLine.coerceAtLeast(1), total = state.total.coerceAtLeast(1))

    companion object {
        fun getInstance(): RecorderSettings = service()

        fun split(list: String?): List<String> = list.orEmpty().split(',', ' ', '\n').map(String::trim).filter(String::isNotEmpty)
    }
}
