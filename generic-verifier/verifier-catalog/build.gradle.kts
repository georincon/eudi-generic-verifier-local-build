plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    `java-library`
}

dependencies {
    api(project(":verifier-protocol"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
