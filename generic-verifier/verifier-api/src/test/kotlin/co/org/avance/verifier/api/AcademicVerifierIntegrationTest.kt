package co.org.avance.verifier.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AcademicVerifierIntegrationTest(@Autowired private val client: WebTestClient) {

    private val json = ObjectMapper()

    companion object {
        private val pki = TestPki(Files.createTempDirectory("verifier-test-pki"))

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("verifier.access-certificate.keystore") { "file:${pki.keystore}" }
            registry.add("verifier.access-certificate.keystore.password") { pki.password }
            registry.add("verifier.access-certificate.password") { pki.password }
            // una lista definida en otra fuente REEMPLAZA a la del application.properties: hay que dar el elemento completo
            registry.add("verifier.intendedUses[0].id") { "academic" }
            registry.add("verifier.intendedUses[0].description") { "Verificar un titulo academico" }
            registry.add("verifier.intendedUses[0].registration-certificate-file") { pki.wrprc.toString() }
            registry.add("verifier.publicUrl") { "http://localhost:8480/verifier" }
        }
    }

    // ---- páginas y seguridad -------------------------------------------------------------

    @Test
    fun `la pagina de inicio se sirve con CSP estricta y enlaces con el prefijo publico`() {
        val body = client.get().uri("/").exchange()
            .expectStatus().isOk
            .expectHeader().value("Content-Security-Policy") { assertTrue("script-src 'self'" in it && "frame-ancestors 'none'" in it) }
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue("Avance Verifier" in body && "Corporación Avance" in body)
        assertTrue("href=\"/verifier/verify\"" in body, "los enlaces deben llevar el prefijo /verifier")
        assertTrue("style=" !in body && "<script>" !in body, "sin estilos ni scripts en línea")
    }

    @Test
    fun `la pagina de verificacion y sus recursos estaticos se sirven`() {
        client.get().uri("/verify").exchange().expectStatus().isOk
        client.get().uri("/public/css/main.css").exchange().expectStatus().isOk
        client.get().uri("/public/js/verify.js").exchange().expectStatus().isOk
        client.get().uri("/webjars/bootstrap/css/bootstrap.min.css").exchange().expectStatus().isOk
    }

    @Test
    fun `las rutas de la API generica del core no existen y se deniegan`() {
        client.post().uri("/ui/presentations").exchange().expectStatus().isUnauthorized
        client.get().uri("/swagger-ui").exchange().expectStatus().isUnauthorized
        client.get().uri("/utilities/attestationClassifications").exchange().expectStatus().isUnauthorized
    }

    // ---- crear la solicitud ----------------------------------------------------------------

    @Test
    fun `crear una solicitud exige la cabecera anti-CSRF`() {
        client.post().uri("/api/academic/requests").exchange().expectStatus().isBadRequest
    }

    @Test
    fun `crear una solicitud devuelve un QR PNG y un enlace openid4vp`() {
        val created = createRequest()

        val png = Base64.getDecoder().decode(created.get("qrPngBase64").asText())
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        assertTrue(created.get("authorizationRequestUri").asText().startsWith("openid4vp://"))
        assertEquals(600, created.get("expiresInSeconds").asInt())
    }

    @Test
    fun `el Request Object que recibiria la billetera pide solo lo necesario y va firmado con nuestro carnet`() {
        val created = createRequest()
        val requestPath = requestPath(created.get("authorizationRequestUri").asText())

        val jwt = client.get().uri(requestPath).exchange()
            .expectStatus().isOk
            .expectHeader().contentType("application/oauth-authz-req+jwt")
            .expectBody(String::class.java).returnResult().responseBody!!.trim()

        val (header, payload) = jwt.split('.').let { decode(it[0]) to decode(it[1]) }
        assertEquals("oauth-authz-req+jwt", header.get("typ").asText())
        assertEquals("ES256", header.get("alg").asText())
        assertTrue(header.get("x5c").size() >= 1, "debe viajar el carnet (x5c)")
        assertEquals(
            Base64.getEncoder().encodeToString(pki.leaf.encoded),
            header.get("x5c").get(0).asText(),
            "el x5c debe ser nuestro carnet",
        )

        assertTrue(payload.get("client_id").asText().startsWith("x509_hash:"))
        assertEquals("vp_token", payload.get("response_type").asText())
        assertEquals("direct_post.jwt", payload.get("response_mode").asText())
        assertTrue(payload.has("verifier_info"), "debe incluir el certificado de registro")

        val claims = payload.get("dcql_query").get("credentials").get(0).get("claims").map { it.get("path").get(0).asText() }.toSet()
        assertEquals(
            setOf("issuing_institution_name", "awarded_title", "program_name", "academic_status", "graduation_date", "family_name", "given_name"),
            claims,
        )
        listOf("accumulated_average", "document_number", "student_id", "email", "phone_number").forEach {
            assertTrue(it !in claims, "no debe pedirse $it")
        }
        assertEquals(
            "urn:eudi:academic:credential:1",
            payload.get("dcql_query").get("credentials").get(0).get("meta").get("vct_values").get(0).asText(),
        )
    }

    // ---- estado --------------------------------------------------------------------------------

    @Test
    fun `el estado avanza de esperando a billetera conectada sin dejar fallos`() {
        val created = createRequest()
        val id = created.get("transactionId").asText()

        assertEquals("WAITING_WALLET", status(id))

        client.get().uri(requestPath(created.get("authorizationRequestUri").asText())).exchange().expectStatus().isOk

        assertEquals("WALLET_CONNECTED", status(id))
    }

    @Test
    fun `una transaccion desconocida da 404`() {
        client.get().uri("/api/academic/requests/no-existe/status").exchange().expectStatus().isNotFound
    }

    // ---- ayudas --------------------------------------------------------------------------------

    private fun createRequest() = json.readTree(
        client.post().uri("/api/academic/requests").header("X-Requested-With", "verifier-ui").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!,
    )

    private fun status(id: String): String = json.readTree(
        client.get().uri("/api/academic/requests/$id/status").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!,
    ).get("status").asText()

    private fun decode(part: String) = json.readTree(Base64.getUrlDecoder().decode(part))

    /** Ruta local (sin el prefijo /verifier del proxy) del request_uri contenido en el enlace openid4vp. */
    private fun requestPath(authorizationRequestUri: String): String {
        val query = URI(authorizationRequestUri).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return URI(query.getValue("request_uri")).path.removePrefix("/verifier")
    }
}
