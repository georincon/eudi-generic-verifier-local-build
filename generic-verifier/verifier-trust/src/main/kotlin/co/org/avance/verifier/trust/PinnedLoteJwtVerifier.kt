package co.org.avance.verifier.trust

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.eudi.etsi119602.consultation.VerifyJwtSignature
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Verifica la firma de una lista de confianza (JWT) **y comprueba quién la firmó**.
 *
 * La firma se valida con el certificado de cabecera `x5c`. Eso solo prueba que el JWT no se
 * alteró después de firmarse, no que el firmante sea legítimo: cualquiera puede fabricar una
 * lista y firmarla con su propio certificado. Por eso, con [pinnedSignerSha256], el
 * certificado firmante debe ser uno de los esperados (huella SHA-256 del DER).
 *
 * [unpinnedForDevelopment] reproduce el comportamiento del wallet de referencia (sin fijar
 * firmante) y existe solo para entornos de desarrollo.
 */
class PinnedLoteJwtVerifier private constructor(
    private val pinnedSignerSha256: Set<String>?,
) : VerifyJwtSignature {

    override suspend fun invoke(jwt: String): VerifyJwtSignature.Outcome =
        try {
            val signed = SignedJWT.parse(jwt)
            val header = signed.header
            val chain = header.x509CertChain
            if (chain.isNullOrEmpty()) {
                notVerified("la cabecera del JWT no trae x5c")
            } else {
                val signer = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(chain.first().decode())) as X509Certificate
                signer.checkValidity()

                val fingerprint = sha256Hex(signer.encoded)
                val verifier = verifierFor(signer, header.criticalParams ?: emptySet())
                when {
                    pinnedSignerSha256 != null && fingerprint !in pinnedSignerSha256 ->
                        notVerified("firmante no fijado: ${signer.subjectX500Principal} sha256=$fingerprint")

                    verifier == null ->
                        notVerified("tipo de clave no soportado: ${signer.publicKey.algorithm}")

                    !signed.verify(verifier) ->
                        notVerified("la firma no corresponde al contenido")

                    else -> VerifyJwtSignature.Outcome.Verified(jwt)
                }
            }
        } catch (e: Exception) {
            VerifyJwtSignature.Outcome.NotVerified(e)
        }

    private fun verifierFor(signer: X509Certificate, critical: Set<String>): JWSVerifier? =
        when (val key = signer.publicKey) {
            is ECPublicKey -> ECDSAVerifier(key, critical)
            is RSAPublicKey -> RSASSAVerifier(key, critical)
            else -> null
        }

    private fun notVerified(reason: String) =
        VerifyJwtSignature.Outcome.NotVerified(SecurityException(reason))

    companion object {
        /** Solo acepta listas firmadas por alguno de estos certificados (SHA-256 del DER, en hexadecimal). */
        fun pinned(signerSha256: Set<String>): PinnedLoteJwtVerifier {
            require(signerSha256.isNotEmpty()) { "debe haber al menos un firmante fijado" }
            return PinnedLoteJwtVerifier(signerSha256.map { it.replace(":", "").lowercase() }.toSet())
        }

        /** SOLO DESARROLLO: acepta cualquier firmante, como el wallet de referencia. */
        fun unpinnedForDevelopment(): PinnedLoteJwtVerifier = PinnedLoteJwtVerifier(null)

        fun sha256Hex(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }
}
