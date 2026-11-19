import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

description = "MonetizationOS SDK core: decisions, identity, offline cache, metering and endpoints, on any JVM."

// Java 17 bytecode from whatever JDK 17+ runs Gradle; no toolchain download.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compile against the JDK 17 API even on a newer JDK, so a newer call fails here, not on a device.
        freeCompilerArgs.add("-Xjdk-release=17")
        optIn.add("com.monetizationos.sdk.InternalMosApi")
    }
    explicitApi()
    // The public API, recorded in api/. checkKotlinAbi fails on any change; updateKotlinAbi records an intended one.
    // The annotation filter misses the classes nested in an internal one, so those are named.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("com.monetizationos.sdk.InternalMosApi")
                byNames.addAll("com.monetizationos.sdk.DeviceInfo.**", "com.monetizationos.sdk.webpaywall.**")
            }
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mos.fixtures", rootDir.resolve("fixtures").absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
