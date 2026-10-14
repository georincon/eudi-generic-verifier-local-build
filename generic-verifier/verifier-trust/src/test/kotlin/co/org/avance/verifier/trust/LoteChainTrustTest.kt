package co.org.avance.verifier.trust

import arrow.core.nonEmptyListOf
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.Base64
import com.sun.net.httpserver.HttpServer
import eu.europa.ec.eudi.etsi119602.datamodel.Uri
import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.DisposableContainer
import eu.europa.ec.eudi.etsi1196x2.consultation.SupportedLists
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.util.Date
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.security.cert.CertificateFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Prueba de extremo a extremo sin red: una "libreta propia" (lista WRPAC) firmada por nosotros
 * que incluye la raíz de prueba del upstream, servida por HTTP local.
 *
 * La lista parte del contenido real de WRPACProviders de la Comisión (fixture) más un servicio
 * nuestro. Es solo para pruebas.
 */
class LoteChainTrustTest {

    private class Pki(val chainSentByVerifier: List<X509Certificate>, val root: X509Certificate, val rootKey: PrivateKey)

    private lateinit var server: HttpServer
    private lateinit var pki: Pki
    private val lifecycle = DisposableContainer()
    private val httpClient = HttpClient(Apache)

    @BeforeTest
    fun setUp() {
        pki = loadPki()
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        lifecycle.dispose()
        httpClient.close()
    }

    @Test
    fun `una cadena que llega a una CA de la libreta es de confianza`() = runTest {
        val trust = trustFromList(signedList(pki.root, pki.rootKey), PinnedLoteJwtVerifier.unpinnedForDevelopment())

        val result = trust(nonEmptyListOf(pki.chainSentByVerifier[0], pki.chainSentByVerifier[1]), WRPAC)

        assertTrusted(result)
    }

    @Test
    fun `la cadena de ejemplo del upstream no pasa un validador estricto aunque su raiz este en la libreta`() = runTest {
        val upstream = loadUpstreamPki()
        val trust = trustFromList(signedList(upstream.root, upstream.rootKey, upstream.root), PinnedLoteJwtVerifier.unpinnedForDevelopment())

        val result = trust(nonEmptyListOf(upstream.chainSentByVerifier[0], upstream.chainSentByVerifier[1]), WRPAC)

        assertIs<CertificationChainValidation.NotTrusted>(result)
        val causes = generateSequence(result.cause) { it.cause }.joinToString { it.message.orEmpty() }
        assertTrue("not a CA certificate" in causes, "causa esperada: el intermediate no es CA. Causa real: $causes")
    }

    @Test
    fun `una cadena de una CA ajena no es de confianza`() = runTest {
        val trust = trustFromList(signedList(pki.root, pki.rootKey), PinnedLoteJwtVerifier.unpinnedForDevelopment())

        val result = trust(nonEmptyListOf(selfSignedForeignCertificate()), WRPAC)

        assertTrue(result !is CertificationChainValidation.Trusted<*>, "una CA que no está en la libreta no debe ser de confianza")
    }

    @Test
    fun `una lista firmada por un firmante no fijado se rechaza aunque la firma sea valida`() = runTest {
        val otherSigner = PinnedLoteJwtVerifier.pinned(setOf("00".repeat(32)))
        val trust = trustFromList(signedList(pki.root, pki.rootKey), otherSigner)

        val outcome = runCatching { trust(nonEmptyListOf(pki.chainSentByVerifier[0], pki.chainSentByVerifier[1]), WRPAC) }

        assertTrue(outcome.getOrNull() !is CertificationChainValidation.Trusted<*>, "con el firmante equivocado la libreta no debe valer")
    }

    @Test
    fun `una lista firmada por el firmante fijado se acepta`() = runTest {
        val pin = PinnedLoteJwtVerifier.pinned(setOf(PinnedLoteJwtVerifier.sha256Hex(pki.root.encoded)))
        val trust = trustFromList(signedList(pki.root, pki.rootKey), pin)

        val result = trust(nonEmptyListOf(pki.chainSentByVerifier[0], pki.chainSentByVerifier[1]), WRPAC)

        assertTrusted(result)
    }

    @Test
    fun `un contexto que ninguna lista cubre no da confianza`() = runTest {
        val trust = trustFromList(signedList(pki.root, pki.rootKey), PinnedLoteJwtVerifier.unpinnedForDevelopment())

        val outcome = runCatching { trust(nonEmptyListOf(pki.chainSentByVerifier[0], pki.chainSentByVerifier[1]), VerificationContext.PID) }

        assertTrue(outcome.getOrNull() !is CertificationChainValidation.Trusted<*>, "PID no está cubierto por la lista WRPAC")
    }

    @Test
    fun `el verificador rechaza un JWT alterado`() = runTest {
        val jwt = signedList(pki.root, pki.rootKey)
        val (h, p, s) = jwt.split('.')
        val tampered = "$h.${p.dropLast(4)}AAAA.$s"

        val outcome = PinnedLoteJwtVerifier.unpinnedForDevelopment().invoke(tampered)

        assertTrue(outcome !is eu.europa.ec.eudi.etsi119602.consultation.VerifyJwtSignature.Outcome.Verified)
    }

    @Test
    fun `la huella se calcula como SHA-256 en hexadecimal`() {
        val fp = PinnedLoteJwtVerifier.sha256Hex(byteArrayOf(1, 2, 3))
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", fp)
    }

    private fun assertTrusted(result: CertificationChainValidation<*>?) {
        if (result == null) throw AssertionError("null: ninguna fuente de confianza aplica a este contexto")
        if (result is CertificationChainValidation.NotTrusted) {
            var c: Throwable? = result.cause
            val causes = generateSequence(c) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
            throw AssertionError("NotTrusted. Causa: $causes")
        }
        assertIs<CertificationChainValidation.Trusted<*>>(result)
    }

    /**
     * Opcional (necesita red): contra la libreta REAL de la Comisión, fijando su firmante.
     * Ejecutar con LIVE_LOTE=1. La cadena es la propia CA 'WRPAC Issuer CA 01' (EU), que figura en la lista.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "LIVE_LOTE", matches = "1")
    fun `contra la libreta real, una CA WRPAC de la lista es de confianza y una ajena no`() = runTest {
        val url = "https://trustedlist.serviceproviders.eudiw.dev/LOTE/json/WRPACProviders.jwt"
        val realSigner = PinnedLoteJwtVerifier.pinned(setOf("d88326a052d41f2aed1bad1c95d8c8c15aefb04a27a47970f4d84eaa57d8512a"))
        val trust = LoteChainTrust.create(
            locations = SupportedLists(wrpacProviders = Uri(url)),
            jwtVerifier = realSigner,
            httpClient = httpClient,
            cacheDirectory = Files.createTempDirectory("lote-live"),
            lifecycle = lifecycle,
            relaxCertificateProfiles = true,
            checkRevocation = false,
        )
        val euCa = LoteChainTrustTest::class.java.classLoader.getResourceAsStream("lote/eu-wrpac-issuer-ca-01.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

        assertTrusted(trust(nonEmptyListOf(euCa), WRPAC))
        assertTrue(trust(nonEmptyListOf(selfSignedForeignCertificate()), WRPAC) !is CertificationChainValidation.Trusted<*>)
    }

    // ---- ayudas ----------------------------------------------------------------------------

    private fun trustFromList(jwt: String, verifier: PinnedLoteJwtVerifier): LoteChainTrust.ChainTrust {
        server.createContext("/WRPACProviders.jwt") { ex ->
            val body = jwt.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/jwt")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val url = "http://localhost:${server.address.port}/WRPACProviders.jwt"
        return LoteChainTrust.create(
            locations = SupportedLists(wrpacProviders = Uri(url)),
            jwtVerifier = verifier,
            httpClient = httpClient,
            cacheDirectory = Files.createTempDirectory("lote-cache"),
            lifecycle = lifecycle,
            relaxCertificateProfiles = true,
            checkRevocation = false,
        )
    }

    /** Contenido real de la lista WRPAC + un servicio nuestro con la raíz de prueba, firmado por [signer]. */
    private fun signedList(signer: X509Certificate, key: PrivateKey, trustedRoot: X509Certificate = pki.root): String {
        val base = Json.parseToJsonElement(resource("lote/WRPACProviders.payload.json")).jsonObject
        val lote = base.getValue("LoTE").jsonObject
        val entity = lote.getValue("TrustedEntitiesList").jsonArray.first().jsonObject
        val services = entity.getValue("TrustedEntityServices").jsonArray
        val ours: JsonElement = JsonObject(
            mapOf(
                "ServiceInformation" to JsonObject(
                    mapOf(
                        "ServiceName" to JsonArray(listOf(JsonObject(mapOf("lang" to JsonPrimitive("en"), "value" to JsonPrimitive("TEST WRPAC Provider"))))),
                        "ServiceDigitalIdentity" to JsonObject(
                            mapOf("X509Certificates" to JsonArray(listOf(JsonObject(mapOf("val" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(trustedRoot.encoded))))))),
                        ),
                        "ServiceTypeIdentifier" to JsonPrimitive("http://uri.etsi.org/19602/SvcType/WRPAC/Issuance"),
                    ),
                ),
            ),
        )
        val newEntity = JsonObject(entity + ("TrustedEntityServices" to JsonArray(services + ours)))
        val newLote = JsonObject(lote + ("TrustedEntitiesList" to JsonArray(listOf(newEntity))))
        val payload = Json.encodeToString(JsonObject.serializer(), JsonObject(base + ("LoTE" to newLote)))

        val alg = if ((key as ECPrivateKey).params.order.bitLength() > 384) JWSAlgorithm.ES512 else JWSAlgorithm.ES256
        val header = JWSHeader.Builder(alg).x509CertChain(listOf(Base64.encode(signer.encoded))).build()
        return JWSObject(header, Payload(payload)).apply { sign(ECDSASigner(key as ECPrivateKey)) }.serialize()
    }

    /** PKI de prueba bien formada: raíz CA -> intermedia CA -> hoja (con SAN). El Request Object lleva [hoja, intermedia]. */
    private fun loadPki(): Pki {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val rootKp = gen.generateKeyPair()
        val interKp = gen.generateKeyPair()
        val leafKp = gen.generateKeyPair()
        val root = issue("CN=test-root", rootKp.public, "CN=test-root", rootKp.private, ca = true)
        val inter = issue("CN=test-intermediate", interKp.public, "CN=test-root", rootKp.private, ca = true)
        val leaf = issue("CN=test-verifier", leafKp.public, "CN=test-intermediate", interKp.private, ca = false, san = listOf("localhost"))
        return Pki(listOf(leaf, inter), root, rootKp.private)
    }

    /** La cadena del keystore de ejemplo del upstream: su "intermediate" NO es CA. */
    private fun loadUpstreamPki(): Pki {
        val ks = KeyStore.getInstance("JKS").apply {
            LoteChainTrustTest::class.java.classLoader.getResourceAsStream("lote/upstream-test-keystore.jks")!!.use { load(it, "keystore".toCharArray()) }
        }
        val access = ks.getCertificateChain("access_certificate").map { it as X509Certificate }
        val rootKey = listOf("root", "keystore").firstNotNullOf { pwd ->
            runCatching { ks.getKey("root", pwd.toCharArray()) as PrivateKey }.getOrNull()
        }
        return Pki(access.dropLast(1), access.last(), rootKey)
    }

    private fun issue(
        subject: String,
        subjectKey: PublicKey,
        issuer: String,
        issuerKey: PrivateKey,
        ca: Boolean,
        san: List<String> = emptyList(),
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 365L * 86_400_000), X500Name(subject), subjectKey,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature),
        )
        if (san.isNotEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(san.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()))
        }
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)))
    }

    private fun selfSignedForeignCertificate(): X509Certificate {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = X500Name("CN=ca-ajena")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 1000), Date(now + 86_400_000), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun resource(path: String): String =
        LoteChainTrustTest::class.java.classLoader.getResourceAsStream(path)!!.use { String(it.readBytes()) }

    private companion object {
        val WRPAC = VerificationContext.WalletRelyingPartyAccessCertificate
    }
}
