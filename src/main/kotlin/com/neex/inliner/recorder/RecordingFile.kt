package com.neex.inliner.recorder

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A recording on disk: it has to outlive the debug session it came from. Binary, with every
 * string (class names, variable names, the same rendered value over and over) stored once.
 */
object RecordingFile {

    private const val MAGIC = 0x494e4c52 // "INLR"
    private const val VERSION = 1

    fun write(recording: Recording, out: OutputStream) {
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
        sites.keys.forEach { listOfNotNull(it.className, it.methodName, it.signature, it.sourcePath).forEach(::id) }

        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
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
        d.flush()
    }

    fun read(input: InputStream): Recording {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC) throw IOException("not a recording")
        val version = d.readInt()
        if (version != VERSION) throw IOException("recording format $version, this plugin reads $VERSION")
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
        return Recording(threads, hits, exhausted, d.readBoolean())
    }
}
