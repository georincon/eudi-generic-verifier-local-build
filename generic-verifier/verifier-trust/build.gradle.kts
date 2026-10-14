plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    `java-library`
}

dependencies {
    api(project(":verifier-protocol"))
    api(libs.etsi119602.consultation)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
