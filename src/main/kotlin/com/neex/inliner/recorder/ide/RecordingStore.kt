package com.neex.inliner.recorder.ide

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.RecordingHeader
import com.neex.inliner.recorder.RecordingFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

/** Recordings on disk, per project, under the IDE's system directory: they outlive the debug session. */
class RecordingStore(val dir: Path, keep: Int = DEFAULT_KEEP) {

    /** How many recordings stay: the newest ones; at least the one just saved. */
    private val keep = keep.coerceAtLeast(1)

    fun save(recording: Recording, at: LocalDateTime = LocalDateTime.now()): Path {
        Files.createDirectories(dir)
        var file = dir.resolve(at.format(NAME) + ".$EXT")
        var n = 2
        while (Files.exists(file)) file = dir.resolve(at.format(NAME) + "-${n++}.$EXT")
        file.outputStream().use { RecordingFile.write(recording, it) }
        prune()
        return file
    }

    /** Newest first (names are timestamps). */
    fun list(): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else dir.listDirectoryEntries().filter { it.isRegularFile() && it.extension == EXT }.sortedByDescending { it.fileName.toString() }

    /** Removes all but the newest [keep]: a recording is up to hundreds of thousands of hits, they would pile up for ever. */
    private fun prune() {
        list().drop(keep).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    fun delete(file: Path): Boolean = runCatching { Files.deleteIfExists(file) }.getOrDefault(false)

    fun load(file: Path): Recording = counted(file).use(RecordingFile::read)

    /**
     * What [file] is, without its hits: the header of a version 3 file, a few hundred bytes of it; an older
     * file has none and is read whole. Null when it can't be read.
     */
    fun header(file: Path): RecordingHeader? = runCatching {
        java.io.BufferedInputStream(counted(file), HEADER_BUFFER).use { RecordingFile.readHeader(it) }
            ?: RecordingFile.headerOf(load(file))
    }.getOrNull()

    private fun counted(file: Path): java.io.InputStream = object : java.io.FilterInputStream(file.inputStream()) {
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
    }

    companion object {
        const val EXT = "inlrec"
        const val DEFAULT_KEEP = 50

        /** A header is read through a buffer this big: nearly all of what listing a recording costs. */
        private const val HEADER_BUFFER = 1024

        /** Bytes read from recording files by any store since the IDE started: what listing them costs, for tests and curiosity. */
        val bytesRead = java.util.concurrent.atomic.AtomicLong()
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        fun of(project: Project) =
            RecordingStore(
                PathManager.getSystemDir().resolve("inliner").resolve("recordings").resolve(project.getProjectCacheFileName()),
                RecorderSettings.getInstance().state.keep,
            )
    }
}
