package co.org.avance.verifier.trust.civica

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant

/** Resultado de comprobar el JWT de una lista: su contenido, o el motivo del rechazo. */
internal sealed interface TlJwtOutcome {
    data class Verified(val payloadJson: String) : TlJwtOutcome
    data class Rejected(val reason: String) : TlJwtOutcome
}

/**
 * Comprueba la firma de una lista `tl+jwt` y **quién la firmó**:
 * - cabecera `typ = tl+jwt` y algoritmo ECDSA;
 * - cadena `x5c` íntegra (cada certificado firmado por el siguiente) y vigente en [now];
 * - al menos un certificado de la cadena con huella SHA-256 igual a una de [pinnedSha256];
 * - firma del JWT válida con la clave del primer certificado.
 *
 * [pinnedSha256] no puede estar vacío: aceptar cualquier firmante permitiría a cualquiera
 * fabricar su propia lista de confianza.
 */
internal class CivicaTlJwtVerifier(pinnedSha256: Set<String>) {

    private val pins: Set<String> = pinnedSha256.map { it.replace(":", "").lowercase() }.toSet()

    init {
        require(pins.isNotEmpty()) { "Debe fijarse al menos un firmante de la lista (huella SHA-256)" }
    }

    fun verify(jwt: String, now: Instant): TlJwtOutcome =
        try {
            val signed = SignedJWT.parse(jwt)
            val header = signed.header
            when {
                header.type?.type != "tl+jwt" -> reject("typ inesperado: ${header.type?.type}")
                header.algorithm !in EC_ALGORITHMS -> reject("algoritmo no permitido: ${header.algorithm}")
                header.x509CertChain.isNullOrEmpty() -> reject("la cabecera no trae x5c")
                else -> verifyChainAndSignature(signed, now)
            }
        } catch (e: Exception) {
            reject("JWT ilegible: ${e.message}")
        }

    private fun verifyChainAndSignature(signed: SignedJWT, now: Instant): TlJwtOutcome {
        val factory = CertificateFactory.getInstance("X.509")
        val chain = signed.header.x509CertChain.map {
            factory.generateCertificate(ByteArrayInputStream(it.decode())) as X509Certificate
        }
        chain.forEach { it.checkValidity(java.util.Date.from(now)) }
        chain.zipWithNext().forEach { (cert, issuer) -> cert.verify(issuer.publicKey) }

        if (chain.none { sha256Hex(it.encoded) in pins }) {
            return reject("firmante no fijado: ${chain.first().subjectX500Principal}")
        }
        val key = chain.first().publicKey as? ECPublicKey ?: return reject("la clave del firmante no es EC")
        if (!signed.verify(ECDSAVerifier(key))) return reject("la firma no corresponde al contenido")
        return TlJwtOutcome.Verified(signed.payload.toString())
    }

    private fun reject(reason: String) = TlJwtOutcome.Rejected(reason)

    companion object {
        private val EC_ALGORITHMS = setOf(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512)

        fun sha256Hex(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }
}
