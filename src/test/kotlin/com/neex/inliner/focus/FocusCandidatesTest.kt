package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusCandidatesTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class C {
            String title;
            int count;
            void process(String inputName, int limit) {
                int localTotal = limit;
                for (char letter : inputName.toCharArray()) {
                    localTotal += letter;
                }
                <caret>System.out.println(localTotal + title + count);
            }
            void other(int unrelatedParam) { }
        }
    """.trimIndent()

    private fun collected(): Pair<String, List<FocusCandidate>> {
        myFixture.configureByText("C.java", sample)
        val (method, candidates) = FocusCandidates.collect(myFixture.file, myFixture.editor.caretModel.offset)!!
        return method.name to candidates
    }

    fun testCollectsParamsLocalsAndFieldsOfEnclosingMethodOnly() {
        val (methodName, candidates) = collected()
        assertEquals("process", methodName)
        val byName = candidates.associateBy { it.name }

        assertEquals(FocusKind.PARAMETER, byName["inputName"]?.kind)
        assertEquals(FocusKind.PARAMETER, byName["limit"]?.kind)
        assertEquals(FocusKind.LOCAL, byName["localTotal"]?.kind)
        assertEquals("foreach variable is a local in spirit", FocusKind.LOCAL, byName["letter"]?.kind)
        assertEquals(FocusKind.FIELD, byName["title"]?.kind)
        assertEquals(FocusKind.FIELD, byName["count"]?.kind)
        assertNull("other method's param must not appear", byName["unrelatedParam"])
    }

    fun testLocalDetailIsCroppedDeclarationLine() {
        val (_, candidates) = collected()
        val local = candidates.first { it.name == "localTotal" }
        assertTrue(local.detail.contains("int localTotal = limit"))
    }

    fun testFuzzyFilter() {
        val (_, candidates) = collected()
        val loc = FocusCandidates.filter(candidates, "loc").map { it.name }
        assertEquals(listOf("localTotal"), loc)
        val camelHump = FocusCandidates.filter(candidates, "lT").map { it.name }
        assertTrue("localTotal" in camelHump)
        val all = FocusCandidates.filter(candidates, "")
        assertEquals(candidates.size, all.size)
    }
}
