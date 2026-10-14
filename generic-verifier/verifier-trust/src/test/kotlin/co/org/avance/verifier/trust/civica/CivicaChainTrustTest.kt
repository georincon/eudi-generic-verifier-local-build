package co.org.avance.verifier.trust.civica

import arrow.core.nonEmptyListOf
import com.sun.net.httpserver.HttpServer
import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pruebas con las listas REALES del tl-publisher (PID y PubEAA, copiadas como fixtures el 14-oct-2026).
 * Como esas listas caducan en 24 h, el reloj es controlable: se fija justo después de su emisión.
 */
class CivicaChainTrustTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var server: HttpServer
    private val httpClient = HttpClient(Apache)
    private val clock = MutableClock(Instant.ofEpochSecond(ISSUED + 60))

    // lo que sirve cada ruta; permite simular fallos y alteraciones
    private var pubEaaResponse: () -> Pair<Int, String> = { 200 to fixture("civica/PUBEAA.jwt") }
    private var pidResponse: () -> Pair<Int, String> = { 200 to fixture("civica/PID.jwt") }
    private var pubEaaHits = 0

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/PUBEAA") { ex -> pubEaaHits++; reply(ex, pubEaaResponse()) }
            createContext("/PID") { ex -> reply(ex, pidResponse()) }
            start()
        }
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        httpClient.close()
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, response: Pair<Int, String>) {
        val body = response.second.toByteArray()
        ex.sendResponseHeaders(response.first, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun trust(
        pins: Set<String> = setOf(SIGNER_CA_SHA256),
        lists: CivicaTlLists = CivicaTlLists(pid = url("PID"), pubEaa = url("PUBEAA")),
    ) = CivicaChainTrust(lists, pins, httpClient, clock, cacheTtl = Duration.ofMinutes(15))

    private fun url(path: String) = "http://localhost:${server.address.port}/$path"

    // ---- el caso real: la raiz de la UIS ------------------------------------------------------

    @Test
    fun `la raiz de la UIS es de confianza para credenciales PubEAA`() = runBlocking<Unit> {
        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertIs<CertificationChainValidation.Trusted<*>>(result)
    }

    @Test
    fun `la raiz de la UIS no es de confianza para PID, que tiene su propia lista`() = runBlocking<Unit> {
        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PID)

        assertIs<CertificationChainValidation.NotTrusted>(result)
    }

    @Test
    fun `una CA ajena no es de confianza`() = runBlocking<Unit> {
        val result = trust()(nonEmptyListOf(foreignCertificate()), VerificationContext.PubEAA)

        assertIs<CertificationChainValidation.NotTrusted>(result)
    }

    @Test
    fun `un contexto sin lista configurada no da confianza`() = runBlocking<Unit> {
        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.WalletRelyingPartyAccessCertificate)

        assertNotTrusted(result, "no hay lista de confianza configurada")
    }

    // ---- seguridad de la lista ----------------------------------------------------------------

    @Test
    fun `una lista firmada por un firmante no fijado se rechaza`() = runBlocking<Unit> {
        val result = trust(pins = setOf("00".repeat(32)))(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertNotTrusted(result, "firmante no fijado")
    }

    @Test
    fun `una lista alterada se rechaza`() = runBlocking<Unit> {
        val tampered = fixture("civica/PUBEAA.jwt").let { jwt ->
            val (h, p, s) = jwt.split('.')
            "$h.${p.dropLast(6)}AAAAAA.$s"
        }
        pubEaaResponse = { 200 to tampered }

        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertIs<CertificationChainValidation.NotTrusted>(result)
    }

    @Test
    fun `una lista caducada se rechaza`() = runBlocking<Unit> {
        clock.now = Instant.ofEpochSecond(PUBEAA_EXPIRES + 1)

        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertNotTrusted(result, "caducad")
    }

    @Test
    fun `se rechaza una lista distinta de la esperada para el contexto`() = runBlocking<Unit> {
        pubEaaResponse = { 200 to fixture("civica/PID.jwt") } // sirven la lista PID en la ruta de PubEAA

        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertNotTrusted(result, "se esperaba la lista PUBEAA")
    }

    @Test
    fun `no hay confianza si la lista es inaccesible y no hay copia`() = runBlocking<Unit> {
        pubEaaResponse = { 503 to "" }

        val result = trust()(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertNotTrusted(result, "no disponible")
    }

    // ---- cache ---------------------------------------------------------------------------------

    @Test
    fun `dentro del TTL no se vuelve a descargar la lista`() = runBlocking<Unit> {
        val t = trust()
        t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)
        t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA)

        assertEquals(1, pubEaaHits)
    }

    @Test
    fun `si falla la actualizacion se usa la copia mientras no haya caducado`() = runBlocking<Unit> {
        val t = trust()
        assertIs<CertificationChainValidation.Trusted<*>>(t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA))

        clock.now = clock.now.plus(Duration.ofMinutes(30)) // pasa el TTL, la lista sigue vigente
        pubEaaResponse = { 503 to "" }

        assertIs<CertificationChainValidation.Trusted<*>>(t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA))
    }

    @Test
    fun `si falla la actualizacion y la copia caduco no hay confianza`() = runBlocking<Unit> {
        val t = trust()
        assertIs<CertificationChainValidation.Trusted<*>>(t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA))

        clock.now = Instant.ofEpochSecond(PUBEAA_EXPIRES + 1)
        pubEaaResponse = { 503 to "" }

        assertIs<CertificationChainValidation.NotTrusted>(t(nonEmptyListOf(uisRoot()), VerificationContext.PubEAA))
    }

    @Test
    fun `el verificador exige al menos un firmante fijado`() {
        val ex = runCatching { CivicaTlJwtVerifier(emptySet()) }.exceptionOrNull()

        assertTrue(ex is IllegalArgumentException)
    }

    // ---- ayudas --------------------------------------------------------------------------------

    private fun assertNotTrusted(result: CertificationChainValidation<*>?, fragment: String) {
        assertIs<CertificationChainValidation.NotTrusted>(result)
        val causes = generateSequence(result.cause) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        assertTrue(fragment in causes, "se esperaba '$fragment' en la causa; causa real: $causes")
    }

    /** La raíz "UIS Issuer Root CA", tal como figura en la lista PubEAA real. */
    private fun uisRoot(): X509Certificate {
        val payload = fixture("civica/PUBEAA.jwt").split('.')[1]
        val der = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject
            .getValue("services").jsonArray.first().jsonObject
            .getValue("certificates").jsonArray.first().jsonObject
            .getValue("der_b64").jsonPrimitive.content
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(der))) as X509Certificate
    }

    private fun foreignCertificate(): X509Certificate {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = X500Name("CN=ca-ajena")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(now - 1000), Date(now + 86_400_000), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun fixture(path: String): String =
        CivicaChainTrustTest::class.java.classLoader.getResourceAsStream(path)!!.use { String(it.readBytes()).trim() }

    private companion object {
        const val ISSUED = 1_791_989_610L // emisión de la lista PID (la más reciente de los fixtures)
        const val PUBEAA_EXPIRES = 1_792_074_371L
        const val SIGNER_CA_SHA256 = "5434f122157c56a9ba09caef1877f62d4f65065e1c560c46533e185338814beb"
    }
}
