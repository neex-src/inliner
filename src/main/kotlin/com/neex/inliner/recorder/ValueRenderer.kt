package com.neex.inliner.recorder

import com.sun.jdi.ArrayReference
import com.sun.jdi.CharValue
import com.sun.jdi.ClassObjectReference
import com.sun.jdi.ClassType
import com.sun.jdi.Field
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Value

/**
 * A debuggee value as text, read with plain JDWP reads: nothing runs in the debuggee — no
 * `toString()`, which could change state, throw, deadlock, or hit our own breakpoints.
 *
 * Objects show as `Type#id` (the id tells "the same object" apart from "an equal one") with their
 * fields one level deep; strings, boxes, enums and common collections as what they hold. Everything
 * is capped: a recording keeps thousands of these.
 */
class ValueRenderer(
    private val maxString: Int = 120,
    private val maxElements: Int = 8,
    private val maxFields: Int = 6,
    /** Levels of fields shown below the value itself. */
    private val depth: Int = 1,
) {
    private val fields = HashMap<ReferenceType, List<Field>>()

    fun render(value: Value?): String = render(value, 0)

    private fun render(value: Value?, level: Int): String = when (value) {
        null -> "null"
        is StringReference -> quote(value.value())
        is CharValue -> "'" + escape(value.value().toString()) + "'"
        is PrimitiveValue -> value.toString()
        is ArrayReference -> array(value, level)
        is ClassObjectReference -> "class " + simple(value.reflectedType().name())
        is ObjectReference -> obj(value, level)
        else -> value.toString()
    }

    private fun obj(o: ObjectReference, level: Int): String {
        val type = o.referenceType()
        val name = type.name()
        if (name in BOXES) return render(fieldValue(o, "value"), level)
        if (type is ClassType && type.isEnum) return simple(name) + "." + (fieldValue(o, "name") as? StringReference)?.value()
        val head = simple(name) + "#" + o.uniqueID()
        when (name) {
            "java.util.ArrayList" -> {
                val size = (fieldValue(o, "size") as? PrimitiveValue)?.intValue() ?: 0
                val data = fieldValue(o, "elementData") as? ArrayReference
                return "$head(size $size)" + if (data != null && level < depth) elements(data, size, level) else ""
            }
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.HashSet", "java.util.LinkedHashSet" -> {
                val map = if (name.endsWith("Set")) fieldValue(o, "map") as? ObjectReference else o
                val size = map?.let { (fieldValue(it, "size") as? PrimitiveValue)?.intValue() }
                return "$head(size ${size ?: "?"})"
            }
        }
        if (level >= depth) return head
        val shown = fieldsOf(type)
        if (shown.isEmpty()) return head
        val values = o.getValues(shown)
        return head + shown.joinToString(", ", "{", if (fieldsOf(type, all = true).size > shown.size) ", …}" else "}") {
            it.name() + "=" + render(values[it], level + 1)
        }
    }

    private fun array(a: ArrayReference, level: Int): String {
        val head = simple(a.referenceType().name()).replaceFirst("[]", "[${a.length()}]")
        return if (level < depth || a.length() == 0) head + elements(a, a.length(), level) else head
    }

    private fun elements(a: ArrayReference, count: Int, level: Int): String {
        val n = minOf(count, a.length(), maxElements)
        if (n == 0) return "[]"
        val shown = a.getValues(0, n).joinToString(", ") { render(it, level + 1) }
        return "[" + shown + (if (count > n) ", …" else "") + "]"
    }

    private fun fieldsOf(type: ReferenceType, all: Boolean = false): List<Field> {
        val instance = fields.getOrPut(type) { type.allFields().filter { !it.isStatic } }
        return if (all) instance else instance.take(maxFields)
    }

    private fun fieldValue(o: ObjectReference, name: String): Value? =
        o.referenceType().fieldByName(name)?.let { o.getValue(it) }

    private fun quote(s: String): String {
        val cut = if (s.length > maxString) s.take(maxString) + "…" else s
        return "\"" + escape(cut) + "\""
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"")

    companion object {
        private val BOXES = setOf(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character",
        )

        /** `com.example.Quote$Line` → `Quote.Line`. */
        fun simple(name: String): String = name.substringAfterLast('.').replace('$', '.')
    }
}
