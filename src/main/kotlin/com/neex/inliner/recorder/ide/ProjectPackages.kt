package com.neex.inliner.recorder.ide

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/** What the project itself is made of: the packages of its Java sources. */
object ProjectPackages {

    /**
     * Needs a read action. Walks the source roots' directories — one listing each — and takes a
     * directory's package from the directory index when it holds a `.java` file: no file is opened,
     * parsed or turned into PSI, so a project of ten thousand files costs as many directories as it has.
     * [includeTests]: the test source roots too, for a debuggee that is a test run.
     */
    fun of(project: Project, includeTests: Boolean = false): List<String> {
        val index = ProjectFileIndex.getInstance(project)
        val roots = ModuleManager.getInstance(project).modules
            .flatMap { ModuleRootManager.getInstance(it).getSourceRoots(includeTests).asList() }
            .distinct()
        val packages = HashSet<String>()
        for (root in roots) {
            com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(
                root,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!file.isDirectory) return true
                        if (index.isExcluded(file) || (!includeTests && index.isInTestSourceContent(file))) return false
                        if (file.children.any { !it.isDirectory && it.extension == "java" }) {
                            PackageIndex.getInstance(project).getPackageNameByDirectory(file)?.let(packages::add)
                        }
                        return true
                    }
                },
            )
        }
        return fold(packages)
    }

    /** Shortest covering prefixes: `a.b` swallows `a.b.c`; the default package can't be named, it is left out. */
    fun fold(packages: Collection<String>): List<String> {
        val sorted = packages.filter { it.isNotEmpty() }.toSortedSet()
        val out = ArrayList<String>()
        for (p in sorted) if (out.none { p == it || p.startsWith("$it.") }) out += p
        return out
    }
}
