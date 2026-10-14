package co.org.avance.verifier.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.net.URI
import java.net.URLDecoder
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Flujo completo con una billetera SIMULADA: solicitud -> presentación cifrada -> validación -> resultado.
 *
 * - El emisor de prueba figura en una "libreta" propia (lista `tl+jwt` firmada por un firmante fijado),
 *   servida por HTTP local. Otro emisor, con la misma forma pero fuera de la libreta, debe rechazarse.
 * - Cubre lo que las otras pruebas no pueden: validación de firma del emisor, cadena de confianza, prueba de
 *   posesión (nonce, audiencia, sd_hash) y descarte de lo que no se pidió.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AcademicWalletFlowTest(@Autowired private val client: WebTestClient) {

    private val json = ObjectMapper()

    companion object {
        private val pki = TestPki(Files.createTempDirectory("avance-verifier-flow-pki"))
        private val trustedIssuer = TestChain("test-issuer-root", "test-issuer-ds", leafDns = "issuer.test")
        private val untrustedIssuer = TestChain("rogue-root", "rogue-ds", leafDns = "issuer.test")
        private val listSigner = TestChain("test-tl-signing-ca", "test-tl-signer")

        private val server: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            val list = TestTrustList.jwt(listSigner, "PUBEAA", listOf(trustedIssuer.root))
            createContext("/PUBEAA") { ex ->
                val body = list.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("verifier.access-certificate.keystore") { "file:${pki.keystore}" }
            registry.add("verifier.access-certificate.keystore.password") { pki.password }
            registry.add("verifier.access-certificate.password") { pki.password }
            registry.add("verifier.intendedUses[0].id") { "academic" }
            registry.add("verifier.intendedUses[0].description") { "Verificar un titulo academico" }
            registry.add("verifier.intendedUses[0].registration-certificate-file") { pki.wrprc.toString() }
            registry.add("verifier.publicUrl") { "http://localhost:8480/verifier" }
            registry.add("verifier.trust.lists.pub-eaa") { "http://localhost:${server.address.port}/PUBEAA" }
            registry.add("verifier.trust.signer-pins-sha256") { TestTrustList.sha256Hex(listSigner.root) }
        }
    }

    private val graduate = mapOf(
        "issuing_institution_name" to "Universidad de Prueba",
        "awarded_title" to "Ingeniero de Sistemas",
        "program_name" to "Ingeniería de Sistemas",
        "academic_status" to "GRADUADO",
        "graduation_date" to "2024-12-13",
        "family_name" to "Pérez",
        "given_name" to "Ana",
    )

    /** Atributos que el emisor real deja en claro aunque no se pidan. */
    private val clearExtras = mapOf("document_number" to "1098765432", "student_id" to "2150001", "accumulated_average" to "4.7")

    // ---- camino feliz ------------------------------------------------------------------------

    @Test
    fun `un titulo de un emisor de la libreta se acredita y solo se muestra lo pedido`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate, clearExtras))).expectStatus().isOk

        assertEquals("RECEIVED", status(id))
        val result = result(id)
        assertEquals("TITLE_ACCREDITED", result.get("verdict").asText())
        assertTrue("Ingeniero de Sistemas" in result.get("headline").asText())
        val shown = result.get("attributes").map { it.get("label").asText() + "=" + it.get("value").asText() }
        assertEquals(7, shown.size)
        val everything = result.toString()
        listOf("1098765432", "2150001", "4.7").forEach { assertFalse(it in everything, "no debe mostrarse un dato que no se pidió: $it") }
    }

    @Test
    fun `un estudiante activo tiene credencial valida pero no acredita titulo`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate + ("academic_status" to "ACTIVO")))).expectStatus().isOk

        assertEquals("VALID_BUT_NOT_GRADUATED", result(id).get("verdict").asText())
    }

    // ---- lo que debe rechazarse ----------------------------------------------------------------

    @Test
    fun `un emisor que no esta en la libreta se rechaza aunque la firma sea valida`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(untrustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate))).expectStatus().isBadRequest

        assertNotEquals("RECEIVED", status(id))
    }

    @Test
    fun `una credencial firmada con otra clave distinta de la de su certificado se rechaza`() {
        val (id, request) = newRequest()
        val forged = WalletSimulator(trustedIssuer, issuerSignsWith = untrustedIssuer.leafKey)

        post(request, forged.encryptedResponse(request, "academic", forged.presentation(request, graduate))).expectStatus().isBadRequest

        assertNotEquals("RECEIVED", status(id))
    }

    @Test
    fun `una prueba de posesion con nonce ajeno se rechaza`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate, kbNonce = "otro-nonce"))).expectStatus().isBadRequest

        assertNotEquals("RECEIVED", status(id))
    }

    @Test
    fun `una prueba de posesion dirigida a otro verificador se rechaza`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate, kbAudience = "x509_hash:otro-verificador"))).expectStatus().isBadRequest

        assertNotEquals("RECEIVED", status(id))
    }

    @Test
    fun `no se acepta una segunda respuesta a la misma solicitud`() {
        val (id, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)
        val body = wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate))

        post(request, body).expectStatus().isOk
        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate))).expectStatus().isBadRequest

        assertEquals("RECEIVED", status(id))
    }

    @Test
    fun `una respuesta con el state de otra solicitud se rechaza`() {
        val (_, request) = newRequest()
        val wallet = WalletSimulator(trustedIssuer)

        post(request, wallet.encryptedResponse(request, "academic", wallet.presentation(request, graduate), state = "state-ajeno")).expectStatus().isBadRequest
    }

    @Test
    fun `una respuesta que no esta cifrada para este verificador se rechaza`() {
        val (_, request) = newRequest()

        post(request, "no.es.un.jwe").expectStatus().isBadRequest
    }

    // ---- ayudas --------------------------------------------------------------------------------

    private fun newRequest(): Pair<String, WalletRequest> {
        val created = json.readTree(
            client.post().uri("/api/academic/requests").header("X-Requested-With", "verifier-ui").exchange()
                .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!,
        )
        val query = URI(created.get("authorizationRequestUri").asText()).rawQuery.split('&')
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val path = URI(query.getValue("request_uri")).path.removePrefix("/verifier")
        val jwt = client.get().uri(path).exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!.trim()
        return created.get("transactionId").asText() to WalletSimulator.parse(jwt)
    }

    private fun post(request: WalletRequest, jwe: String): WebTestClient.ResponseSpec =
        client.post().uri(URI(request.responseUri).path.removePrefix("/verifier"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("response", jwe))
            .exchange()

    private fun status(id: String): String = json.readTree(
        client.get().uri("/api/academic/requests/$id/status").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!,
    ).get("status").asText()

    private fun result(id: String): JsonNode = json.readTree(
        client.get().uri("/api/academic/requests/$id/result").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!,
    )
}
