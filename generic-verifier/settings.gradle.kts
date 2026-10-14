plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "avance-verifier"

include("verifier-protocol", "verifier-trust", "verifier-catalog", "verifier-persistence", "verifier-api")
