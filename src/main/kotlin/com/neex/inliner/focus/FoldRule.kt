package com.neex.inliner.focus

/**
 * Which lines fold-to-focus keeps visible: a predicate over the [Touch]es on one line. What
 * else stays — the signature, the closing brace, control-flow headers around kept lines, frame
 * anchors — is structure, the same for every rule ([FocusFolder]).
 */
enum class FoldRule(val title: String) {
    TOUCHES("Reads and writes") {
        override fun keeps(touches: List<Touch>) = touches.isNotEmpty()
    },
    WRITES("Writes only") {
        override fun keeps(touches: List<Touch>) = touches.any { it.access.writes }
    };

    abstract fun keeps(touches: List<Touch>): Boolean

    fun next(): FoldRule = entries[(ordinal + 1) % entries.size]
}
