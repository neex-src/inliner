package com.neex.inliner.focus

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FocusPopupTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class P {
            void work(int limit, int lower) {
                int local1 = limit;
                int other = lower;
                <caret>System.out.println(local1 + other);
            }
        }
    """.trimIndent()

    private fun setUpPopup(vararg trackedNames: String): Pair<FocusSet, List<FocusCandidate>> {
        myFixture.configureByText("P.java", sample)
        val (method, candidates) = FocusCandidates.collect(myFixture.file, myFixture.editor.caretModel.offset)!!
        val set = FocusService.focusSet(myFixture.editor, project, create = true)!!
        for (name in trackedNames) FocusService.add(set, candidates.first { it.name == name }.variable, method)
        return set to candidates
    }

    private fun names(items: List<FocusPopup.Item>) = items.map {
        when (it) {
            is FocusPopup.Item.Tracked -> "+" + it.entry.name
            is FocusPopup.Item.Candidate -> it.candidate.name
        }
    }

    fun testNoFilterListsTrackedFirstThenTheRest() {
        val (set, candidates) = setUpPopup("other")
        val items = FocusPopup.listing(set, candidates, "")
        assertEquals(listOf("+other", "limit", "lower", "local1"), names(items))
        assertEquals(0, FocusPopup.preferredIndex(items, ""))
        assertEquals("work", (items[0] as FocusPopup.Item.Tracked).scopeName)
    }

    fun testFilterHidesTrackedEntriesThatDoNotMatch() {
        val (set, candidates) = setUpPopup("other")
        val items = FocusPopup.listing(set, candidates, "loc")
        assertEquals(listOf("local1"), names(items))
        assertEquals(0, FocusPopup.preferredIndex(items, "loc"))
    }

    fun testFilterSelectsFirstMatchingCandidateNotTrackedEntry() {
        val (set, candidates) = setUpPopup("limit")
        val items = FocusPopup.listing(set, candidates, "l")
        assertEquals(listOf("+limit", "lower", "local1"), names(items))
        assertEquals("Enter after typing must add the match, not toggle the tracked one", 1, FocusPopup.preferredIndex(items, "l"))
    }

    fun testFilterMatchingOnlyTrackedSelectsIt() {
        val (set, candidates) = setUpPopup("limit")
        val items = FocusPopup.listing(set, candidates, "lim")
        assertEquals(listOf("+limit"), names(items))
        assertEquals(0, FocusPopup.preferredIndex(items, "lim"))
    }
}
