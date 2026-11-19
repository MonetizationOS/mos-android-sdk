import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

description = "MonetizationOS SDK for Android: MOS.create, on-device storage and the web paywall view."

android {
    namespace = "com.monetizationos.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric reads the merged manifest and resources from the build.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    explicitApi()
}

// The validator's own classpath, for the API tasks at the end.
val abiValidatorClasspath = configurations.create("abiValidatorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":mos-core"))
    // mos-android calls mos-core's internal API, so the two must be the same release: Gradle fails on any other mos-core.
    constraints {
        api("com.monetizationos:mos-core") { version { strictly(project.version.toString()) } }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")

    abiValidatorClasspath("org.ow2.asm:asm:9.9")
    abiValidatorClasspath("org.ow2.asm:asm-tree:9.9")
    abiValidatorClasspath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")
}

// The public API, recorded in api/: apiCheck fails on any change, apiDump records an intended one. The validator plugin
// does not detect AGP 9's built-in Kotlin, so its tasks are wired to the release classes here.
val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
    inputClassesDirs.from(tasks.named("compileReleaseKotlin"))
    outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
    runtimeClasspath.from(abiValidatorClasspath)
    nonPublicMarkers.add("com.monetizationos.sdk.InternalMosApi")
}

tasks.register<Sync>("apiDump") {
    from(apiBuild)
    into(layout.projectDirectory.dir("api"))
}

val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
    projectApiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}

tasks.named("check") { dependsOn(apiCheck) }
