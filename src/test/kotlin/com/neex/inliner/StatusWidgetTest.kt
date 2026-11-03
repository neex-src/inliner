package com.neex.inliner

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.replay.Replay
import com.neex.inliner.replay.ReplayService

/** The status bar item exists only while something is recorded or replayed. */
class StatusWidgetTest : LightJavaCodeInsightFixtureTestCase() {

    private val factory = InlinerStatusWidgetFactory()

    override fun tearDown() {
        try {
            ReplayService.of(project).close()
        } finally {
            super.tearDown()
        }
    }

    fun testNotAvailableWhileNothingRunsAvailableDuringAReplayAndGoneWithIt() {
        assertFalse("an empty item in every project's status bar is noise", factory.isAvailable(project))

        myFixture.addFileToProject("p/A.java", "package p;\npublic class A {\n    void run() {\n        int x = 1;\n    }\n}")
        val hit = Hit(0, 1, Site("p.A", "run", "()V", 4, "p/A.java"), true, false, 1, emptyList())
        val replay = Replay(Recording(mapOf(1L to "main"), listOf(hit), emptySet(), false))
        val sources = ReadAction.compute<Map<String, VirtualFile>, Throwable> { ReplayService.sourcesOf(project, replay) }
        ReplayService.of(project).start(replay, "test", sources)
        assertTrue(factory.isAvailable(project))

        ReplayService.of(project).close()
        assertFalse(factory.isAvailable(project))
    }
}
