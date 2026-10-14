package co.org.avance.verifier.catalog

import co.org.avance.verifier.protocol.domain.DCQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Caso de uso "acreditar un título académico" sobre la credencial que emite el uis-issuer
 * (`dc+sd-jwt`, vct [VCT]).
 *
 * Solo se piden los atributos necesarios para acreditar el título. NO se piden: promedio, documento,
 * código estudiantil, correo ni teléfono. (Los atributos que el emisor no marca como de divulgación
 * selectiva viajan igualmente en el SD-JWT, pero esta clase solo expone los de [CLAIMS].)
 */
object AcademicTitle {
    const val VCT = "urn:eudi:academic:credential:1"
    const val QUERY_ID = "academic"
    const val KEY = "academic-title"

    /** Atributo pedido y su etiqueta para mostrar. */
    data class Claim(val name: String, val label: String)

    val CLAIMS: List<Claim> = listOf(
        Claim("issuing_institution_name", "Institución"),
        Claim("awarded_title", "Título otorgado"),
        Claim("program_name", "Programa académico"),
        Claim("academic_status", "Estado académico"),
        Claim("graduation_date", "Fecha de graduación"),
        Claim("family_name", "Apellidos"),
        Claim("given_name", "Nombres"),
    )

    /** Estados académicos que define el emisor. Solo GRADUADO acredita el título. */
    const val STATUS_GRADUATED = "GRADUADO"

    /** La finalidad ("verificar un título") no va en el DCQL: la declara el certificado de registro (WRPRC). */
    fun dcqlJson(): String {
        val claims = CLAIMS.joinToString(",") { """{"path":["${it.name}"]}""" }
        return """
            {"credentials":[{"id":"$QUERY_ID","format":"dc+sd-jwt","meta":{"vct_values":["$VCT"]},"claims":[$claims]}],
             "credential_sets":[{"options":[["$QUERY_ID"]]}]}
        """.trimIndent()
    }

    fun dcql(): DCQL = Json.decodeFromString(dcqlJson())

    val useCase: UseCase by lazy {
        UseCase(
            key = KEY,
            version = 1,
            dcql = dcql(),
            classification = Classification.PUB_EAA,
            trustProfile = "pubeaa",
            retention = Retention.DISCARD,
            titles = mapOf("es" to "Verificación de título académico"),
        )
    }

    enum class Verdict { TITLE_ACCREDITED, VALID_BUT_NOT_GRADUATED, INCOMPLETE }

    /** Un atributo ya verificado, listo para mostrar. */
    data class Shown(val label: String, val value: String)

    data class Assessment(val verdict: Verdict, val headline: String, val shown: List<Shown>)

    /**
     * Interpreta los atributos de una credencial **ya validada** (firma, cadena de confianza y prueba de posesión
     * las comprueba el protocolo al recibir la respuesta). Aquí solo se decide qué significa el contenido.
     */
    fun assess(claims: JsonObject): Assessment {
        fun value(name: String): String? = (claims[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        val shown = CLAIMS.mapNotNull { c -> value(c.name)?.let { Shown(c.label, it) } }
        val status = value("academic_status")
        val title = value("awarded_title")
        val institution = value("issuing_institution_name")

        return when {
            status == null || title == null || institution == null ->
                Assessment(Verdict.INCOMPLETE, "La credencial es válida pero no trae todos los datos para acreditar un título", shown)

            status == STATUS_GRADUATED ->
                Assessment(Verdict.TITLE_ACCREDITED, "Título acreditado: $title ($institution)", shown)

            else ->
                Assessment(Verdict.VALID_BUT_NOT_GRADUATED, "Credencial válida, pero el estado académico es $status: no acredita un título", shown)
        }
    }
}
