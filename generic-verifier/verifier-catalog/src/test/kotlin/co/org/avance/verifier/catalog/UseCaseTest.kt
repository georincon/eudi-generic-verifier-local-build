package co.org.avance.verifier.catalog

import co.org.avance.verifier.protocol.domain.DCQL
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UseCaseTest {
    private val ageOver18Dcql = """
        {"credentials":[{"id":"pid_age","format":"mso_mdoc",
          "meta":{"doctype_value":"eu.europa.ec.eudi.pid.1"},
          "claims":[{"path":["eu.europa.ec.eudi.pid.1","age_over_18"]}]}],
         "credential_sets":[{"options":[["pid_age"]]}]}
    """.trimIndent()

    private fun ageOver18() = UseCase(
        key = "age-over-18",
        version = 1,
        dcql = Json.decodeFromString<DCQL>(ageOver18Dcql),
        classification = Classification.PID,
        trustProfile = "pid-providers",
        titles = mapOf("es" to "Mayoría de edad", "en" to "Over 18"),
    )

    @Test
    fun `el DCQL del caso de edad se interpreta con el dominio del protocolo`() {
        val dcql = ageOver18().dcql
        assertEquals(1, dcql.credentials.value.size)
        assertEquals("pid_age", dcql.credentials.value.first().id.value)
    }

    @Test
    fun `el catalogo encuentra casos activos y no los inactivos`() = runTest {
        val catalog = InMemoryUseCaseCatalog(listOf(ageOver18(), ageOver18().copy(key = "off", active = false)))
        assertNotNull(catalog.find("age-over-18"))
        assertNull(catalog.find("off"))
    }
}
