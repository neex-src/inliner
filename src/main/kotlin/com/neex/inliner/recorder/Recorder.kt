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

    /** This location used its budget: disable its breakpoint. */
    LOCATION_DONE,

    /** The whole budget is used: remove every breakpoint, the recording is over. */
    ALL_DONE,
}

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
        val returns = Bytecode.code(method)?.let(Bytecode::returnOffsets).orEmpty()
            .mapNotNull { method.locationOfCodeIndex(it.toLong()) }.toSet()
        val byIndex = starts.associateBy { it.codeIndex() }
        val out = starts.map { ArmPoint(it, startsLine = true, returns = it in returns) }
        return out + returns.filter { it.codeIndex() !in byIndex }.map { ArmPoint(it, startsLine = false, returns = true) }
    }

    @Synchronized
    fun onHit(thread: ThreadReference, location: Location): Verdict {
        val point = points[location] ?: return Verdict.KEEP
        if (truncated) return Verdict.ALL_DONE
        if (busy(thread)) return Verdict.KEEP
        val frames = try {
            thread.frames()
        } catch (_: IncompatibleThreadStateException) {
            return Verdict.KEEP
        }
        val values = try {
            read(frames[0])
        } catch (_: InvalidStackFrameException) {
            return Verdict.KEEP
        } catch (_: ObjectCollectedException) {
            return Verdict.KEEP
        }
        val id = thread.uniqueID()
        threads.getOrPut(id) { thread.name() }
        hits += Hit(hits.size, id, siteOf(location), point.startsLine, point.returns, frames.size, values)
        val n = (counts[location] ?: 0) + 1
        counts[location] = n
        return when {
            hits.size >= budget.total -> {
                truncated = true
                Verdict.ALL_DONE
            }
            n >= budget.perLocation -> {
                exhausted += siteOf(location)
                Verdict.LOCATION_DONE
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

    @Synchronized
    fun recording(): Recording = Recording(LinkedHashMap(threads), ArrayList(hits), LinkedHashSet(exhausted), truncated)
}
