import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Live-mode defaults for Settings come from local.properties (gitignored), never the build file.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(key: String, default: String): String = "\"" + (local.getProperty(key) ?: default) + "\""

// Fixture mode answers from Examples/Showcase/fixtures; they are packaged as is.
val fixtures = file("../fixtures")

android {
    namespace = "com.monetizationos.showcase"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.monetizationos.showcase"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "MOS_PUBLIC_KEY", setting("mos.publicKey", ""))
        buildConfigField("String", "MOS_API_BASE_URL", setting("mos.apiBaseUrl", "https://api.monetizationos.com"))
        buildConfigField("String", "MOS_ENDPOINT_NAMESPACE", setting("mos.endpointNamespace", "showcase"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").assets.directories.add(fixtures.path)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.systemProperty("showcase.fixtures", fixtures.absolutePath) }
    }
}

dependencies {
    implementation("com.monetizationos:mos-android")
    implementation("androidx.activity:activity-compose:1.14.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(kotlin("test-junit"))

    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.13.0-alpha03")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-web:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.13.0-alpha03")
}
