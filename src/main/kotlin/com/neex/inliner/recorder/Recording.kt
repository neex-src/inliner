package com.neex.inliner.recorder

/** A place in the code a hit happened at: a line of a method. Interned per recording. */
data class Site(
    val className: String,
    val methodName: String,
    /** JVM signature: tells overloads apart. */
    val signature: String,
    val line: Int,
    /** The source file's path relative to its root (`com/example/Cart.java`), when known. */
    val sourcePath: String?,
) {
    val method: MethodKey get() = MethodKey(className, methodName, signature)
}

data class MethodKey(val className: String, val methodName: String, val signature: String)

/** One variable as it was at a hit, already text: the debuggee is gone when anyone reads it. */
data class VarValue(val name: String, val text: String)

/**
 * One armed location reached by one thread. Values are read before the location runs: a line's
 * effect shows at the next hit of the same call. An [returns] hit is the method about to return
 * (its locals as final as they get; the returned value itself shows up in the caller).
 */
class Hit(
    val seq: Int,
    val thread: Long,
    val site: Site,
    /** The first instruction of [Site.line]. */
    val startsLine: Boolean,
    /** A return instruction. */
    val returns: Boolean,
    /** Frames on the thread's stack, framework frames included: tells calls apart. */
    val depth: Int,
    val values: List<VarValue>,
)

/** What a recorder saw, in order. */
class Recording(
    val threads: Map<Long, String>,
    val hits: List<Hit>,
    /** Sites that used up their budget: armed no more, their later runs are not in [hits]. */
    val exhausted: Set<Site>,
    /** The total budget ran out: the recording ends early. */
    val truncated: Boolean,
)
