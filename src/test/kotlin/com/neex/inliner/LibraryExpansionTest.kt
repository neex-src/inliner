package com.neex.inliner

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

class LibraryExpansionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DESCRIPTOR

    fun testLibraryMethodWithAttachedSources() {
        myFixture.configureByText("U.java", "class U { int u() { return new libfoo.Foo().b<caret>ar(); } }")
        InlineExpansionService.toggle(myFixture.editor, project)

        val state = myFixture.editor.getUserData(Keys.HOST_STATE)
        assertNotNull("library call with attached sources should expand", state)
        val node = state!!.roots[0]
        assertEquals("bar", node.methodPtr.element?.name)
        assertNull("real source document expected, not a detached one", node.pinnedPsiFile)
        val visible = node.fragmentEditor.document.text
            .substring(node.methodRange.startOffset, node.methodRange.endOffset)
        assertTrue("attached-sources body expected, got: $visible", visible.contains("baz() + 1"))
    }

    fun testRecursionInsideLibrarySources() {
        myFixture.configureByText("U.java", "class U { int u() { return new libfoo.Foo().b<caret>ar(); } }")
        InlineExpansionService.toggle(myFixture.editor, project)
        val state = myFixture.editor.getUserData(Keys.HOST_STATE)!!
        val root = state.roots[0]
        val frag = root.fragmentEditor

        val bazCall = frag.document.text.indexOf("baz()", root.methodRange.startOffset)
        assertTrue(bazCall >= 0)
        frag.caretModel.moveToOffset(bazCall + 1)
        InlineExpansionService.toggle(frag, project)

        assertEquals("recursion into a library-source frame should work", 1, root.children.size)
        assertEquals("baz", root.children[0].methodPtr.element?.name)
    }

    fun testCompiledMethodWithoutSourcesShowsMirror() {
        myFixture.configureByText("V.java", "class V { int v() { return new libnosrc.Bare().fo<caret>rtyTwo(); } }")
        InlineExpansionService.toggle(myFixture.editor, project)

        val state = myFixture.editor.getUserData(Keys.HOST_STATE)
        assertNotNull("compiled call without sources should expand via its mirror", state)
        val node = state!!.roots[0]
        assertEquals("fortyTwo", node.methodPtr.element?.name)
        val visible = node.fragmentEditor.document.text
            .substring(node.methodRange.startOffset, node.methodRange.endOffset)
        assertTrue("mirror should at least show the signature, got: $visible", visible.contains("fortyTwo"))
    }

    fun testAProjectClassShadowedByALibraryClassResolvesToTheProjectSource() {
        val own = myFixture.addFileToProject("libfoo/Foo.java", "package libfoo;\npublic class Foo { public int bar() { return 1; } }")
        // the library ahead of the module's own sources: what a classpath order can do to a lookup in allScope
        val original = com.intellij.openapi.roots.ModuleRootManager.getInstance(module).orderEntries.toList()
        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model ->
            val entries = model.orderEntries
            val (sources, rest) = entries.partition { it is com.intellij.openapi.roots.ModuleSourceOrderEntry }
            model.rearrangeOrderEntries((rest + sources).toTypedArray())
        }
        val replay = com.neex.inliner.replay.Replay(
            com.neex.inliner.recorder.Recording(
                mapOf(1L to "main"),
                listOf(
                    com.neex.inliner.recorder.Hit(
                        0, 1, com.neex.inliner.recorder.Site("libfoo.Foo", "bar", "()I", 2, "libfoo/Foo.java"), true, false, 1, emptyList(),
                    ),
                ),
                emptySet(), false,
            ),
        )
        val found = com.intellij.openapi.application.ReadAction.compute<Map<String, com.intellij.openapi.vfs.VirtualFile>, Throwable> {
            com.neex.inliner.replay.ReplayService.sourcesOf(project, replay)
        }
        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model -> model.rearrangeOrderEntries(model.orderEntries.sortedBy { e -> original.indexOfFirst { it.presentableName == e.presentableName } }.toTypedArray()) }
        assertEquals(own.virtualFile, found["libfoo.Foo"])
    }

    companion object {
        private val libRoot = File("src/test/testData/lib").absolutePath

        private val DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                addLib(model, "libfoo", "libfoo.jar", "libfoo-sources.jar")
                addLib(model, "libnosrc", "libnosrc.jar", null)
            }

            private fun addLib(model: ModifiableRootModel, name: String, classesJar: String, sourcesJar: String?) {
                val lib = model.moduleLibraryTable.createLibrary(name)
                val libModel = lib.modifiableModel
                libModel.addRoot(VfsUtil.getUrlForLibraryRoot(File(libRoot, classesJar)), OrderRootType.CLASSES)
                if (sourcesJar != null) {
                    libModel.addRoot(VfsUtil.getUrlForLibraryRoot(File(libRoot, sourcesJar)), OrderRootType.SOURCES)
                }
                libModel.commit()
            }
        }
    }
}
