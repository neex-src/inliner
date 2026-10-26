package com.neex.inliner

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * A frame's panel (header above, the frame's editor below), and the frame's data context.
 *
 * A frame's component sits inside the host editor's, so without this every action in a frame
 * sees the HOST's file editor and file: Find Usages and the like resolve the frame's caret
 * against the wrong file, or give up. Here the frame answers as an
 * editor of the callee's file. Rules derive the rest (PSI_ELEMENT, LANGUAGE, …) from these.
 *
 * NAVIGATABLE is the callee file at the frame's caret: F4 (Jump to Source) opens the real file
 * right there — frames are read-only, editing happens in the file.
 */
class FramePanel(private val node: ExpansionNode) : JPanel(BorderLayout()), UiDataProvider {

    override fun uiDataSnapshot(sink: DataSink) {
        if (node.disposed) return
        val editor = node.fragmentEditor
        val project = editor.project ?: return
        sink[PlatformCoreDataKeys.FILE_EDITOR] = TextEditorProvider.getInstance().getTextEditor(editor)
        sink.lazy(CommonDataKeys.PSI_FILE) { InlineExpansionService.psiFileFor(editor, project) }
        val file = sourceFile() ?: return
        sink[CommonDataKeys.VIRTUAL_FILE] = file
        sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = arrayOf(file)
        val here = OpenFileDescriptor(project, file, editor.caretModel.offset)
        sink[CommonDataKeys.NAVIGATABLE] = here
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf(here)
    }

    /** The callee's file; for a decompiled mirror, the class file it was decompiled from. */
    private fun sourceFile(): VirtualFile? =
        node.pinnedPsiFile?.let { it.virtualFile ?: it.originalFile.virtualFile }
            ?: FileDocumentManager.getInstance().getFile(node.fragmentEditor.document)
}
