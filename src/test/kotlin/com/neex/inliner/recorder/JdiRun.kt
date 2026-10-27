package com.neex.inliner.recorder

import com.sun.jdi.Bootstrap
import com.sun.jdi.ThreadReference
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import java.io.File

/**
 * A plain JDI debugger for the tests: launches a fixture's main in a fresh JVM, arms what the
 * [Recorder] asks for as each fixture class loads, feeds it the hits, and runs to the end — the
 * part IntelliJ's debug session plays in the plugin.
 */
object JdiRun {

    class Result(val recording: Recording, val output: String, val change: Change? = null, val whatIfError: String? = null)

    /** Set [name] to [text] the first time the run reaches [line] of [method], before that line runs. */
    class WhatIfAt(val method: String, val line: Int, val name: String, val text: String)

    fun record(
        main: Class<*>,
        vararg args: String,
        scope: RecordScope = RecordScope(listOf("recfix")),
        budget: Budget = Budget(),
        busy: (ThreadReference) -> Boolean = { false },
        whatIf: WhatIfAt? = null,
    ): Result {
        var change: Change? = null
        var whatIfError: String? = null
        var whatIfDone = false
        val launcher = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = launcher.defaultArguments()
        arguments.getValue("main").setValue((listOf(main.name) + args).joinToString(" "))
        arguments.getValue("options").setValue("-cp " + classesRoot(main).path)
        val vm = launcher.launch(arguments)
        val output = StringBuffer()
        listOf(vm.process().inputStream, vm.process().errorStream).forEach { stream ->
            Thread { stream.bufferedReader().forEachLine { output.append(it).append('\n') } }.apply { isDaemon = true }.start()
        }
        val recorder = Recorder(scope, budget, busy = busy)
        val requests = vm.eventRequestManager()
        requests.createClassPrepareRequest().apply {
            addClassFilter("recfix.*")
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            enable()
        }
        val deadline = System.currentTimeMillis() + 60_000
        events@ while (true) {
            check(System.currentTimeMillis() < deadline) { "debuggee still running after 60 s\n$output" }
            val set = vm.eventQueue().remove(1_000) ?: continue
            for (event in set) {
                when (event) {
                    is ClassPrepareEvent -> recorder.arm(event.referenceType()).forEach {
                        requests.createBreakpointRequest(it.location).apply {
                            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                            enable()
                        }
                    }
                    is BreakpointEvent -> {
                        val at = event.location()
                        if (whatIf != null && !whatIfDone && at.method().name() == whatIf.method && at.lineNumber() == whatIf.line) {
                            whatIfDone = true
                            try {
                                change = WhatIf.apply(event.thread().frame(0), whatIf.name, whatIf.text)
                            } catch (e: WhatIfException) {
                                whatIfError = e.message
                            }
                        }
                        when (recorder.onHit(event.thread(), at)) {
                            Verdict.LOCATION_DONE -> event.request().disable()
                            Verdict.ALL_DONE -> requests.deleteAllBreakpoints()
                            Verdict.KEEP -> Unit
                        }
                    }
                    is VMDisconnectEvent -> break@events
                }
            }
            set.resume()
        }
        vm.process().waitFor()
        return Result(recorder.recording(change = change), output.toString(), change, whatIfError)
    }

    /** The directory [main] was compiled into (the test class loader reports no code source). */
    private fun classesRoot(main: Class<*>): File {
        val file = File(main.getResource(main.simpleName + ".class")!!.toURI())
        return generateSequence(file.parentFile) { it.parentFile }.drop(main.name.count { it == '.' }).first()
    }

    /** 1-based line of the first line in [file] (under src/test/java/recfix) containing [text]. */
    fun lineOf(file: String, text: String): Int {
        val lines = File("src/test/java/recfix/$file").readLines()
        val i = lines.indexOfFirst { text in it }
        check(i >= 0) { "'$text' not in $file" }
        return i + 1
    }
}
