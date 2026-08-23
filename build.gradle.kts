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
        local("~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate")
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
            local(file("~/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate"))
        }
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
}
