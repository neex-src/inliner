package com.neex.inliner.recorder

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InvalidStackFrameException
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.ObjectCollectedException
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference

/** A location the recorder wants a breakpoint on. */
class ArmPoint(val location: Location, val startsLine: Boolean, val returns: Boolean)

/** How much a recording may cost: each kept hit is a debugger round trip in the user's run. */
class Budget(
    /** Hits kept per armed location; then it is switched off — a hot loop costs this much, once. */
    val perLocation: Int = 200,
    val total: Int = 200_000,
)

/** What the caller does with the breakpoint that was hit. */
enum class Verdict {
    /** Recorded (or not ours to record); leave the breakpoint on. */
    KEEP,

    /** This location used its budget: disable its breakpoint. Never said of a return point, see [Recorder.onHit]. */
    LOCATION_DONE,

    /** The whole budget is used: remove every breakpoint, the recording is over. */
    ALL_DONE,

    /** The frame the recording waited on ([Recorder.endWhenReturns]) returned: remove every breakpoint, it is over. */
    RETURNED,
}

/** The frame a recording that ends with a return waits on: [depth] frames deep in [thread], running [method]. */
private class Until(val thread: Long, val depth: Int, val method: MethodKey)

/**
 * The recorder, in `com.sun.jdi` terms only: it says where to put breakpoints ([arm]), takes the
 * hits ([onHit], with the hitting thread suspended), and hands out what it saw ([recording]).
 * Whoever owns the debugger connection — IntelliJ's debug session, or a plain JDI client in the
 * tests — creates the requests and resumes the thread.
 *
 * Calls come from one debugger thread; [recording] may be called from any.
 */
class Recorder(
    private val scope: RecordScope,
    private val budget: Budget = Budget(),
    private val renderer: ValueRenderer = ValueRenderer(),
    /** A thread running something for the debugger itself (the Variables view's toString()): not the user's run. */
    private val busy: (ThreadReference) -> Boolean = { false },
) {
    private val points = HashMap<Location, ArmPoint>()
    private val sites = HashMap<Location, Site>()
    private val counts = HashMap<Location, Int>()
    private val hits = ArrayList<Hit>()
    private val threads = LinkedHashMap<Long, String>()
    private val exhausted = LinkedHashSet<Site>()
    private var truncated = false
    private var until: Until? = null
    private var anchor: Site? = null
    private val anchorReturns = HashSet<Location>()

    /** The breakpoints [type] needs, empty if it is out of scope. Call once per loaded class. */
    @Synchronized
    fun arm(type: ReferenceType): List<ArmPoint> {
        if (!scope.records(type)) return emptyList()
        val out = type.methods().filter(scope::records).flatMap(::pointsOf)
        out.forEach { points[it.location] = it }
        return out
    }

    /** The first location of every line, and every return. */
    private fun pointsOf(method: Method): List<ArmPoint> {
        val lines = try {
            method.allLineLocations()
        } catch (_: AbsentInformationException) {
            return emptyList()
        }
        val starts = lines.groupBy { it.lineNumber() }.values.map { all -> all.minBy { it.codeIndex() } }
        val returns = returnLocations(method).toSet()
        val byIndex = starts.associateBy { it.codeIndex() }
        val out = starts.map { ArmPoint(it, startsLine = true, returns = it in returns) }
        return out + returns.filter { it.codeIndex() !in byIndex }.map { ArmPoint(it, startsLine = false, returns = true) }
    }

    /** Where [method] returns (its return instructions), empty when it has no bytecode to read. */
    private fun returnLocations(method: Method): List<Location> =
        Bytecode.code(method)?.let(Bytecode::returnOffsets).orEmpty().mapNotNull { method.locationOfCodeIndex(it.toLong()) }

    /**
     * The user is paused in frame [frameIndex] (0: the top) of [thread]: from its line, the
     * recording ends when that frame returns ([Verdict.RETURNED]), and its line is the recording's
     * anchor. Call after [arm]. Returns the extra breakpoints to put up: when the frame's method
     * is not one the scope arms (framework code, an accessor, no `-g`) no recorded hit would
     * ever tell that it returned, so its own return instructions are armed — they record nothing,
     * they only end the recording.
     */
    @Synchronized
    fun endWhenReturns(thread: ThreadReference, frameIndex: Int = 0): List<ArmPoint> {
        val location = thread.frame(frameIndex).location()
        val method = location.method()
        until = Until(thread.uniqueID(), thread.frameCount() - frameIndex, MethodKey(method.declaringType().name(), method.name(), method.signature()))
        anchor = siteOf(location)
        val returns = returnLocations(method)
        if (returns.isEmpty() || returns.any { it in points }) return emptyList()
        anchorReturns += returns
        return returns.map { ArmPoint(it, startsLine = false, returns = true) }
    }

    private fun lineStart(location: Location): Location =
        try {
            location.method().locationsOfLine(location.lineNumber()).minByOrNull { it.codeIndex() } ?: location
        } catch (_: AbsentInformationException) {
            location
        }

    /** A hit of the frame [endWhenReturns] waits on: it is returning, or something above it already runs. */
    private fun returned(hit: Hit): Boolean {
        val until = until ?: return false
        return hit.thread == until.thread &&
            (hit.depth < until.depth || hit.depth == until.depth && hit.returns && hit.site.method == until.method)
    }

    private fun returning(thread: ThreadReference): Boolean {
        val until = until ?: return false
        return try {
            thread.uniqueID() == until.thread && thread.frameCount() == until.depth
        } catch (_: IncompatibleThreadStateException) {
            false
        }
    }

    /**
     * [paused]: the user stopped [thread] here — record it even though it counts as busy;
     * [frameIndex]: in that frame rather than the top one.
     *
     * A return point is never switched off: it is the cheapest hit there is, and the call tree
     * tells calls apart by their returns. Past its budget it keeps being recorded, without values.
     */
    @Synchronized
    fun onHit(thread: ThreadReference, where: Location, paused: Boolean = false, frameIndex: Int = 0): Verdict {
        // a caller frame is somewhere in the middle of its line: the hit it gets is the line's
        val location = if (paused && frameIndex > 0) lineStart(where) else where
        if (location in anchorReturns) return if (returning(thread)) Verdict.RETURNED else Verdict.KEEP
        val point = points[location] ?: return Verdict.KEEP
        if (truncated) return Verdict.ALL_DONE
        if (!paused && busy(thread)) return Verdict.KEEP
        val spent = point.returns && (counts[location] ?: 0) >= budget.perLocation
        val depth: Int
        val values: List<VarValue>
        try {
            if (spent) {
                depth = thread.frameCount() - frameIndex
                values = emptyList()
            } else {
                val frames = thread.frames()
                depth = frames.size - frameIndex
                values = read(frames[frameIndex])
            }
        } catch (_: IncompatibleThreadStateException) {
            return Verdict.KEEP
        } catch (_: InvalidStackFrameException) {
            return Verdict.KEEP
        } catch (_: ObjectCollectedException) {
            return Verdict.KEEP
        }
        val id = thread.uniqueID()
        threads.getOrPut(id) { thread.name() }
        val hit = Hit(hits.size, id, siteOf(location), point.startsLine, point.returns, depth, values)
        hits += hit
        val n = (counts[location] ?: 0) + 1
        counts[location] = n
        return when {
            returned(hit) -> Verdict.RETURNED
            hits.size >= budget.total -> {
                truncated = true
                Verdict.ALL_DONE
            }
            n >= budget.perLocation -> {
                exhausted += siteOf(location)
                if (point.returns) Verdict.KEEP else Verdict.LOCATION_DONE
            }
            else -> Verdict.KEEP
        }
    }

    /** Locals (or, compiled without -g, the arguments by position) and `this`. */
    private fun read(frame: StackFrame): List<VarValue> {
        val out = ArrayList<VarValue>()
        val vars = try {
            frame.visibleVariables()
        } catch (_: AbsentInformationException) {
            null
        }
        if (vars != null) {
            val values = frame.getValues(vars)
            vars.forEach { out += VarValue(it.name(), renderer.render(values[it])) }
        } else {
            frame.argumentValues.forEachIndexed { i, v -> out += VarValue("arg$i", renderer.render(v)) }
        }
        frame.thisObject()?.let { out += VarValue("this", renderer.render(it)) }
        return out
    }


    private fun siteOf(location: Location): Site = sites.getOrPut(location) {
        val method = location.method()
        val path = try {
            location.sourcePath()
        } catch (_: AbsentInformationException) {
            null
        }
        Site(location.declaringType().name(), method.name(), method.signature(), location.lineNumber(), path)
    }

    val hitCount: Int
        @Synchronized get() = hits.size

    @Synchronized
    fun recording(change: Change? = null): Recording =
        Recording(LinkedHashMap(threads), ArrayList(hits), LinkedHashSet(exhausted), truncated, anchor, change)
}
