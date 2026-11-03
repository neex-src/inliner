package com.neex.inliner.recorder

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a recording is, in one line's worth: enough for a list and for a what-if's search, without its hits. */
data class RecordingHeader(
    val hits: Int,
    val calls: Int,
    val truncated: Boolean,
    /** The first hit's class and method: where the recording starts. */
    val entry: Pair<String, String>?,
    val anchor: Site?,
    val change: Change?,
    val threads: List<String>,
)

/**
 * A recording on disk: it has to outlive the debug session it came from. Binary, with every
 * string (class names, variable names, the same rendered value over and over) stored once.
 *
 * Version 3 starts with a [RecordingHeader] right after the version, with its own length: a list of
 * recordings reads those few hundred bytes of each file with [readHeader] and stops, where the hits
 * can be tens of megabytes. Versions 1 and 2 have no header ([readHeader] says so with null) and are
 * read whole as before.
 */
object RecordingFile {

    private const val MAGIC = 0x494e4c52 // "INLR"
    private const val VERSION = 3

    /** The header [recording] would get: what [readHeader] returns for its file, and what an old file's is derived as. */
    fun headerOf(recording: Recording): RecordingHeader = RecordingHeader(
        hits = recording.hits.size,
        calls = CallTree.build(recording).values.sumOf { roots -> roots.sumOf(::count) },
        truncated = recording.truncated,
        entry = recording.hits.firstOrNull()?.site?.let { it.className to it.methodName },
        anchor = recording.anchor,
        change = recording.change,
        threads = recording.threads.values.toList(),
    )

    private fun count(call: Invocation): Int = 1 + call.children.sumOf(::count)

    fun write(recording: Recording, out: OutputStream) = write(recording, out, VERSION)

    /** [version] 2: without the header, as older plugins wrote it (the compatibility test needs one). */
    internal fun write(recording: Recording, out: OutputStream, version: Int) {
        val strings = LinkedHashMap<String, Int>()
        fun id(s: String) = strings.getOrPut(s) { strings.size }
        val sites = LinkedHashMap<Site, Int>()
        fun site(s: Site) = sites.getOrPut(s) { sites.size }

        // Interned up front, so the tables come before what refers to them.
        recording.threads.values.forEach(::id)
        recording.hits.forEach { hit ->
            site(hit.site)
            hit.values.forEach { id(it.name); id(it.text) }
        }
        recording.exhausted.forEach(::site)
        recording.anchor?.let(::site)
        recording.change?.let { id(it.name); id(it.before); id(it.after) }
        sites.keys.forEach { listOfNotNull(it.className, it.methodName, it.signature, it.sourcePath).forEach(::id) }

        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(version)
        if (version >= 3) writeHeader(headerOf(recording), d)
        d.writeInt(strings.size)
        strings.keys.forEach(d::writeUTF)
        d.writeInt(sites.size)
        sites.keys.forEach {
            d.writeInt(id(it.className)); d.writeInt(id(it.methodName)); d.writeInt(id(it.signature))
            d.writeInt(it.line); d.writeInt(it.sourcePath?.let(::id) ?: -1)
        }
        d.writeInt(recording.threads.size)
        recording.threads.forEach { (tid, name) -> d.writeLong(tid); d.writeInt(id(name)) }
        d.writeInt(recording.hits.size)
        recording.hits.forEach { hit ->
            d.writeLong(hit.thread)
            d.writeInt(site(hit.site))
            d.writeByte((if (hit.startsLine) 1 else 0) or (if (hit.returns) 2 else 0))
            d.writeInt(hit.depth)
            d.writeInt(hit.values.size)
            hit.values.forEach { d.writeInt(id(it.name)); d.writeInt(id(it.text)) }
        }
        d.writeInt(recording.exhausted.size)
        recording.exhausted.forEach { d.writeInt(site(it)) }
        d.writeBoolean(recording.truncated)
        d.writeInt(recording.anchor?.let(::site) ?: -1)
        d.writeBoolean(recording.change != null)
        recording.change?.let { d.writeInt(id(it.name)); d.writeInt(id(it.before)); d.writeInt(id(it.after)) }
        d.flush()
    }

    private fun writeHeader(h: RecordingHeader, out: DataOutputStream) {
        val bytes = ByteArrayOutputStream()
        val d = DataOutputStream(bytes)
        d.writeInt(h.hits)
        d.writeInt(h.calls)
        d.writeBoolean(h.truncated)
        d.writeBoolean(h.entry != null)
        h.entry?.let { d.writeUTF(it.first); d.writeUTF(it.second) }
        d.writeBoolean(h.anchor != null)
        h.anchor?.let {
            d.writeUTF(it.className); d.writeUTF(it.methodName); d.writeUTF(it.signature); d.writeInt(it.line)
            d.writeBoolean(it.sourcePath != null)
            it.sourcePath?.let(d::writeUTF)
        }
        d.writeBoolean(h.change != null)
        h.change?.let { d.writeUTF(it.name); d.writeUTF(it.before); d.writeUTF(it.after) }
        d.writeInt(h.threads.size)
        h.threads.forEach(d::writeUTF)
        d.flush()
        out.writeInt(bytes.size())
        bytes.writeTo(out)
    }

    /**
     * The header of a version 3 file, reading no further than it; null for an older file, which has
     * none (its header is [headerOf] its recording).
     */
    fun readHeader(input: InputStream): RecordingHeader? {
        val d = DataInputStream(input)
        if (d.readInt() != MAGIC) throw IOException("not a recording")
        val version = d.readInt()
        if (version !in 1..VERSION) throw IOException("recording format $version, this plugin reads 1 to $VERSION")
        if (version < 3) return null
        d.readInt() // the header's length: only readers that skip it need it
        val hits = d.readInt()
        val calls = d.readInt()
        val truncated = d.readBoolean()
        val entry = if (d.readBoolean()) d.readUTF() to d.readUTF() else null
        val anchor = if (d.readBoolean()) Site(d.readUTF(), d.readUTF(), d.readUTF(), d.readInt(), if (d.readBoolean()) d.readUTF() else null) else null
        val change = if (d.readBoolean()) Change(d.readUTF(), d.readUTF(), d.readUTF()) else null
        val threads = List(d.readInt()) { d.readUTF() }
        return RecordingHeader(hits, calls, truncated, entry, anchor, change, threads)
    }

    fun read(input: InputStream): Recording {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC) throw IOException("not a recording")
        val version = d.readInt()
        if (version !in 1..VERSION) throw IOException("recording format $version, this plugin reads 1 to $VERSION")
        if (version >= 3) d.skipNBytes(d.readInt().toLong())
        val strings = List(d.readInt()) { d.readUTF() }
        val sites = List(d.readInt()) {
            val c = strings[d.readInt()]
            val m = strings[d.readInt()]
            val sig = strings[d.readInt()]
            val line = d.readInt()
            val path = d.readInt().let { if (it < 0) null else strings[it] }
            Site(c, m, sig, line, path)
        }
        val threads = LinkedHashMap<Long, String>()
        repeat(d.readInt()) { threads[d.readLong()] = strings[d.readInt()] }
        val hits = List(d.readInt()) { seq ->
            val thread = d.readLong()
            val site = sites[d.readInt()]
            val flags = d.readByte().toInt()
            val depth = d.readInt()
            val values = List(d.readInt()) { VarValue(strings[d.readInt()], strings[d.readInt()]) }
            Hit(seq, thread, site, flags and 1 != 0, flags and 2 != 0, depth, values)
        }
        val exhausted = LinkedHashSet<Site>()
        repeat(d.readInt()) { exhausted += sites[d.readInt()] }
        val truncated = d.readBoolean()
        if (version < 2) return Recording(threads, hits, exhausted, truncated)
        val anchor = d.readInt().let { if (it < 0) null else sites[it] }
        val change = if (d.readBoolean()) Change(strings[d.readInt()], strings[d.readInt()], strings[d.readInt()]) else null
        return Recording(threads, hits, exhausted, truncated, anchor, change)
    }
}
