package com.neex.inliner

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataMap
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class FrameCaretTest : LightJavaCodeInsightFixtureTestCase() {

    private val sample = """
        class A {
            void caller() {
                int a = 1;
                callee(a); helper();
                int b = 2;
            }
            void callee(int p) {
                int x = p;
                helper();
                int y = x;
            }
            void helper() {
                int h = 0;
            }
        }
    """.trimIndent()

    /** The editor the caret is in, as the keyboard focus would be. */
    private lateinit var current: Editor

    override fun setUp() {
        super.setUp()
        FrameCaret.testFocus = { current = it }
    }

    override fun tearDown() {
        try {
            FrameCaret.testFocus = null
        } finally {
            super.tearDown()
        }
    }

    private val host get() = myFixture.editor

    private fun caretOn(editor: Editor, text: String) {
        val offset = editor.document.text.indexOf(text, startIndexIn(editor))
        assertTrue("'$text' not found", offset >= 0)
        editor.caretModel.moveToOffset(offset)
        current = editor
    }

    /** In a frame, search from the method start: the frame shows the whole file's document. */
    private fun startIndexIn(editor: Editor) = FrameRows.frameOf(editor)?.methodRange?.startOffset ?: 0

    private fun expandAt(editor: Editor, call: String): ExpansionNode {
        caretOn(editor, call)
        InlineExpansionService.toggle(editor, project)
        val nodes = FrameRows.framesIn(editor)
        return nodes.first { it.methodPtr.element?.name + "(" == call.substringBefore("(") + "(" }
    }

    /** "host:3", or "callee:6" — the method a frame shows and the caret's line in its document. */
    private fun where(): String {
        val name = FrameRows.frameOf(current)?.methodPtr?.element?.name ?: "host"
        return "$name:${current.caretModel.logicalPosition.line}"
    }

    private fun press(action: String, times: Int = 1): List<String> = (1..times).map {
        EditorTestUtil.executeAction(current, action)
        where()
    }

    fun testDownWalksIntoThroughAndOutOfNestedFrames() {
        myFixture.configureByText("A.java", sample)
        val callee = expandAt(host, "callee(a)")
        expandAt(callee.fragmentEditor, "helper();\n        int y")
        caretOn(host, "callee(a)")

        assertEquals(
            listOf("callee:6", "callee:7", "callee:8", "helper:11", "helper:12", "helper:13", "callee:9", "callee:10", "host:4"),
            press(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, 9),
        )
    }

    fun testUpWalksTheSameRowsBack() {
        myFixture.configureByText("A.java", sample)
        val callee = expandAt(host, "callee(a)")
        expandAt(callee.fragmentEditor, "helper();\n        int y")
        caretOn(host, "int b")

        assertEquals(
            listOf("callee:10", "callee:9", "helper:13", "helper:12", "helper:11", "callee:8", "callee:7", "callee:6", "host:3", "host:2"),
            press(IdeActions.ACTION_EDITOR_MOVE_CARET_UP, 10),
        )
    }

    fun testFramesUnderTheSameLineFollowEachOther() {
        myFixture.configureByText("A.java", sample)
        val first = expandAt(host, "callee(a)")
        val second = expandAt(host, "helper();\n        int b")
        val order = FrameRows.framesUnder(FrameRows.anchorRow(first))
        assertEquals(listOf(first, second).toSet(), order.toSet())
        caretOn(host, "callee(a)")

        val down = press(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, 9)
        val rows = order.flatMap { node ->
            val doc = node.fragmentEditor.document
            val name = node.methodPtr.element!!.name
            (doc.getLineNumber(node.methodRange.startOffset)..doc.getLineNumber(node.methodRange.endOffset)).map { "$name:$it" }
        }
        assertEquals(rows + "host:4", down)
        assertEquals(down.reversed().drop(1) + "host:3", press(IdeActions.ACTION_EDITOR_MOVE_CARET_UP, 9))
    }

    fun testRightAtLineEndEntersFrameAndLeftComesBack() {
        myFixture.configureByText("A.java", sample)
        expandAt(host, "callee(a)")
        caretOn(host, "callee(a)")
        host.caretModel.moveToOffset(host.document.getLineEndOffset(3))

        assertEquals(listOf("callee:6"), press(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT))
        assertEquals(0, current.caretModel.logicalPosition.column)
        assertEquals(listOf("host:3"), press(IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT))
        assertEquals(host.document.getLineEndOffset(3), host.caretModel.offset)
    }

    fun testCaretCannotLeaveTheMethod() {
        myFixture.configureByText("A.java", sample)
        val node = expandAt(host, "callee(a)")
        val frame = node.fragmentEditor

        frame.caretModel.moveToOffset(frame.document.textLength)
        assertEquals(node.methodRange.endOffset, frame.caretModel.offset)
        frame.caretModel.moveToOffset(0)
        assertEquals(frame.document.getLineStartOffset(6), frame.caretModel.offset)
    }

    fun testColumnSurvivesTheTripThroughAFrame() {
        myFixture.configureByText("A.java", sample)
        expandAt(host, "callee(a)")
        caretOn(host, "= 1;")
        val column = host.caretModel.logicalPosition.column

        val trip = press(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, 7)
        assertEquals("host:4", trip.last())
        assertEquals(column, host.caretModel.logicalPosition.column)
    }

    fun testFrameAnswersAsAnEditorOfTheCallee() {
        myFixture.configureByText("A.java", sample)
        val node = expandAt(host, "callee(a)")
        val frame = node.fragmentEditor as EditorEx
        caretOn(frame, "x = p")

        // Preconditions of the platform's editor data rule (PSI_ELEMENT, LANGUAGE, …): headless
        // there is no component data context to run it, the live pass checks Alt+F7 & co.
        assertFalse(frame.editorKind == EditorKind.PREVIEW || frame.editorKind == EditorKind.CONSOLE)
        assertEquals(myFixture.file.virtualFile, frame.virtualFile)

        val data = RecordingSink().also { FramePanel(node).uiDataSnapshot(it) }
        assertSame("not the host's file editor", frame, (data[PlatformCoreDataKeys.FILE_EDITOR] as TextEditor).editor)
        assertEquals(myFixture.file.virtualFile, data[CommonDataKeys.VIRTUAL_FILE])
        assertEquals(myFixture.file, data[CommonDataKeys.PSI_FILE])
        val here = data[CommonDataKeys.NAVIGATABLE] as OpenFileDescriptor
        assertEquals("F4 opens the callee at the frame's caret", frame.caretModel.offset, here.offset)
        assertEquals(myFixture.file.virtualFile, here.file)
    }

    private class RecordingSink : DataSink {
        private val values = HashMap<String, Any?>()
        operator fun get(key: DataKey<*>): Any? = values[key.name]
        override fun <T : Any> set(key: DataKey<T>, data: T?) { values[key.name] = data }
        override fun <T : Any> setNull(key: DataKey<T>) { values[key.name] = null }
        override fun <T : Any> lazy(key: DataKey<T>, data: () -> T?) { values[key.name] = data() }
        override fun <T : Any> lazyNull(key: DataKey<T>) { values[key.name] = null }
        override fun <T : Any> lazyValue(key: DataKey<T>, data: (DataMap) -> T?) = Unit
        override fun dataSnapshot(provider: DataSnapshotProvider) = Unit
        override fun uiDataSnapshot(provider: DataProvider) = Unit
        override fun uiDataSnapshot(provider: UiDataProvider) = provider.uiDataSnapshot(this)
    }
}
