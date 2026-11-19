import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("com.android.library") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2" apply false
}

// Both libraries publish as com.monetizationos:<module>:VERSION_NAME. The release workflow runs publishToMavenCentral,
// which leaves the deployment for a manual Publish in the Central Portal; credentials and the signing key come from the
// mavenCentralUsername, mavenCentralPassword and signingInMemoryKey* Gradle properties. Local builds need none of them.
subprojects {
    group = "com.monetizationos"
    version = providers.gradleProperty("VERSION_NAME").get()

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            // The plugin requires a signature for every release version; here only a Central upload does, so
            // publishToMavenLocal works without a key (and still signs when one is set).
            val uploading = gradle.startParameter.taskNames.any { it.contains("MavenCentral") }
            extensions.configure<SigningExtension> { setRequired(uploading) }
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/MonetizationOS/mos-android-sdk")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("monetizationos")
                        name.set("MonetizationOS")
                        organization.set("MonetizationOS")
                        organizationUrl.set("https://monetizationos.com")
                    }
                }
                scm {
                    url.set("https://github.com/MonetizationOS/mos-android-sdk")
                    connection.set("scm:git:https://github.com/MonetizationOS/mos-android-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/MonetizationOS/mos-android-sdk.git")
                }
            }
        }
    }
}
