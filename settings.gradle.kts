import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mos-android-sdk"
include(":mos-core")

fun sdkDir(properties: File): String? = properties.takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir") }
    ?.takeIf { it.isNotBlank() }

// Android Studio opened on Examples/Showcase writes the SDK path there only. This build reads its own
// local.properties, so copy the path over once; both files are gitignored.
val showcaseSdkDir = sdkDir(file("Examples/Showcase/local.properties"))
val rootLocal = file("local.properties")
if (sdkDir(rootLocal) == null && showcaseSdkDir != null) {
    val separator = if (rootLocal.exists() && !rootLocal.readText().let { it.isEmpty() || it.endsWith("\n") }) "\n" else ""
    rootLocal.appendText("${separator}sdk.dir=${showcaseSdkDir.replace("\\", "\\\\")}\n")
}

// mos-android needs an Android SDK; without one, :mos-core:test must still work locally. With CI set it fails
// instead, so a release can never publish mos-core alone.
val localSdkDir = sdkDir(rootLocal)
val hasAndroidSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !System.getenv(it).isNullOrBlank() } ||
    !localSdkDir.isNullOrBlank()
if (hasAndroidSdk) {
    include(":mos-android")
} else if (!System.getenv("CI").isNullOrBlank()) {
    throw GradleException(
        "No Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties) and CI is set: " +
            ":mos-android would be left out of the build.",
    )
}
