package com.neex.inliner.recorder.ide

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.neex.inliner.recorder.ArmPoint
import com.neex.inliner.recorder.Change
import com.neex.inliner.recorder.Recorder
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Verdict
import com.neex.inliner.recorder.WhatIf
import com.neex.inliner.recorder.WhatIfException
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.Event
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import java.util.function.Consumer

/** Why a recording ended. */
enum class EndReason { STOPPED, RETURNED, BUDGET, DETACHED }

/**
 * A [Recorder] attached to one debug process: our own breakpoints on every recorded line, put
 * there and served on the debugger's manager thread. The requests are raw JDI ones tagged with a
 * handler (`DebugProcessEvents.enableRequestWithHandler`): the debugger hands the event to us and
 * resumes the thread itself, without the suspend-context machinery a breakpoint goes through —
 * M0 measured that path at about a third of the cost. The user never sees these stops.
 *
 * Classes loaded later are armed as they load (`callbackOnPrepareClasses`, before their code runs).
 * Hits in a thread the user is paused in are not the run: that is the debugger evaluating
 * (the Variables view calling toString()).
 */
class LiveRecording(
    private val process: DebugProcessImpl,
    private val packages: List<String>,
    newRecorder: (busy: (ThreadReference) -> Boolean) -> Recorder,
    private val onEnd: (Recording, EndReason) -> Unit,
) : Disposable {

    private val recorder = newRecorder(::pausedByUser)
    private val requests = ArrayList<BreakpointRequest>()
    private val armed = HashSet<ReferenceType>()
    private var change: Change? = null

    @Volatile
    var ended = false
        private set

    val hits: Int get() = recorder.hitCount

    private val prepared = ClassPrepareRequestor { _, type -> arm(type) }

    private val handler = Consumer<Event> { event ->
        if (ended || event !is BreakpointEvent) return@Consumer
        when (recorder.onHit(event.thread(), event.location())) {
            Verdict.KEEP -> Unit
            Verdict.LOCATION_DONE -> event.request().disable()
            Verdict.ALL_DONE -> end(EndReason.BUDGET)
            Verdict.RETURNED -> end(EndReason.RETURNED)
        }
    }

    /** A value to set in the paused frame before recording on: a what-if. */
    class WhatIfRequest(val name: String, val text: String)

    /**
     * Arms what is loaded now and what loads later, then calls [armed] (on the manager thread).
     * [selected]: the frame the user has selected in the debugger's Frames view (not always the
     * top one). [untilReturn]: the user is paused; end when *that* frame returns, its line being
     * the recording's anchor. [whatIf]: first set that value in that frame; if it can't be done,
     * nothing is recorded and [refused] says why.
     */
    fun start(
        selected: StackFrameProxyImpl?,
        untilReturn: Boolean,
        whatIf: WhatIfRequest? = null,
        refused: (String) -> Unit = {},
        armed: () -> Unit,
    ) = onManagerThread {
        if (whatIf != null) {
            try {
                if (selected == null) throw WhatIfException("Not paused in a frame")
                change = WhatIf.apply(selected.stackFrame, whatIf.name, whatIf.text)
            } catch (e: WhatIfException) {
                ended = true
                refused(e.message ?: "Can't set ${whatIf.name}")
                Disposer.dispose(this)
                return@onManagerThread
            }
        }
        process.addDebugProcessListener(object : DebugProcessListener {
            override fun processDetached(process: DebugProcess, closedByUser: Boolean) = end(EndReason.DETACHED)
        }, this)
        // Classes loaded from here on are armed as they load; the ones loaded already, in chunks below.
        packages.forEach { process.requestsManager.callbackOnPrepareClasses(prepared, "$it.*") }
        // The line the user is paused on ran its event already: it is the recording's first hit —
        // in the selected frame when the recording ends with that frame's return, else the top one.
        // Its class is armed first: the end of the recording is decided from what is armed.
        val frame = if (untilReturn) selected else process.suspendManager.pausedContext?.frameProxy
        var seed = Verdict.KEEP
        if (frame != null) {
            val thread = frame.threadProxy().threadReference
            arm(frame.location().declaringType())
            if (untilReturn) recorder.endWhenReturns(thread, frame.frameIndex).forEach(::request)
            seed = recorder.onHit(thread, frame.location(), paused = true, frameIndex = frame.frameIndex)
        }
        todo.addAll(process.virtualMachineProxy.allClasses().filter { it.isPrepared && recorder.wants(it) && it !in this.armed })
        armTotal = todo.size
        armingNow = true
        armChunk {
            armed()
            if (seed == Verdict.RETURNED) end(EndReason.RETURNED)
        }
    }

    fun stop() = onManagerThread { end(EndReason.STOPPED) }

    /**
     * Arms classes for about [CHUNK_NANOS], then leaves the manager thread to whatever else is waiting
     * for it — a resume, a step, an evaluation — and goes on in a low-priority command. Every
     * breakpoint is a round trip the debuggee answers with a pause of its own: arming a hundred classes
     * takes seconds, and the debugger must not stand still for them. [done] runs after the last chunk.
     */
    private fun armChunk(done: () -> Unit) {
        if (ended) return
        val from = System.nanoTime()
        var n = 0
        while (todo.isNotEmpty() && (n == 0 || System.nanoTime() - from < CHUNK_NANOS)) {
            arm(todo.removeFirst())
            armDone++
            n++
        }
        if (todo.isEmpty()) {
            armingNow = false
            done()
        } else {
            onManagerThread(PrioritizedTask.Priority.LOW) { armChunk(done) }
        }
    }

    private val todo = ArrayDeque<ReferenceType>()

    @Volatile
    private var armTotal = 0

    @Volatile
    private var armDone = 0

    @Volatile
    private var armingNow = false

    /** Classes armed so far and in all, while the first arming is going on; null before and after it. */
    val arming: Pair<Int, Int>? get() = if (armingNow) armDone to armTotal else null

    private fun arm(type: ReferenceType) {
        if (ended || !type.isPrepared || !armed.add(type)) return
        recorder.arm(type).forEach(::request)
    }

    private fun request(point: ArmPoint) {
        val request = process.virtualMachineProxy.eventRequestManager().createBreakpointRequest(point.location)
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        DebugProcessEvents.enableRequestWithHandler(request, handler)
        requests += request
    }

    private fun pausedByUser(thread: ThreadReference): Boolean =
        process.suspendManager.pausedContexts.any { it.thread?.threadReference == thread }

    private fun end(reason: EndReason) {
        if (ended) return
        ended = true
        try {
            process.virtualMachineProxy.eventRequestManager().deleteEventRequests(requests)
            process.requestsManager.deleteRequest(prepared)
        } catch (_: VMDisconnectedException) {
            // the debuggee is gone, and its requests with it
        }
        requests.clear()
        onEnd(recorder.recording(change), reason)
        Disposer.dispose(this)
        // The manager thread holds on to the last command it ran until it runs another: if that is
        // ours, it keeps this plugin's classes alive after an unload. Leave it a command of its own.
        process.managerThread.schedule(PrioritizedTask.Priority.LOW, EmptyRunnable.getInstance())
    }

    private fun onManagerThread(priority: PrioritizedTask.Priority = PrioritizedTask.Priority.NORMAL, work: () -> Unit) {
        process.managerThread.schedule(priority) {
            try {
                work()
            } catch (e: VMDisconnectedException) {
                end(EndReason.DETACHED)
            } catch (e: Exception) {
                LOG.warn("recorder", e)
                end(EndReason.STOPPED)
            }
        }
    }

    /** Disposed from outside (project closing, plugin unloading) while running: take the breakpoints out. */
    override fun dispose() {
        if (!ended) onManagerThread { end(EndReason.STOPPED) }
    }

    private companion object {
        val LOG = logger<LiveRecording>()

        /** How long a chunk of arming holds the manager thread. */
        const val CHUNK_NANOS = 30_000_000L
    }
}
