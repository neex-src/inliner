plugins {
    // fetches the JDK 25 toolchain of the tests when none is installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "inliner"
