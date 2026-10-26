package com.neex.inliner.recorder.ide

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import com.neex.inliner.recorder.Recording
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
class RecordingStore(private val dir: Path) {

    fun save(recording: Recording, at: LocalDateTime = LocalDateTime.now()): Path {
        Files.createDirectories(dir)
        var file = dir.resolve(at.format(NAME) + ".$EXT")
        var n = 2
        while (Files.exists(file)) file = dir.resolve(at.format(NAME) + "-${n++}.$EXT")
        file.outputStream().use { RecordingFile.write(recording, it) }
        return file
    }

    /** Newest first (names are timestamps). */
    fun list(): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else dir.listDirectoryEntries().filter { it.isRegularFile() && it.extension == EXT }.sortedByDescending { it.fileName.toString() }

    fun load(file: Path): Recording = file.inputStream().use(RecordingFile::read)

    companion object {
        const val EXT = "inlrec"
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        fun of(project: Project) =
            RecordingStore(PathManager.getSystemDir().resolve("inliner").resolve("recordings").resolve(project.getProjectCacheFileName()))
    }
}
