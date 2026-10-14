plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    `java-library`
}

dependencies {
    api(platform(libs.kotlin.bom))
    api(platform(libs.kotlinx.coroutines.bom))
    api(platform(libs.ktor.bom))
    api(platform(libs.kotlinx.serialization.bom))
    api(platform(libs.arrow.stack))
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springboot.get()}"))
    api("com.fasterxml.jackson.module:jackson-module-kotlin")

    api("org.jetbrains.kotlin:kotlin-reflect")
    api(libs.nimbusds.oauth2.oidc.sdk)
    api(libs.bouncy.castle)
    api(libs.arrow.core)
    api(libs.arrow.fx.coroutines)
    api(libs.arrow.core.serialization)
    api(libs.waltid.mdoc.credentials)
    api(libs.kotlinx.datetime)
    api(libs.cose.java)
    api(libs.sd.jwt)
    api(libs.ktor.client.apache)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.client.serialization)
    api(libs.tink)
    api(libs.statium)
    api(libs.zxing)
    api(libs.uri)
    api(libs.aedile)
    api(libs.consultation)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
