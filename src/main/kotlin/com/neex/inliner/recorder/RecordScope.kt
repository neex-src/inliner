package com.neex.inliner.recorder

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ClassType
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType

/**
 * Which code the recorder arms: the user's own, never framework code and never classes that were
 * generated at runtime. Every armed line costs a debugger round trip each time it runs, so what
 * stays out matters as much as what goes in.
 *
 * [include] and [exclude] are package or class names, each covering what is nested under it
 * (`com.example` covers `com.example.shop.Cart` and `com.example$Inner`); exclude wins.
 */
class RecordScope(
    val include: List<String>,
    val exclude: List<String> = DEFAULT_EXCLUDES,
    /** Getters, setters and record accessors that only move a field: Jackson calls them by the dozen. */
    val skipAccessors: Boolean = true,
    val skipConstructors: Boolean = false,
) {

    fun covers(className: String): Boolean =
        include.any { under(className, it) } && exclude.none { under(className, it) }

    fun records(type: ReferenceType): Boolean {
        val name = type.name()
        if (!covers(name) || isGenerated(name)) return false
        // Runtime-made classes have no source: CGLIB says "<generated>", hidden classes throw.
        val source = try {
            type.sourceName()
        } catch (_: AbsentInformationException) {
            return false
        }
        return source != "<generated>"
    }

    fun records(method: Method): Boolean {
        if (method.isAbstract || method.isNative || method.isBridge) return false
        // Lambda bodies are synthetic methods of the declaring class; other synthetics are plumbing.
        if (method.isSynthetic && !method.name().startsWith("lambda$")) return false
        if (method.isStaticInitializer) return false
        if (skipConstructors && method.isConstructor) return false
        if (skipAccessors && (Bytecode.isAccessor(method) || isGeneratedRecordMethod(method))) return false
        return true
    }

    /** A record's toString / hashCode / equals the compiler wrote: one invokedynamic. */
    private fun isGeneratedRecordMethod(method: Method): Boolean {
        val type = method.declaringType() as? ClassType ?: return false
        if (type.superclass()?.name() != "java.lang.Record") return false
        if (method.name() !in setOf("toString", "hashCode", "equals")) return false
        return Bytecode.isObjectMethodsBootstrap(method)
    }

    companion object {
        val DEFAULT_EXCLUDES = listOf(
            "java", "javax", "jakarta", "jdk", "sun", "com.sun", "kotlin", "kotlinx", "scala", "groovy",
            "org.springframework", "org.apache", "org.hibernate", "org.aspectj", "org.slf4j",
            "ch.qos.logback", "com.fasterxml", "com.zaxxer", "io.micrometer", "io.netty", "reactor",
            "net.bytebuddy", "org.mockito", "org.junit", "junit", "com.intellij.rt",
        )

        private fun under(name: String, prefix: String): Boolean =
            name == prefix || name.startsWith("$prefix.") || name.startsWith("$prefix$")

        /** CGLIB / ByteBuddy / Hibernate / Mockito proxies, hidden lambda classes, JDK proxies. */
        fun isGenerated(name: String): Boolean =
            "$$" in name || "/0x" in name || "\$HibernateProxy$" in name || "\$ByteBuddy$" in name ||
                "\$MockitoMock$" in name || PROXY.matches(name.substringAfterLast('.'))

        private val PROXY = Regex("\\\$Proxy\\d+")
    }
}
