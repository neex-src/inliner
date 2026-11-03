package com.neex.inliner.recorder

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Method

/** Just enough of the JVM instruction set to find returns and recognise one-instruction methods. */
internal object Bytecode {

    private const val ALOAD_0 = 0x2a
    private const val GETFIELD = 0xb4
    private const val GETSTATIC = 0xb2
    private const val PUTFIELD = 0xb5
    private const val PUTSTATIC = 0xb3
    private const val RETURN = 0xb1
    private const val INVOKEDYNAMIC = 0xba
    private const val TABLESWITCH = 0xaa
    private const val LOOKUPSWITCH = 0xab
    private const val WIDE = 0xc4
    private const val IINC = 0x84

    /** ireturn … return. */
    private fun isReturn(op: Int) = op in 0xac..0xb1

    private val LOAD_0 = setOf(0x1a, 0x1e, 0x22, 0x26, 0x2a)
    private val LOAD_1 = setOf(0x1b, 0x1f, 0x23, 0x27, 0x2b)

    fun code(method: Method): ByteArray? =
        try {
            method.bytecodes()
        } catch (_: UnsupportedOperationException) {
            null
        }

    /** Offsets of the method's return instructions. */
    fun returnOffsets(code: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc].toInt() and 0xff
            if (isReturn(op)) out += pc
            pc += length(code, pc)
        }
        return out
    }

    /** `return this.f`, `this.f = v`, and their static forms: nothing to watch happen. */
    fun isAccessor(method: Method): Boolean {
        // an accessor is one or two lines long: only those are worth a bytecodes() round trip
        val lines = try {
            method.allLineLocations().size
        } catch (_: AbsentInformationException) {
            return false
        }
        if (lines > 2) return false
        val c = code(method)?.map { it.toInt() and 0xff } ?: return false
        return when (c.size) {
            5 -> c[0] == ALOAD_0 && c[1] == GETFIELD && isReturn(c[4]) && c[4] != RETURN ||
                c[0] in LOAD_0 && c[1] == PUTSTATIC && c[4] == RETURN
            4 -> c[0] == GETSTATIC && isReturn(c[3]) && c[3] != RETURN
            6 -> c[0] == ALOAD_0 && c[1] in LOAD_1 && c[2] == PUTFIELD && c[5] == RETURN
            else -> false
        }
    }

    /** A body that is one invokedynamic and a return: how javac writes a record's toString & co. */
    fun isObjectMethodsBootstrap(method: Method): Boolean {
        val c = code(method) ?: return false
        return c.size <= 8 && c.any { (it.toInt() and 0xff) == INVOKEDYNAMIC } && isReturn(c.last().toInt() and 0xff)
    }

    private fun length(code: ByteArray, pc: Int): Int {
        val op = code[pc].toInt() and 0xff
        return when (op) {
            TABLESWITCH -> {
                val base = pad(pc)
                val low = int(code, base + 4)
                val high = int(code, base + 8)
                base + 12 + (high - low + 1) * 4 - pc
            }
            LOOKUPSWITCH -> {
                val base = pad(pc)
                base + 8 + int(code, base + 4) * 8 - pc
            }
            WIDE -> if ((code[pc + 1].toInt() and 0xff) == IINC) 6 else 4
            0x10, 0x12, in 0x15..0x19, in 0x36..0x3a, 0xa9, 0xbc -> 2
            0x11, 0x13, 0x14, IINC, in 0x99..0xa8, in 0xb2..0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3
            0xc5 -> 4
            0xb9, INVOKEDYNAMIC, 0xc8, 0xc9 -> 5
            else -> 1
        }
    }

    /** First offset after the switch opcode that is a multiple of four. */
    private fun pad(pc: Int) = (pc + 4) and 3.inv()

    private fun int(code: ByteArray, at: Int): Int =
        ((code[at].toInt() and 0xff) shl 24) or ((code[at + 1].toInt() and 0xff) shl 16) or
            ((code[at + 2].toInt() and 0xff) shl 8) or (code[at + 3].toInt() and 0xff)
}
