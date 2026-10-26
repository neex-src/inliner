package com.neex.inliner.recorder.ide

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopesCore

/** What the project itself is made of: the packages of its production Java sources. */
object ProjectPackages {

    /** Needs a read action and smart mode. */
    fun of(project: Project): List<String> {
        val psi = PsiManager.getInstance(project)
        val packages = FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScopesCore.projectProductionScope(project))
            .mapNotNull { (psi.findFile(it) as? PsiJavaFile)?.packageName }
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
