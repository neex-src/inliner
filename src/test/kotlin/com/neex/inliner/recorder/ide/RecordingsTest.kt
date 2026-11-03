package com.neex.inliner.recorder.ide

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.recorder.Change
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.RecordingFile
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue
import com.neex.inliner.replay.ReplayPickAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDateTime

/** What a list of recordings costs, how many are kept, how they are deleted, and that old files still open. */
class RecordingsTest : LightJavaCodeInsightFixtureTestCase() {

    private val site = Site("com.shop.Cart", "total", "()J", 12, "com/shop/Cart.java")
    private val deeper = Site("com.shop.Line", "amount", "()J", 7, "com/shop/Line.java")

    /** [n] hits in two calls of different methods, each with its own values: a file that is not small. */
    private fun recording(n: Int, anchor: Site? = null, change: Change? = null) = Recording(
        mapOf(1L to "main", 2L to "worker-1"),
        List(n) { i ->
            Hit(i, 1, if (i % 3 == 0) deeper else site, true, i % 50 == 49, if (i % 3 == 0) 4 else 3,
                listOf(VarValue("sum", "${i * 7}"), VarValue("label", "\"line number $i of the order\""), VarValue("this", "Cart#${i % 9}")))
        },
        emptySet(), false, anchor, change,
    )

    private fun store() = RecordingStore(FileUtil.createTempDirectory("recordings", null).toPath())

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var read = 0L
        override fun read(): Int = super.read().also { if (it >= 0) read++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
    }

    fun testTheHeaderIsAtTheFrontAndReadingItStopsThere() {
        val rec = recording(3_000, anchor = site, change = Change("bulk", "true", "false"))
        val bytes = ByteArrayOutputStream().also { RecordingFile.write(rec, it) }.toByteArray()
        val counting = Counting(java.io.BufferedInputStream(ByteArrayInputStream(bytes), 64))
        val header = RecordingFile.readHeader(counting)!!
        assertEquals(RecordingFile.headerOf(rec), header)
        assertEquals(3_000, header.hits)
        assertEquals("com.shop.Line" to "amount", header.entry)
        assertEquals(site, header.anchor)
        assertEquals(Change("bulk", "true", "false"), header.change)
        assertEquals(listOf("main", "worker-1"), header.threads)
        assertTrue("read ${counting.read} of ${bytes.size} bytes", counting.read < 300 && counting.read < bytes.size / 100)
        assertEquals("and the whole file still reads as before", 3_000, RecordingFile.read(ByteArrayInputStream(bytes)).hits.size)
    }

    fun testAFileWithoutTheHeaderStillOpens() {
        val rec = recording(40, anchor = site)
        val old = ByteArrayOutputStream().also { RecordingFile.write(rec, it, 2) }.toByteArray()
        assertNull("version 2 has no header", RecordingFile.readHeader(ByteArrayInputStream(old)))
        val back = RecordingFile.read(ByteArrayInputStream(old))
        assertEquals(40, back.hits.size)
        assertEquals(site, back.anchor)
        // the store derives its header by reading it whole
        val store = store()
        val file = store.dir.resolve("20260101-000000-000.${RecordingStore.EXT}").also { Files.write(it, old) }
        assertEquals(RecordingFile.headerOf(rec), store.header(file))
    }

    fun testListingThirtyRecordingsReadsOnlyTheirHeaders() {
        val store = store()
        val at = LocalDateTime.of(2026, 11, 1, 9, 0)
        repeat(30) { store.save(recording(4_000 + it), at.plusMinutes(it.toLong())) }
        val onDisk = store.list().sumOf { Files.size(it) }
        val before = RecordingStore.bytesRead.get()
        val choices = ReplayPickAction().choices(project, store)
        val read = RecordingStore.bytesRead.get() - before
        assertEquals(30, choices.size)
        assertTrue(choices.first().label, choices.first().label.contains("Line.amount") && choices.first().label.contains("lines"))
        println("listing 30 recordings: read $read bytes of $onDisk on disk (before: all of it, ${onDisk}), ${read * 100.0 / onDisk}%")
        assertTrue("read $read of $onDisk", read <= 30 * 1024 && read * 100 < onDisk)
    }
}
