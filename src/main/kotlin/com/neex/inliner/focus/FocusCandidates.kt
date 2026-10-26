package com.neex.inliner.focus

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.util.PsiTreeUtil

data class FocusCandidate(
    val variable: PsiVariable,
    val kind: FocusKind,
    val name: String,
    /** Type for params/fields; the (cropped) declaration line for locals. */
    val detail: String,
)

object FocusCandidates {

    /** Everything trackable from the method around [offset]: its params, its locals, the class fields. */
    fun collect(psiFile: PsiFile, offset: Int): Pair<PsiMethod, List<FocusCandidate>>? {
        val leaf = psiFile.findElementAt(offset)
            ?: (if (offset > 0) psiFile.findElementAt(offset - 1) else null)
            ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java) ?: return null

        val out = mutableListOf<FocusCandidate>()
        for (p in method.parameterList.parameters) {
            out += FocusCandidate(p, FocusKind.PARAMETER, p.name, p.type.presentableText)
        }
        method.body?.let { body ->
            for (v in PsiTreeUtil.findChildrenOfType(body, PsiLocalVariable::class.java)) {
                out += FocusCandidate(v, FocusKind.LOCAL, v.name, declLine(v))
            }
            // foreach / catch / lambda parameters — locals in spirit
            for (p in PsiTreeUtil.findChildrenOfType(body, PsiParameter::class.java)) {
                out += FocusCandidate(p, FocusKind.LOCAL, p.name, declLine(p))
            }
        }
        method.containingClass?.fields?.forEach { f ->
            out += FocusCandidate(f, FocusKind.FIELD, f.name, f.type.presentableText)
        }
        return method to out
    }

    fun filter(candidates: List<FocusCandidate>, pattern: String): List<FocusCandidate> {
        if (pattern.isBlank()) return candidates
        val matches = nameFilter(pattern)
        return candidates.filter { matches(it.name) }
    }

    /** Fuzzy, camel-hump name match; a blank pattern matches everything. */
    fun nameFilter(pattern: String): (String) -> Boolean {
        if (pattern.isBlank()) return { true }
        val matcher = NameUtil.buildMatcher("*$pattern").build()
        return { matcher.matches(it) }
    }

    private fun declLine(v: PsiVariable): String {
        val text = (v.parent ?: v).text.lineSequence().first().trim()
        return if (text.length <= 48) text else text.take(47) + "…"
    }
}
