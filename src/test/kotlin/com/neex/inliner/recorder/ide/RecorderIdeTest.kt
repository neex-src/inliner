package com.neex.inliner.recorder.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.inliner.recorder.Hit
import com.neex.inliner.recorder.Recording
import com.neex.inliner.recorder.Site
import com.neex.inliner.recorder.VarValue
import java.time.LocalDateTime

class RecorderIdeTest : LightJavaCodeInsightFixtureTestCase() {

    fun testProjectPackagesFoldToShortestPrefixes() {
        myFixture.addClass("package com.shop; public class Cart {}")
        myFixture.addClass("package com.shop.web; public class Api {}")
        myFixture.addClass("package org.acme.tools; public class Knife {}")
        myFixture.addClass("public class Loose {}")
        assertEquals(listOf("com.shop", "org.acme.tools"), ReadAction.compute<List<String>, Throwable> { ProjectPackages.of(project) })
    }

    fun testFoldKeepsSiblingsApart() {
        assertEquals(listOf("a.b", "a.bc"), ProjectPackages.fold(listOf("a.bc", "a.b.c", "a.b", "")))
    }

    fun testProjectUnderAFrameworkPackageIsStillRecorded() {
        val settings = RecorderSettings()
        val scope = settings.scope(listOf("org.apache.myproject"))
        assertTrue(scope.covers("org.apache.myproject.Main"))
        assertFalse("not ours", scope.covers("org.apache.commons.Lang"))
        settings.state.exclude = "org.apache.myproject.generated, org.apache.myproject.dto"
        settings.state.include = "com.vendor.sdk"
        val narrowed = settings.scope(listOf("org.apache.myproject"))
        assertFalse(narrowed.covers("org.apache.myproject.generated.Stub"))
        assertFalse(narrowed.covers("org.apache.myproject.dto.Order"))
        assertTrue(narrowed.covers("com.vendor.sdk.Client"))
        assertFalse("the built-in list still applies elsewhere", narrowed.covers("org.springframework.web.Servlet"))
    }

    fun testStoreSavesListsNewestFirstAndLoads() {
        val store = RecordingStore(FileUtil.createTempDirectory("recordings", null).toPath())
        val site = Site("com.shop.Cart", "total", "()J", 12, "com/shop/Cart.java")
        val one = Recording(mapOf(1L to "main"), listOf(Hit(0, 1L, site, true, false, 3, listOf(VarValue("sum", "40")))), emptySet(), false)
        val two = Recording(mapOf(1L to "main"), emptyList(), setOf(site), true)
        val at = LocalDateTime.of(2026, 10, 27, 10, 0)
        val first = store.save(one, at)
        val second = store.save(two, at.plusMinutes(1))
        val third = store.save(two, at.plusMinutes(2))
        val sameTime = store.save(one, at.plusMinutes(2))
        assertEquals(4, store.list().size)
        assertEquals(listOf(second, first), store.list().takeLast(2))
        assertTrue(store.list().take(2).containsAll(listOf(third, sameTime)))
        val back = store.load(first)
        assertEquals("40", back.hits.single().values.single().text)
        assertTrue(store.load(second).truncated)
    }

    fun testPackagesComeFromTheDirectoriesWithoutReadingAnyFile() {
        // the directory says com.odd.pkg, the file says something else: only a PSI read would tell
        val file = myFixture.tempDirFixture.createFile("com/odd/pkg/Odd.java", "package declared.elsewhere;\npublic class Odd {}")
        val cached = com.intellij.psi.impl.PsiManagerEx.getInstanceEx(project).fileManager
        assertNull(cached.getCachedPsiFile(file))
        val packages = ReadAction.compute<List<String>, Throwable> { ProjectPackages.of(project) }
        assertEquals(listOf("com.odd.pkg"), packages)
        assertNull("no PSI was made for the file", cached.getCachedPsiFile(file))
    }

    fun testTestPackagesAreIncludedForATestRun() {
        val tests = myFixture.tempDirFixture.findOrCreateDir("tests")
        val file = myFixture.tempDirFixture.createFile("tests/com/shop/CartTest.java", "package com.shop;\npublic class CartTest {}")
        myFixture.addClass("package com.shop.main; public class Cart {}")
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(module, tests, true)
        try {
            assertEquals("production only", listOf("com.shop.main"), ReadAction.compute<List<String>, Throwable> { ProjectPackages.of(project) })
            assertEquals(
                "a test run records the tests' own code too",
                listOf("com.shop"),
                ReadAction.compute<List<String>, Throwable> { ProjectPackages.of(project, includeTests = true) },
            )
            assertNotNull(file)
        } finally {
            com.intellij.testFramework.PsiTestUtil.removeSourceRoot(module, tests)
        }
    }
}
