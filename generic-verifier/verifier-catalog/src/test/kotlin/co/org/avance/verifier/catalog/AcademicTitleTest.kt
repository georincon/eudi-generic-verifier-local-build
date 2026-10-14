package co.org.avance.verifier.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcademicTitleTest {

    private fun claims(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun `el DCQL pide solo los atributos necesarios y ninguno sensible`() {
        val asked = AcademicTitle.CLAIMS.map { it.name }

        assertEquals(7, asked.size)
        listOf("accumulated_average", "document_number", "student_id", "email", "phone_number").forEach {
            assertFalse(it in asked, "no debe pedirse $it")
        }
        assertTrue("\"urn:eudi:academic:credential:1\"" in AcademicTitle.dcqlJson())
    }

    @Test
    fun `el DCQL se interpreta con el dominio del protocolo`() {
        val dcql = AcademicTitle.dcql()

        assertEquals(1, dcql.credentials.value.size)
        assertEquals(AcademicTitle.QUERY_ID, dcql.credentials.value.first().id.value)
    }

    @Test
    fun `un graduado acredita el titulo`() {
        val result = AcademicTitle.assess(
            claims(
                "issuing_institution_name" to "Universidad Industrial de Santander",
                "awarded_title" to "Ingeniero de Sistemas",
                "program_name" to "Ingeniería de Sistemas",
                "academic_status" to "GRADUADO",
                "graduation_date" to "2024-12-13",
                "family_name" to "Pérez",
                "given_name" to "Ana",
            ),
        )

        assertEquals(AcademicTitle.Verdict.TITLE_ACCREDITED, result.verdict)
        assertTrue("Ingeniero de Sistemas" in result.headline)
        assertEquals(7, result.shown.size)
    }

    @Test
    fun `un estudiante activo tiene credencial valida pero no acredita titulo`() {
        val result = AcademicTitle.assess(
            claims(
                "issuing_institution_name" to "UIS",
                "awarded_title" to "Ingeniero de Sistemas",
                "academic_status" to "ACTIVO",
            ),
        )

        assertEquals(AcademicTitle.Verdict.VALID_BUT_NOT_GRADUATED, result.verdict)
    }

    @Test
    fun `una credencial sin titulo o sin institucion es incompleta`() {
        val result = AcademicTitle.assess(claims("academic_status" to "GRADUADO"))

        assertEquals(AcademicTitle.Verdict.INCOMPLETE, result.verdict)
    }

    @Test
    fun `no se muestran atributos que no se pidieron aunque la credencial los traiga`() {
        val result = AcademicTitle.assess(
            claims(
                "issuing_institution_name" to "UIS",
                "awarded_title" to "Ingeniero",
                "academic_status" to "GRADUADO",
                "document_number" to "1098765432",
                "accumulated_average" to "4.5",
            ),
        )

        assertEquals(setOf("Institución", "Título otorgado", "Estado académico"), result.shown.map { it.label }.toSet())
    }

    @Test
    fun `el caso de uso se clasifica como PubEAA con descarte de datos`() {
        assertEquals(Classification.PUB_EAA, AcademicTitle.useCase.classification)
        assertEquals(Retention.DISCARD, AcademicTitle.useCase.retention)
    }
}
