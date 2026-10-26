package com.neex.inliner

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware

/**
 * A platform action that edits, working in a frame as it does in the callee's own editor: the
 * platform's action itself, run inside a [FrameEditSession] — the platform turns these off in a
 * viewer, and a frame is one. Bound to the platform action's own shortcut (so a remapped one
 * follows); [FrameEditPromoter] puts it first in a frame, and outside frames it is off.
 */
abstract class FrameEditAction(private val platformId: String) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.let(::editableFrameOf) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val node = e.getData(CommonDataKeys.EDITOR)?.let(::editableFrameOf) ?: return
        val platform = ActionManager.getInstance().getAction(platformId) ?: return
        FrameEditSession.open(node).dispatching {
            // Updated now, inside the session: the platform's update is what turns it off in a viewer.
            val event = AnActionEvent.createEvent(platform, e.dataContext, null, e.place, ActionUiKind.NONE, e.inputEvent)
            ActionUtil.updateAction(platform, event)
            ActionUtil.performAction(platform, event)
        }
    }
}

/** Alt+Enter: the callee's intentions and quick fixes at the frame's caret; the chosen one edits its file. */
class FrameIntentionsAction : FrameEditAction("ShowIntentionActions")

/** Undo in a frame: the callee file's undo, as in its own editor (an intention applied here, say). */
class FrameUndoAction : FrameEditAction(IdeActions.ACTION_UNDO)

class FrameRedoAction : FrameEditAction(IdeActions.ACTION_REDO)

class FrameEditPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val ours = actions.filterIsInstance<FrameEditAction>()
        if (ours.isEmpty() || CommonDataKeys.EDITOR.getData(context)?.let(::editableFrameOf) == null) return null
        return ours + actions.filterNot { it is FrameEditAction }
    }
}

/** The frame [editor] renders, if it can take edits (a decompiled mirror's PSI isn't its document's). */
internal fun editableFrameOf(editor: Editor): ExpansionNode? =
    editor.getUserData(Keys.FRAME_NODE)?.takeIf { !it.disposed && it.pinnedPsiFile == null }
