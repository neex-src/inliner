package com.neex.inliner.focus

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.neex.inliner.FrameRows

/** How an occurrence uses its variable. */
enum class Access {
    /** Declared without a value (`int x;`, a method parameter). */
    DECLARATION,
    READ,
    WRITE,

    /** Both at once: `x++`, `x += 1`. */
    READ_WRITE;

    val reads: Boolean get() = this == READ || this == READ_WRITE
    val writes: Boolean get() = this == WRITE || this == READ_WRITE
}

/** How a traced variable is related to the tracked one. */
enum class Relation {
    /** The tracked variable itself. */
    ROOT,

    /** The value flows into it (transitive mode). */
    DOWNSTREAM,

    /** It flows into the value (transitive mode). */
    UPSTREAM,
}

/** One occurrence of a variable that carries [entry]'s value. */
class Touch(
    val entry: FocusEntry,
    val variable: PsiVariable,
    val relation: Relation,
    val range: TextRange,
    val access: Access,
) {
    val direct: Boolean get() = relation == Relation.ROOT
}

/**
 * What the focus sees in one file: every [Touch] of the enabled entries, by line. The analysis
 * half of focus, kept apart from how it is shown ([FocusHighlighter]) and what stays visible
 * ([FocusFolder] with a [FoldRule]):
 *
 *     FocusEntry   what the user tracks
 *       → trace    the variables carrying its value in its scope: the variable itself, plus in
 *                  transitive mode where the value goes and what flows into it ([FocusFlow])
 *       → Touch    one occurrence of a traced variable: where, read or write, through which entry
 *       → picture  all of them, by line
 *
 * Views ask questions of the picture, never of PSI. A new question — where two tracked values
 * meet, where one is written after another is read — is a new predicate over the same touches,
 * not a new analysis pass.
 */
class FocusPicture(val touches: List<Touch>, document: Document) {

    /** line → the touches on it */
    val byLine: Map<Int, List<Touch>> = touches.groupBy { document.getLineNumber(it.range.startOffset) }

    /** Lines with a touch of a tracked variable itself. */
    val directLines: Set<Int> = byLine.filterValues { line -> line.any { it.direct } }.keys

    /** Lines reached only through value flow. */
    val propagatedLines: Set<Int> = byLine.keys - directLines

    fun touchesOn(line: Int): List<Touch> = byLine[line].orEmpty()

    companion object {

        /**
         * [editor]: the editor the picture is for, when it matters which. A frame is lit with what
         * its own call derived and only inside its method; the host editor, which may show the same
         * file (a callee in the host's own file), is not lit with entries derived for a frame.
         */
        fun of(set: FocusSet, psiFile: PsiFile, document: Document, editor: Editor? = null): FocusPicture {
            val frame = editor?.let(FrameRows::frameOf)
            val touches = mutableListOf<Touch>()
            val edgesCache = HashMap<PsiMethod, FocusFlow.Edges>()
            for (entry in set.enabledEntries()) {
                val source = entry.sourceNode
                if (editor != null && source != null && source.fragmentEditor !== editor) continue
                val scope = entry.scopePtr.element ?: continue
                if (scope.containingFile != psiFile) continue
                val target = entry.ptr.element ?: continue
                val trace = if (set.transitive) {
                    FocusFlow.trace(edgesCache.getOrPut(scope) { FocusFlow.edgesOf(scope) }, target)
                } else {
                    mapOf(target to Relation.ROOT)
                }
                for ((variable, relation) in trace) {
                    occurrences(variable, scope)
                        .filter { (range, _) -> range.endOffset <= document.textLength }
                        .filter { (range, _) -> frame == null || !frame.methodRange.isValid || (range.startOffset >= frame.methodRange.startOffset && range.endOffset <= frame.methodRange.endOffset) }
                        .mapTo(touches) { (range, access) -> Touch(entry, variable, relation, range, access) }
                }
            }
            return FocusPicture(touches, document)
        }

        /** The declaration (if inside [scope]) and every reference in [scope], with their access. */
        private fun occurrences(variable: PsiVariable, scope: PsiMethod): List<Pair<TextRange, Access>> {
            val detector = ReadWriteAccessDetector.findDetector(variable)
            val out = mutableListOf<Pair<TextRange, Access>>()
            variable.nameIdentifier?.let { id ->
                if (PsiTreeUtil.isAncestor(scope, id, false)) {
                    val writes = detector?.isDeclarationWriteAccess(variable) == true
                    out += id.textRange to (if (writes) Access.WRITE else Access.DECLARATION)
                }
            }
            for (ref in ReferencesSearch.search(variable, LocalSearchScope(scope)).findAll()) {
                val base = ref.element.textRange?.startOffset ?: continue
                val access = when (detector?.getReferenceAccess(variable, ref)) {
                    ReadWriteAccessDetector.Access.Write -> Access.WRITE
                    ReadWriteAccessDetector.Access.ReadWrite -> Access.READ_WRITE
                    else -> Access.READ
                }
                out += ref.rangeInElement.shiftRight(base) to access
            }
            return out
        }
    }
}
