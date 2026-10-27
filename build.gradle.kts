import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.neex"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Pinned, not local(...): the Toolbox install moves on every update and took the
        // build with it twice (243 → 262, then 2026.2.1 → 2026.2.3). Bump this deliberately.
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            // the IDE actually in daily use, whatever Toolbox updated it to
            local(file("~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate"))
        }
    }
}

kotlin {
    compilerOptions {
        // Implement the platform's Kotlin interfaces through their own default methods: no
        // generated stubs that override (and so "use") their deprecated members.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// jvmTarget intentionally not set: the IntelliJ Platform plugin pins it to the
// platform's Java version (25 for 262), and Kotlin 2.3 can emit that.

// Tests fork on the IDE's own JBR 25 (registered via org.gradle.java.installations.paths) —
// the same runtime the plugin sees in production.
tasks.test {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // 2026.2's Ultimate distribution has an obfuscated post-startup activity
    // (com.intellij.modules.ultimate) that cannot be instantiated in the light test
    // environment and fails every platform test. Same fix as deliberator's ide/ build.
    systemProperty("idea.suppressed.plugins.id", "com.intellij.modules.ultimate")

    // The test JVM writes temp projects to java.io.tmpdir, a java.util.prefs node for its
    // licence check, and JetBrains dirs under XDG_*: fence all of it into build/.
    val tmp = layout.buildDirectory.dir("test-tmp").get().asFile
    val prefs = layout.buildDirectory.dir("test-prefs").get().asFile
    doFirst { tmp.mkdirs(); prefs.mkdirs() }
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    systemProperty("java.util.prefs.userRoot", prefs.absolutePath)
    systemProperty("java.util.prefs.systemRoot", prefs.absolutePath)
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("test-xdg/data").get().asFile.absolutePath)
    environment("XDG_CONFIG_HOME", layout.buildDirectory.dir("test-xdg/config").get().asFile.absolutePath)
    environment("XDG_CACHE_HOME", layout.buildDirectory.dir("test-xdg/cache").get().asFile.absolutePath)
}

// `bb ijdev start` runs this: the sandbox IDE with the dev-only ijdev REPL plugin (nREPL inside
// the IDE, port in .ijdev/nrepl-port), the agent that keeps Instrumentation for `ijdev hotswap`,
// JBR's enhanced class redefinition, and no auto-reload (`ijdev reload` unloads and loads the
// plugin itself). Its own sandbox (suffix _runIdeDev), so plain runIde is untouched. The REPL
// plugin is built in the repls repo: `ijdevPlugin` in gradle.properties, same path as .ijdev.edn.
val ijdevBuild = file(providers.gradleProperty("ijdevPlugin").get()).resolve("build")
val runIdeDev by intellijPlatformTesting.runIde.registering {
    plugins { localPlugin(ijdevBuild.resolve("distributions/ijdev-repl-0.1.0.zip")) }
    task {
        autoReload = false
        val dev = layout.buildDirectory.dir("ide-dev").get().asFile
        doFirst { dev.resolve("tmp").mkdirs(); dev.resolve("prefs").mkdirs() }
        jvmArgs(
            "-javaagent:" + ijdevBuild.resolve("agent/ijdev-agent.jar").absolutePath,
            "-XX:+AllowEnhancedClassRedefinition",
            "-Dijdev.dir=" + layout.projectDirectory.dir(".ijdev").asFile.absolutePath,
            "-Dijdev.plugins=com.neex.inliner",
            "-Didea.is.internal=true",
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            // same fences as the tests: nothing of the sandbox IDE lands in $HOME or /tmp
            "-Djava.io.tmpdir=" + dev.resolve("tmp").absolutePath,
            "-Djava.util.prefs.userRoot=" + dev.resolve("prefs").absolutePath,
            "-Djava.util.prefs.systemRoot=" + dev.resolve("prefs").absolutePath,
        )
        environment("XDG_DATA_HOME", dev.resolve("xdg/data").absolutePath)
        environment("XDG_CONFIG_HOME", dev.resolve("xdg/config").absolutePath)
        environment("XDG_CACHE_HOME", dev.resolve("xdg/cache").absolutePath)
    }
}
