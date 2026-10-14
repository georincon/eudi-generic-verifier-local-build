plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(project(":verifier-protocol"))
    api(project(":verifier-catalog"))
}
