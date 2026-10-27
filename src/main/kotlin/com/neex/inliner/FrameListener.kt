package com.neex.inliner

import com.intellij.openapi.editor.Editor
import com.intellij.util.messages.Topic

/** Frames came or went under [host]: for whoever paints into frames (the replay). */
fun interface FrameListener {
    fun framesChanged(host: Editor)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<FrameListener> = Topic(FrameListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
