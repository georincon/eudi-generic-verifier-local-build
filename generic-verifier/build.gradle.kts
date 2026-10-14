import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.spring) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.waltid.dev/releases")
            mavenContent { releasesOnly() }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain {
                languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
                vendor = JvmVendorSpec.ADOPTIUM
                implementation = JvmImplementation.VENDOR_SPECIFIC
            }
            target {
                compilerOptions {
                    javaParameters = true
                    jvmDefault = JvmDefaultMode.ENABLE
                    jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
                    apiVersion = KotlinVersion.DEFAULT
                    languageVersion = KotlinVersion.DEFAULT
                    optIn.addAll(
                        "kotlin.contracts.ExperimentalContracts",
                        "kotlinx.serialization.ExperimentalSerializationApi",
                    )
                    freeCompilerArgs.addAll("-Xjsr305=strict")
                }
            }
        }
    }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
