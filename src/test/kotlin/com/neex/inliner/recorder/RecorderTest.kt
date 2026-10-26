package com.neex.inliner.recorder

import com.neex.inliner.recorder.JdiRun.lineOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import recfix.Calls
import recfix.Hot
import recfix.Quotes
import recfix.Values
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The recorder against real JVMs: each test runs a fixture from src/test/java/recfix under JDI. */
class RecorderTest {

    private fun Recording.lineHits(method: String) = hits.filter { it.site.methodName == method && it.startsLine }

    private fun Hit.value(name: String) = values.single { it.name == name }.text

    @Test
    fun recordsThePathAndTheValuesBeforeEachLine() {
        val run = JdiRun.record(Quotes::class.java)
        assertEquals("EUR 546\n", run.output)
        val discount = run.recording.lineHits("discount")
        val expected = listOf(
            "boolean loyal", "boolean bulk", "int percent = 0", "if (loyal)", "percent += 5",
            "if (bulk)", "percent += c.business()", "return subtotal * percent",
        ).map { lineOf("Quotes.java", it) }
        assertEquals(expected, discount.map { it.site.line })
        val last = discount.last()
        assertEquals("loyal and bulk, not business: 5 + 4", "9", last.value("percent"))
        assertEquals("600", last.value("subtotal"))
        assertEquals("true", last.value("bulk"))
        assertTrue(last.value("this"), last.value("this").matches(Regex("""Quotes#\d+\{currency="EUR"}""")))
        assertTrue("the return is recorded too", run.recording.hits.any { it.site.methodName == "discount" && it.returns })
    }

    @Test
    fun skipsAccessorsGeneratedRecordMethodsAndHiddenClassesButKeepsLambdaBodies() {
        val methods = JdiRun.record(Quotes::class.java).recording.hits.map { it.site.className + "." + it.site.methodName }.toSet()
        listOf("Quotes\$Customer.yearsLoyal", "Quotes\$Customer.business", "Quotes\$Line.qty", "Quotes.getCurrency").forEach {
            assertFalse("$it is an accessor", "recfix.$it" in methods)
        }
        assertTrue(methods.toString(), "recfix.Quotes\$Line.amount" in methods)
        assertTrue(methods.toString(), methods.any { it.startsWith("recfix.Quotes.lambda\$quote$") })
        assertTrue(methods.toString(), methods.none { "$$" in it })
    }

    @Test
    fun callTreeTellsCallsApart() {
        val recording = JdiRun.record(Calls::class.java).recording
        val main = recording.threads.entries.single { it.value == "main" }.key
        val root = CallTree.build(recording).getValue(main).single()
        assertEquals("main", root.method.methodName)
        assertEquals(
            listOf("fact", "twice", "twice", "countDown", "rescue", "kind", "sparse"),
            root.children.map { it.method.methodName },
        )

        val fact = root.children[0]
        assertEquals("fact(3) → fact(2) → fact(1)", 2, generateSequence(fact) { it.children.singleOrNull() }.count() - 1)
        assertTrue(fact.returned)

        val (first, second) = root.children[1] to root.children[2]
        assertEquals("1", first.hits.first().value("x"))
        assertEquals("2", second.hits.first().value("x"))
        assertEquals("both calls on one line, made from it", first.callSite!!.site.line, second.callSite!!.site.line)

        val countDown = root.children[3]
        val loop = lineOf("Calls.java", "while (n > 0)")
        assertEquals("the loop back to the first line stays one call", 4, countDown.hits.count { it.site.line == loop && it.startsLine })

        val rescue = root.children[4]
        val fails = generateSequence(rescue.children.single()) { it.children.singleOrNull() }.toList()
        assertEquals(3, fails.size)
        assertTrue("unwound by the exception, not returned", fails.none { it.returned })
        assertTrue(rescue.returned)
        assertEquals("-1", rescue.hits.last().value("got"))

        assertEquals(lineOf("Calls.java", "case 2: return \"two\""), root.children[5].hits.single { it.returns }.site.line)
        assertEquals(lineOf("Calls.java", "case 1000: return 2"), root.children[6].hits.single { it.returns }.site.line)
    }

    @Test
    fun budgetSwitchesAHotLineOff() {
        val run = JdiRun.record(Hot::class.java, budget = Budget(perLocation = 50))
        assertEquals("499500\n", run.output)
        val body = lineOf("Hot.java", "sum += i")
        assertEquals(50, run.recording.hits.count { it.site.line == body })
        assertTrue(run.recording.exhausted.any { it.line == body })
        assertFalse(run.recording.truncated)
    }

    @Test
    fun totalBudgetEndsTheRecording() {
        val run = JdiRun.record(Hot::class.java, budget = Budget(total = 30))
        assertEquals("the program still runs to the end", "499500\n", run.output)
        assertEquals(30, run.recording.hits.size)
        assertTrue(run.recording.truncated)
    }

    @Test
    fun threadsAreRecordedApart() {
        val recording = JdiRun.record(Hot::class.java, "threads").recording
        val trees = CallTree.build(recording)
        for (name in listOf("worker-1", "worker-2")) {
            val id = recording.threads.entries.single { it.value == name }.key
            val root = trees.getValue(id).single()
            assertTrue(root.method.methodName, root.method.methodName.startsWith("lambda\$main$"))
            assertEquals("work", root.children.single().method.methodName)
        }
        val w2 = recording.threads.entries.single { it.value == "worker-2" }.key
        assertEquals("\"three\"", trees.getValue(w2).single().children.single().hits.first().value("who"))
    }

    @Test
    fun busyThreadsAreNotRecorded() {
        val recording = JdiRun.record(Hot::class.java, "threads", busy = { it.name() == "worker-1" }).recording
        assertFalse(recording.threads.values.toString(), "worker-1" in recording.threads.values)
        assertTrue("worker-2" in recording.threads.values)
    }

    @Test
    fun rendersValuesWithoutRunningAnything() {
        val hit = JdiRun.record(Values::class.java).recording.lineHits("look").first()
        assertEquals("\"line\\nbreak\"", hit.value("s"))
        assertEquals("42", hit.value("boxed"))
        assertEquals("Values.Color.GREEN", hit.value("color"))
        assertEquals("int[3][1, 2, 3]", hit.value("numbers"))
        assertTrue(hit.value("box"), hit.value("box").matches(Regex("""Values\.Box#\d+\{width=3, label="crate", inner=Values\.Box#\d+}""")))
        assertTrue(hit.value("list"), hit.value("list").matches(Regex("""ArrayList#\d+\(size 2\)\["a", "b"]""")))
        assertTrue(hit.value("map"), hit.value("map").matches(Regex("""HashMap#\d+\(size 1\)""")))
        assertEquals("null", hit.value("nothing"))
        assertEquals("'x'", hit.value("ch"))
    }

    @Test
    fun fileRoundTrip() {
        val recording = JdiRun.record(Hot::class.java, budget = Budget(perLocation = 20)).recording
        val bytes = ByteArrayOutputStream().also { RecordingFile.write(recording, it) }.toByteArray()
        val back = RecordingFile.read(ByteArrayInputStream(bytes))
        assertEquals(recording.threads, back.threads)
        assertEquals(recording.exhausted, back.exhausted)
        assertEquals(recording.truncated, back.truncated)
        assertEquals(recording.hits.size, back.hits.size)
        recording.hits.zip(back.hits).forEach { (a, b) ->
            assertEquals(a.seq, b.seq)
            assertEquals(a.thread, b.thread)
            assertEquals(a.site, b.site)
            assertEquals(a.startsLine to a.returns, b.startsLine to b.returns)
            assertEquals(a.depth, b.depth)
            assertEquals(a.values, b.values)
        }
    }

    @Test
    fun scopeNamesGeneratedClassesAndPackages() {
        listOf("com.x.Svc\$\$SpringCGLIB\$\$0", "com.x.Foo\$\$Lambda/0x0000012", "jdk.proxy1.\$Proxy12", "com.sun.proxy.\$Proxy3")
            .forEach { assertTrue(it, RecordScope.isGenerated(it)) }
        assertFalse(RecordScope.isGenerated("com.x.Foo\$Inner"))
        val scope = RecordScope(listOf("com.example"), exclude = listOf("com.example.gen"))
        assertTrue(scope.covers("com.example.Cart"))
        assertTrue(scope.covers("com.example.shop.Cart\$Line"))
        assertFalse(scope.covers("com.examples.Cart"))
        assertFalse(scope.covers("com.example.gen.Dto"))
    }
}
