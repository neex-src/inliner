package com.neex.inliner.focus

import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable

/**
 * Bounded intra-method value flow. Edges come from assignments and initializers
 * (`a = expr`, `int a = expr`, `a += expr`) and foreach (`for (T x : expr)`): every variable
 * referenced in the RHS flows into the assigned variable. Calls in the RHS contribute their
 * argument variables (`r = compute(value)` yields value → r) — the frame propagation follows
 * the same value *into* the callee separately. Fields can be flow nodes, but there is no
 * cross-method heap tracking, no aliasing, no collections, no arrays.
 */
object FocusFlow {

    class Edges(
        val forward: Map<PsiVariable, Set<PsiVariable>>,
        val backward: Map<PsiVariable, Set<PsiVariable>>,
    )

    fun edgesOf(scope: PsiMethod): Edges {
        val fwd = HashMap<PsiVariable, MutableSet<PsiVariable>>()
        val bwd = HashMap<PsiVariable, MutableSet<PsiVariable>>()

        fun edge(from: PsiVariable, to: PsiVariable) {
            if (from == to) return
            fwd.getOrPut(from) { mutableSetOf() } += to
            bwd.getOrPut(to) { mutableSetOf() } += from
        }

        scope.body?.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitLocalVariable(variable: PsiLocalVariable) {
                super.visitLocalVariable(variable)
                variable.initializer?.let { init -> rhsVars(init).forEach { edge(it, variable) } }
            }

            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                val target = (expression.lExpression as? PsiReferenceExpression)?.resolve() as? PsiVariable
                    ?: return // array element / heap write: flow stops here
                expression.rExpression?.let { rhs -> rhsVars(rhs).forEach { edge(it, target) } }
            }

            override fun visitForeachStatement(statement: PsiForeachStatement) {
                super.visitForeachStatement(statement)
                val param = statement.iterationParameter
                statement.iteratedValue?.let { iterated ->
                    rhsVars(iterated).forEach { edge(it, param) }
                }
            }
        })
        return Edges(fwd, bwd)
    }

    private fun rhsVars(expr: PsiExpression): List<PsiVariable> {
        val out = mutableListOf<PsiVariable>()
        expr.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                super.visitReferenceExpression(expression)
                (expression.resolve() as? PsiVariable)?.let { out += it }
            }
        })
        return out
    }

    /**
     * The variables carrying [root]'s value: the root, everything downstream of it (dependents —
     * where the value goes), and everything upstream of it (contributors — what writes into it).
     * Root-centric on purpose: contributors' other dependents (siblings) are not included. A
     * variable that is both (a cycle) counts as downstream.
     */
    fun trace(edges: Edges, root: PsiVariable): Map<PsiVariable, Relation> {
        fun bfs(adj: Map<PsiVariable, Set<PsiVariable>>): Set<PsiVariable> {
            val seen = mutableSetOf(root)
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                for (next in adj[queue.removeFirst()].orEmpty()) {
                    if (seen.add(next)) queue.add(next)
                }
            }
            return seen
        }
        val out = LinkedHashMap<PsiVariable, Relation>()
        out[root] = Relation.ROOT
        bfs(edges.forward).forEach { out.putIfAbsent(it, Relation.DOWNSTREAM) }
        bfs(edges.backward).forEach { out.putIfAbsent(it, Relation.UPSTREAM) }
        return out
    }
}
