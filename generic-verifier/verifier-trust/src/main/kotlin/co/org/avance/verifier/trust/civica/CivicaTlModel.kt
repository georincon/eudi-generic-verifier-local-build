package co.org.avance.verifier.trust.civica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contenido de una lista de confianza del tl-publisher (JWT `tl+jwt`). Es un resumen JSON de la
 * lista ETSI TS 119 612 (XML) con los certificados de cada servicio.
 */
@Serializable
internal data class CivicaTlPayload(
    val iss: String,
    val scheme: String,
    val iat: Long,
    val exp: Long,
    val territory: String? = null,
    @SerialName("sequence_number") val sequenceNumber: Long? = null,
    val services: List<CivicaTlService> = emptyList(),
)

@Serializable
internal data class CivicaTlService(
    @SerialName("service_type") val serviceType: String,
    val status: String,
    @SerialName("status_start") val statusStart: String,
    val certificates: List<CivicaTlCertificate> = emptyList(),
)

@Serializable
internal data class CivicaTlCertificate(
    @SerialName("der_b64") val derB64: String,
)
