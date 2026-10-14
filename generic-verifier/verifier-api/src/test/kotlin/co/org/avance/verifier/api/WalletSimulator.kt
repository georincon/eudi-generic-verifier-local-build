package co.org.avance.verifier.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.util.Base64
import com.nimbusds.jose.util.Base64URL
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Date

private val mapper = ObjectMapper()

/** CA raíz (CA=true) y hoja (CA=false, con SAN) de PRUEBA. */
class TestChain(rootName: String, leafName: String, leafDns: String? = null) {
    val root: X509Certificate
    val leaf: X509Certificate
    val leafKey: PrivateKey

    init {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val rootKp = gen.generateKeyPair()
        val leafKp = gen.generateKeyPair()
        root = issue("CN=$rootName", rootKp.public, "CN=$rootName", rootKp.private, ca = true)
        leaf = issue("CN=$leafName", leafKp.public, "CN=$rootName", rootKp.private, ca = false, dns = leafDns)
        leafKey = leafKp.private
    }

    private fun issue(subject: String, key: PublicKey, issuer: String, issuerKey: PrivateKey, ca: Boolean, dns: String? = null): X509Certificate {
        val now = System.currentTimeMillis()
        val b = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name(subject), key)
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        b.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        if (dns != null) b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, dns)))
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)))
    }
}

/** Lista de confianza `tl+jwt` de PRUEBA, con el mismo formato que publica el tl-publisher. */
object TestTrustList {
    fun jwt(signer: TestChain, scheme: String, trustedRoots: List<X509Certificate>): String {
        val now = System.currentTimeMillis() / 1000
        val services = trustedRoots.joinToString(",") {
            """{"service_type":"http://uri.etsi.org/TrstSvc/Svctype/EAA/Pub-EAA","status":"http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted","status_start":"2026-01-01T00:00:00Z","certificates":[{"der_b64":"${java.util.Base64.getEncoder().encodeToString(it.encoded)}"}]}"""
        }
        val payload = """{"iss":"https://tl.test","sub":"$scheme","scheme":"$scheme","territory":"CO","iat":$now,"exp":${now + 3600},"services":[$services]}"""
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("tl+jwt"))
            .x509CertChain(listOf(Base64.encode(signer.leaf.encoded), Base64.encode(signer.root.encoded))).build()
        return JWSObject(header, Payload(payload)).apply { sign(ECDSASigner(signer.leafKey as ECPrivateKey)) }.serialize()
    }

    fun sha256Hex(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

/** Datos de la solicitud que recibe una wallet. */
data class WalletRequest(val clientId: String, val nonce: String, val state: String, val responseUri: String, val encryptionKey: ECKey)

/**
 * Simula a una wallet: arma un SD-JWT VC con divulgaciones y prueba de posesión (KB-JWT) y lo envía cifrado
 * (ECDH-ES) como `direct_post.jwt`.
 */
class WalletSimulator(private val issuer: TestChain, private val issuerSignsWith: PrivateKey = issuer.leafKey) {
    private val random = SecureRandom()
    private val holder: ECKey = ECKeyGenerator(Curve.P_256).generate()

    private fun b64u(bytes: ByteArray) = Base64URL.encode(bytes).toString()
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))

    /** Presentación completa: SD-JWT + KB-JWT. [sdClaims] van como divulgaciones; [clearClaims] en claro (como hace el emisor real con 21 de 27). */
    fun presentation(
        request: WalletRequest,
        sdClaims: Map<String, String>,
        clearClaims: Map<String, String> = emptyMap(),
        kbNonce: String = request.nonce,
        kbAudience: String = request.clientId,
    ): String {
        val disclosures = sdClaims.map { (name, value) ->
            val salt = b64u(ByteArray(16).also(random::nextBytes))
            b64u(mapper.writeValueAsBytes(listOf(salt, name, value)))
        }
        val now = System.currentTimeMillis() / 1000
        val payload = linkedMapOf<String, Any>(
            "iss" to "https://issuer.test",
            "iat" to now,
            "exp" to now + 3600,
            "vct" to "urn:eudi:academic:credential:1",
            "_sd_alg" to "sha-256",
            "_sd" to disclosures.map { b64u(sha256(it)) },
            "cnf" to mapOf("jwk" to holder.toPublicJWK().toJSONObject()),
        ).apply { putAll(clearClaims) }

        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("dc+sd-jwt"))
            .x509CertChain(listOf(Base64.encode(issuer.leaf.encoded))).build()
        val issuerJwt = JWSObject(header, Payload(mapper.writeValueAsString(payload)))
            .apply { sign(ECDSASigner(issuerSignsWith as ECPrivateKey)) }.serialize()

        val sdJwt = issuerJwt + "~" + disclosures.joinToString("~") + "~"
        val kbPayload = mapOf("iat" to now, "aud" to kbAudience, "nonce" to kbNonce, "sd_hash" to b64u(sha256(sdJwt)))
        val kbJwt = JWSObject(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("kb+jwt")).build(), Payload(mapper.writeValueAsString(kbPayload)))
            .apply { sign(ECDSASigner(holder)) }.serialize()
        return sdJwt + kbJwt
    }

    /** Respuesta `direct_post.jwt`: JWE (ECDH-ES, A128GCM) con `vp_token` y `state`. */
    fun encryptedResponse(request: WalletRequest, queryId: String, presentation: String, state: String = request.state): String {
        val claims = mapper.writeValueAsString(mapOf("vp_token" to mapOf(queryId to listOf(presentation)), "state" to state))
        val header = JWEHeader.Builder(JWEAlgorithm.ECDH_ES, EncryptionMethod.A128GCM).keyID(request.encryptionKey.keyID).build()
        return JWEObject(header, Payload(claims)).apply { encrypt(ECDHEncrypter(request.encryptionKey)) }.serialize()
    }

    companion object {
        /** Extrae del Request Object (JWT) lo que necesita la wallet. */
        fun parse(requestObjectJwt: String): WalletRequest {
            val p = mapper.readTree(java.util.Base64.getUrlDecoder().decode(requestObjectJwt.split('.')[1]))
            val jwks = JWKSet.parse(mapper.writeValueAsString(p.get("client_metadata").get("jwks")))
            return WalletRequest(
                clientId = p.get("client_id").asText(),
                nonce = p.get("nonce").asText(),
                state = p.get("state").asText(),
                responseUri = p.get("response_uri").asText(),
                encryptionKey = jwks.keys.first() as ECKey,
            )
        }
    }
}
