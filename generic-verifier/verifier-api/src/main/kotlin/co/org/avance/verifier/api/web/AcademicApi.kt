package co.org.avance.verifier.api.web

import arrow.core.raise.either
import co.org.avance.verifier.catalog.AcademicTitle
import co.org.avance.verifier.protocol.domain.Presentation
import co.org.avance.verifier.protocol.domain.TransactionId
import co.org.avance.verifier.protocol.port.input.GetWalletResponse
import co.org.avance.verifier.protocol.port.input.InitTransaction
import co.org.avance.verifier.protocol.port.input.InitTransactionResponse
import co.org.avance.verifier.protocol.port.input.InitTransactionTO
import co.org.avance.verifier.protocol.port.input.Output
import co.org.avance.verifier.protocol.port.input.ProcessSdJwtVc
import co.org.avance.verifier.protocol.port.input.QueryResponse
import co.org.avance.verifier.protocol.port.input.ValidationError
import co.org.avance.verifier.protocol.port.out.persistence.LoadPresentationById
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.util.Base64

/**
 * API que usan las páginas del verificador académico. **No expone DCQL libre**: la solicitud es siempre
 * la del caso de uso [AcademicTitle], de modo que un tercero no puede pedirle a una persona atributos distintos
 * usando nuestro certificado de acceso.
 */
@RestController
@RequestMapping("/api/academic")
class AcademicApi(
    private val initTransaction: InitTransaction,
    private val loadPresentationById: LoadPresentationById,
    private val getWalletResponse: GetWalletResponse,
    private val processSdJwtVc: ProcessSdJwtVc,
    private val maxAgeSeconds: Long = DEFAULT_SESSION_SECONDS,
) {
    private val log = LoggerFactory.getLogger(AcademicApi::class.java)
    private val random = SecureRandom()

    @Serializable
    data class CreatedTO(
        val transactionId: String,
        val qrPngBase64: String,
        val authorizationRequestUri: String,
        val expiresInSeconds: Long,
    )

    @Serializable
    data class StatusTO(val status: String)

    @Serializable
    data class ShownTO(val label: String, val value: String)

    @Serializable
    data class ResultTO(val verdict: String, val headline: String, val attributes: List<ShownTO> = emptyList())

    @PostMapping("/requests")
    suspend fun create(
        @RequestHeader(name = "X-Requested-With", required = false) requestedWith: String?,
    ): ResponseEntity<Any> {
        if (requestedWith != REQUIRED_HEADER_VALUE) return ResponseEntity.badRequest().body(mapOf("error" to "missing_header"))

        val input = InitTransactionTO(
            dcqlQuery = AcademicTitle.dcql(),
            nonce = newNonce(),
            responseMode = InitTransactionTO.ResponseModeTO.DirectPostJwt,
            jarMode = co.org.avance.verifier.protocol.port.input.EmbedModeTO.ByReference,
            requestUriMethod = co.org.avance.verifier.protocol.port.input.RequestUriMethodTO.Get,
            intendedUseId = INTENDED_USE_ID,
            output = Output.QrCode,
        )

        return either<ValidationError, InitTransactionResponse> { initTransaction(input) }.fold(
            ifLeft = {
                log.warn("No se pudo iniciar la transacción académica: {}", it)
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to "init_failed", "detail" to it::class.simpleName))
            },
            ifRight = { response ->
                when (response) {
                    is InitTransactionResponse.QrCode ->
                        ResponseEntity.ok(
                            CreatedTO(
                                transactionId = response.transactionId,
                                qrPngBase64 = Base64.getEncoder().encodeToString(response.qrCode),
                                authorizationRequestUri = response.authorizationRequestUri,
                                expiresInSeconds = maxAgeSeconds,
                            ),
                        )
                    else -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "unexpected_output"))
                }
            },
        )
    }

    /** Estado sin tocar la respuesta: no deja eventos de "fallo" mientras la billetera aún no responde. */
    @GetMapping("/requests/{id}/status")
    suspend fun status(@PathVariable id: String): ResponseEntity<StatusTO> {
        val presentation = loadPresentationById(TransactionId(id)) ?: return ResponseEntity.notFound().build()
        val status =
            when (presentation) {
                is Presentation.Requested -> "WAITING_WALLET"
                is Presentation.RequestObjectRetrieved -> "WALLET_CONNECTED"
                is Presentation.Submitted -> "RECEIVED"
                else -> "EXPIRED"
            }
        return ResponseEntity.ok(StatusTO(status))
    }

    @GetMapping("/requests/{id}/result")
    suspend fun result(@PathVariable id: String): ResponseEntity<Any> =
        when (val response = getWalletResponse(TransactionId(id), null)) {
            is QueryResponse.NotFound -> ResponseEntity.notFound().build()
            is QueryResponse.InvalidState -> ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "not_ready"))
            is QueryResponse.Found -> {
                val wallet = response.value
                if (wallet.error != null) {
                    ResponseEntity.ok(ResultTO("WALLET_DECLINED", "La billetera no compartió la credencial (${wallet.error})"))
                } else {
                    val token = wallet.vpToken?.get(AcademicTitle.QUERY_ID)?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    if (token == null) {
                        ResponseEntity.ok(ResultTO("NO_CREDENTIAL", "La respuesta no incluye la credencial solicitada"))
                    } else {
                        // La firma, la cadena de confianza y la prueba de posesión ya se validaron al recibir la respuesta.
                        val assessment = AcademicTitle.assess(processSdJwtVc(token))
                        ResponseEntity.ok(
                            ResultTO(
                                verdict = assessment.verdict.name,
                                headline = assessment.headline,
                                attributes = assessment.shown.map { ShownTO(it.label, it.value) },
                            ),
                        )
                    }
                }
            }
        }

    private fun newNonce(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private companion object {
        const val REQUIRED_HEADER_VALUE = "verifier-ui"
        const val INTENDED_USE_ID = "academic"
        const val DEFAULT_SESSION_SECONDS = 600L
    }
}
