package com.neex.inliner.recorder

import com.sun.jdi.StackFrame
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine

/** Why a what-if can't be done, in words for the user. */
class WhatIfException(message: String) : Exception(message)

/**
 * A what-if: a local variable of a suspended frame set to a value written as text — the rest of
 * the run then goes the way that value takes it. Values that can be made without running
 * anything in the debuggee: primitives, strings, `null`.
 */
object WhatIf {

    fun apply(frame: StackFrame, name: String, text: String, renderer: ValueRenderer = ValueRenderer()): Change {
        val variable = frame.visibleVariableByName(name) ?: throw WhatIfException("No variable '$name' at this line")
        val before = renderer.render(frame.getValue(variable))
        val value = parse(frame.virtualMachine(), variable.typeName(), text.trim())
        frame.setValue(variable, value)
        return Change(name, before, renderer.render(value))
    }

    fun parse(vm: VirtualMachine, type: String, text: String): Value? {
        fun bad(): Nothing = throw WhatIfException("'$text' is not a $type")
        return when (type) {
            "boolean" -> vm.mirrorOf(text.toBooleanStrictOrNull() ?: bad())
            "int" -> vm.mirrorOf(text.toIntOrNull() ?: bad())
            "long" -> vm.mirrorOf(text.removeSuffix("L").removeSuffix("l").toLongOrNull() ?: bad())
            "short" -> vm.mirrorOf(text.toShortOrNull() ?: bad())
            "byte" -> vm.mirrorOf(text.toByteOrNull() ?: bad())
            "double" -> vm.mirrorOf(text.removeSuffix("d").removeSuffix("D").toDoubleOrNull() ?: bad())
            "float" -> vm.mirrorOf(text.removeSuffix("f").removeSuffix("F").toFloatOrNull() ?: bad())
            "char" -> vm.mirrorOf(text.removeSurrounding("'").singleOrNull() ?: bad())
            else -> when {
                text == "null" -> null
                type == "java.lang.String" -> vm.mirrorOf(text.removeSurrounding("\""))
                else -> throw WhatIfException("A $type can only be set to null here: making one would run code in the program")
            }
        }
    }
}
