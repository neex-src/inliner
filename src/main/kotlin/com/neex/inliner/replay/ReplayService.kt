package com.neex.inliner.replay

import com.intellij.codeInsight.hint.HintManager
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.neex.inliner.InlinerStatusWidget
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.PsiTreeUtil
import com.neex.inliner.FrameListener
import com.neex.inliner.InlinerLifetime
import com.neex.inliner.focus.FocusListener
import com.neex.inliner.recorder.ide.RecordingStore
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.ValueRenderer
import java.nio.file.Path

/**
 * The project's open replay: at most one. Each move puts the cursor's line in front — its file
 * opened, the caret on it — and repaints [ReplayView] there.
 */
@Service(Service.Level.PROJECT)
class ReplayService(private val project: Project) : Disposable {

    var replay: Replay? = null
        private set
    private var name = ""

    /** Source file per recorded class, resolved when the replay opens: the index is no EDT business. */
    private var sources: Map<String, VirtualFile> = emptyMap()
    private var view: ReplayView? = null

    /** What is painted now: for tests. */
    internal val currentView: ReplayView? get() = view

    val isOpen: Boolean get() = replay != null

    /** Fold what the cursor's call didn't run (Alt+Shift+F), kept while moving. */
    var foldNotRun = false
        private set

    init {
        // Frames expanded or collapsed under the painted editor show (or drop) their calls;
        // variables tracked or untracked there get (or lose) their history.
        val bus = project.messageBus.connect(this)
        bus.subscribe(FrameListener.TOPIC, FrameListener(::repaint))
        bus.subscribe(FocusListener.TOPIC, FocusListener(::repaint))
    }

    private fun repaint(host: Editor) {
        val replay = replay ?: return
        view?.takeIf { it.editor === host && !host.isDisposed }?.render(replay, foldNotRun, overlay())
    }

    /** A what-if run beside its original: [replay] is one of the two. */
    var comparison: Comparison? = null
        private set

    private fun overlay(): ReplayView.Overlay? {
        val comparison = comparison ?: return null
        val replay = replay ?: return null
        return ReplayView.Overlay(comparison, replay)
    }

    /** Opens a what-if run ([variant]), compared with its [original] when there is one. Any thread. */
    fun openCompared(variant: Recording, original: Recording?, name: String) {
        ReadAction.nonBlocking<Triple<Replay, Replay?, Map<String, VirtualFile>>> {
            val v = Replay(variant)
            val o = original?.let(::Replay)
            Triple(v, o, sourcesOf(project, v) + (o?.let { sourcesOf(project, it) } ?: emptyMap()))
        }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.nonModal()) { (v, o, sources) -> startCompared(v, o, name, sources) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun startCompared(variant: Replay, original: Replay?, name: String, sources: Map<String, VirtualFile>) {
        start(variant, name, sources)
        if (original != null && replay === variant) {
            comparison = Comparison(original, variant)
            show()
        }
    }

    /** Shows the other run of the comparison on the same line if it ran it (the run of it nearest in step), else at the same step. */
    fun toggleCompared() {
        val comparison = comparison ?: return
        val now = replay ?: return
        val other = comparison.other(now)
        val here = now.current.site
        val stops = other.stops()
        val step = stops.indices.filter { stops[it].site == here }.minByOrNull { kotlin.math.abs(it - now.position) }
            ?: now.position.coerceAtMost(stops.size - 1)
        replay = other
        other.moveTo(stops[step])
        show()
    }

    /** To where this run and the other part. */
    fun toDivergence(editor: Editor) {
        val comparison = comparison ?: return
        val replay = replay ?: return
        val hit = comparison.divergence(replay) ?: return hint(editor, "Both runs take the same path")
        if (replay.moveTo(hit)) show()
    }

    fun toggleFold() {
        foldNotRun = !foldNotRun
        view?.let { repaint(it.editor) }
    }

    /** Back to the line that gave [name] its value (the call site when the call never changed it), or on to the next change. */
    fun toChange(name: String, forward: Boolean, editor: Editor) {
        val replay = replay ?: return
        val target = if (forward) replay.nextChange(name) else replay.lastChange(name) ?: replay.callOf(replay.current).callSite
        if (target != null && replay.moveTo(target)) show()
        else hint(editor, if (forward) "$name doesn't change again in this call" else "$name has this value from before the recording")
    }

    fun selectThread(id: Long) {
        val replay = replay ?: return
        replay.selectThread(id)
        if (!replay.isEmpty) show()
    }

    fun moveTo(hit: com.neex.inliner.recorder.Hit) {
        val replay = replay ?: return
        if (replay.moveTo(hit)) show()
    }

    /** No replay keys while a debugger is paused: F8 & co. belong to it then. */
    fun debuggerPaused(): Boolean = DebuggerManagerEx.getInstanceEx(project).context.suspendContext != null

    fun open(file: Path) {
        ReadAction.nonBlocking<Pair<Replay, Map<String, VirtualFile>>> {
            val replay = Replay(RecordingStore.of(project).load(file))
            replay to sourcesOf(project, replay)
        }
            .inSmartMode(project)
            .expireWith(this)
            .finishOnUiThread(ModalityState.nonModal()) { (replay, sources) -> start(replay, file.fileName.toString(), sources) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun start(replay: Replay, name: String, sources: Map<String, VirtualFile>) {
        close()
        if (replay.isEmpty) return
        this.replay = replay
        this.name = name
        this.sources = sources
        show()
    }

    fun close() {
        comparison = null
        foldNotRun = false // the toggle belongs to the replay that was open
        view?.let(Disposer::dispose)
        view = null
        replay = null
        status("")
    }

    fun move(step: Step, editor: Editor?) {
        val replay = replay ?: return
        if (replay.move(step)) show() else hint(editor, if (step == Step.BACK_INTO || step == Step.BACK_OVER) "Start of the recording" else "End of the recording")
    }

    /** To the next / previous run of the caret's line. */
    fun toCaretLine(editor: Editor, forward: Boolean) {
        val replay = replay ?: return
        val className = ReadAction.computeBlocking<String?, Throwable> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            PsiTreeUtil.getParentOfType(file?.findElementAt(editor.caretModel.offset), PsiClass::class.java, false)?.let(ClassUtil::getJVMClassName)
        } ?: return
        val line = editor.caretModel.logicalPosition.line + 1
        if (replay.toLine(className, line, forward)) show() else hint(editor, if (forward) "This line doesn't run again" else "This line didn't run before")
    }

    private fun show() {
        val replay = replay ?: return
        val hit = replay.current
        val file = sources[hit.site.className] ?: return status("Replay · no source for ${hit.site.className}")
        val line = hit.site.line - 1
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, line, 0), true) ?: return
        val text = editor.document.let { if (line in 0 until it.lineCount) it.getText(TextRange(it.getLineStartOffset(line), it.getLineEndOffset(line))) else "" }
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)))
        val view = view?.takeIf { it.editor === editor && !editor.isDisposed } ?: run {
            view?.let(Disposer::dispose)
            ReplayView(editor).also {
                InlinerLifetime.bind(editor, it)
                view = it
            }
        }
        view.render(replay, foldNotRun, overlay())
        val call = replay.callOf(hit)
        val from = call.parent?.let { " ← " + ValueRenderer.simple(it.method.className) + "." + it.method.methodName } ?: ""
        val thread = replay.recording.threads[hit.thread] ?: "?"
        val run = comparison?.variant?.recording?.change?.let { c ->
            if (replay === comparison?.variant) " · what if ${c.name} = ${c.after}" else " · original (${c.name} = ${c.before})"
        } ?: replay.recording.change?.let { " · what if ${it.name} = ${it.after}" } ?: ""
        status("Replay$run · ${ValueRenderer.simple(hit.site.className)}.${hit.site.methodName}$from · ${replay.position + 1}/${replay.stopCount} · $thread · $name")
    }


    private fun hint(editor: Editor?, text: String) {
        if (editor != null && editor.component.isShowing) HintManager.getInstance().showInformationHint(editor, text)
    }

    /** What [InlinerStatusWidget] shows for this service; empty: nothing. */
    var statusText = ""
        private set

    private fun status(text: String) {
        statusText = text
        InlinerStatusWidget.update(project)
    }

    override fun dispose() = Unit

    companion object {
        fun of(project: Project): ReplayService = project.service()

        /** Each recorded class's source file, through the class (nested ones too), else the recorded source path. Read action. */
        fun sourcesOf(project: Project, replay: Replay): Map<String, VirtualFile> {
            val scope = GlobalSearchScope.allScope(project)
            return replay.recording.hits.map { it.site }.distinctBy { it.className }.mapNotNull { site ->
                val facade = JavaPsiFacade.getInstance(project)
                val name = site.className.replace('$', '.')
                // the project's own class first: a library class of the same name must not shadow it
                val file = (facade.findClass(name, GlobalSearchScope.projectScope(project)) ?: facade.findClass(name, scope))
                    ?.navigationElement?.containingFile?.virtualFile
                    ?: site.sourcePath?.let { path ->
                        FilenameIndex.getVirtualFilesByName(path.substringAfterLast('/'), scope).firstOrNull { it.path.endsWith(path) }
                    }
                file?.let { site.className to it }
            }.toMap()
        }
    }
}
