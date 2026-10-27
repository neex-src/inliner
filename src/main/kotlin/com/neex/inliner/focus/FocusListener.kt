package com.neex.inliner.focus

import com.intellij.openapi.editor.Editor
import com.intellij.util.messages.Topic

/** Focus was repainted under [host] (something tracked, untracked, folded): for views that build on it. */
fun interface FocusListener {
    fun focusChanged(host: Editor)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<FocusListener> = Topic(FocusListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
