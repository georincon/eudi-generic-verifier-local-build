package co.org.avance.verifier.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(AppBeans::class)
@EnableConfigurationProperties(VerifierEndpointConfigurationProperties::class)
class GenericVerifierApplication

fun main(args: Array<String>) {
    runApplication<GenericVerifierApplication>(*args)
}
