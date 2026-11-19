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

rootProject.name = "Showcase"
include(":app")

// The SDK straight from the repo's build, never a copy. Its settings only include
// :mos-android when ANDROID_HOME (or ANDROID_SDK_ROOT) is set, so export it before building.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.monetizationos:mos-core")).using(project(":mos-core"))
        substitute(module("com.monetizationos:mos-android")).using(project(":mos-android"))
    }
}
