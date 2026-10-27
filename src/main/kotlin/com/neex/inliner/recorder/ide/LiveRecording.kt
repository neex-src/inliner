package com.neex.inliner.recorder.ide

import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.DebugProcessListener
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.neex.inliner.recorder.Change
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.MethodKey
import com.neex.inliner.recorder.Recorder
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
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
    private var until: Until? = null
    private var anchor: Site? = null
    private var change: Change? = null

    @Volatile
    var ended = false
        private set

    val hits: Int get() = recorder.hitCount

    /** Ends when [method], paused at [depth] in [thread], returns — or anything shallower runs there. */
    private class Until(val thread: Long, val depth: Int, val method: MethodKey?)

    private val prepared = ClassPrepareRequestor { _, type -> arm(type) }

    private val handler = Consumer<Event> { event ->
        if (ended || event !is BreakpointEvent) return@Consumer
        val before = recorder.hitCount
        when (recorder.onHit(event.thread(), event.location())) {
            Verdict.KEEP -> Unit
            Verdict.LOCATION_DONE -> event.request().disable()
            Verdict.ALL_DONE -> return@Consumer end(EndReason.BUDGET)
        }
        if (recorder.hitCount > before && until?.let { returned(it, recorder.lastHit!!) } == true) end(EndReason.RETURNED)
    }

    private fun returned(until: Until, hit: Hit): Boolean =
        hit.thread == until.thread &&
            (hit.depth < until.depth || hit.depth == until.depth && hit.returns && hit.site.method == until.method)

    /** A value to set in the paused frame before recording on: a what-if. */
    class WhatIfRequest(val name: String, val text: String)

    /**
     * Arms what is loaded now and what loads later, then calls [armed] (on the manager thread).
     * [untilReturn]: the user is paused; end when that frame returns, the paused line being the
     * recording's anchor. [whatIf]: first set that value in the paused frame; if it can't be
     * done, nothing is recorded and [refused] says why.
     */
    fun start(untilReturn: Boolean, whatIf: WhatIfRequest? = null, refused: (String) -> Unit = {}, armed: () -> Unit) = onManagerThread {
        if (whatIf != null) {
            val frame = process.suspendManager.pausedContext?.frameProxy
            try {
                if (frame == null) throw WhatIfException("Not paused in a frame")
                change = WhatIf.apply(frame.stackFrame, whatIf.name, whatIf.text)
            } catch (e: WhatIfException) {
                ended = true
                refused(e.message ?: "Can't set ${whatIf.name}")
                Disposer.dispose(this)
                return@onManagerThread
            }
        }
        if (untilReturn) {
            until = pausedFrame()
            anchor = process.suspendManager.pausedContext?.frameProxy?.location()?.let(recorder::siteAt)
        }
        process.addDebugProcessListener(object : DebugProcessListener {
            override fun processDetached(process: DebugProcess, closedByUser: Boolean) = end(EndReason.DETACHED)
        }, this)
        process.virtualMachineProxy.allClasses().forEach(::arm)
        packages.forEach { process.requestsManager.callbackOnPrepareClasses(prepared, "$it.*") }
        // The line the user is paused on ran its event already: it is the recording's first hit.
        process.suspendManager.pausedContext?.let { context ->
            val thread = context.thread?.threadReference ?: return@let
            context.frameProxy?.location()?.let { recorder.onHit(thread, it, paused = true) }
        }
        armed()
    }

    fun stop() = onManagerThread { end(EndReason.STOPPED) }

    private fun arm(type: ReferenceType) {
        if (ended || !type.isPrepared || !armed.add(type)) return
        val vm = process.virtualMachineProxy.eventRequestManager()
        for (point in recorder.arm(type)) {
            val request = vm.createBreakpointRequest(point.location)
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            DebugProcessEvents.enableRequestWithHandler(request, handler)
            requests += request
        }
    }

    private fun pausedFrame(): Until? {
        val context = process.suspendManager.pausedContext ?: return null
        val thread = context.thread ?: return null
        val frame = context.frameProxy ?: return null
        val method = frame.location().method()
        val key = MethodKey(method.declaringType().name(), method.name(), method.signature())
        return Until(thread.threadReference.uniqueID(), thread.frameCount(), key)
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
        onEnd(recorder.recording(anchor, change), reason)
        Disposer.dispose(this)
        // The manager thread holds on to the last command it ran until it runs another: if that is
        // ours, it keeps this plugin's classes alive after an unload. Leave it a command of its own.
        process.managerThread.schedule(PrioritizedTask.Priority.LOW, EmptyRunnable.getInstance())
    }

    private fun onManagerThread(work: () -> Unit) {
        process.managerThread.schedule(PrioritizedTask.Priority.NORMAL) {
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
    }
}
