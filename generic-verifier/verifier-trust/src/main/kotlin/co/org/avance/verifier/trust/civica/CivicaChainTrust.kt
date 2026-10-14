package co.org.avance.verifier.trust.civica

import arrow.core.NonEmptyList
import eu.europa.ec.eudi.etsi1196x2.consultation.CertificationChainValidation
import eu.europa.ec.eudi.etsi1196x2.consultation.IsChainTrustedForContextF
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChainUsingPKIXJvm
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import eu.europa.ec.eudi.etsi1196x2.consultation.NonEmptyList as ConsultationNonEmptyList

/** Dirección de cada lista de confianza del tl-publisher. Una lista sin dirección no da confianza a su contexto. */
data class CivicaTlLists(
    val pid: String? = null,
    val pubEaa: String? = null,
    val walletProviders: String? = null,
    val wrpac: String? = null,
    val wrprc: String? = null,
)

/**
 * Decide si una cadena de certificados es de confianza consultando las listas del tl-publisher.
 *
 * - Cada lista se descarga, se comprueba su firma y su firmante ([CivicaTlJwtVerifier]), que sea la
 *   lista esperada para el contexto, y que no esté caducada.
 * - Solo cuentan los servicios en estado `granted` cuyo inicio ya ocurrió.
 * - La cadena se valida (PKIX) hasta uno de esos certificados.
 * - **Falla cerrado**: sin lista válida (inaccesible, firma mala, caducada) no hay confianza. Si una
 *   lista nueva no se puede descargar, se sigue usando la anterior solo mientras no haya caducado.
 *
 * Las listas no publican CRL ni OCSP, así que la revocación de certificados no se comprueba.
 */
class CivicaChainTrust(
    private val lists: CivicaTlLists,
    signerPinsSha256: Set<String>,
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(15),
    private val fetchTimeout: Duration = Duration.ofSeconds(10),
) : IsChainTrustedForContextF<NonEmptyList<X509Certificate>, VerificationContext, TrustAnchor> {

    private val verifier = CivicaTlJwtVerifier(signerPinsSha256)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = HashMap<String, CachedList>()
    private val log = System.getLogger(CivicaChainTrust::class.java.name)

    private data class CachedList(val anchors: List<X509Certificate>, val fetchedAt: Instant, val validUntil: Instant)

    private sealed interface Anchors {
        data class Found(val certificates: List<X509Certificate>) : Anchors
        data class Unavailable(val reason: String) : Anchors
    }

    override suspend fun invoke(
        chain: NonEmptyList<X509Certificate>,
        context: VerificationContext,
    ): CertificationChainValidation<TrustAnchor> {
        val (url, scheme) = listFor(context)
            ?: return notTrusted("no hay lista de confianza configurada para el contexto $context")

        return when (val anchors = anchorsFor(url, scheme)) {
            is Anchors.Unavailable -> notTrusted(anchors.reason)
            is Anchors.Found ->
                ValidateCertificateChainUsingPKIXJvm(customization = { isRevocationEnabled = false })
                    .invoke(
                        chain = chain,
                        trustAnchors = ConsultationNonEmptyList(anchors.certificates.map { TrustAnchor(it, null) }),
                    )
        }
    }

    private fun listFor(context: VerificationContext): Pair<String, String>? {
        val (url, scheme) = when (context) {
            VerificationContext.PID, VerificationContext.PIDStatus -> lists.pid to "PID"
            VerificationContext.PubEAA, VerificationContext.PubEAAStatus -> lists.pubEaa to "PUBEAA"
            VerificationContext.WalletProviderAttestation, VerificationContext.WalletOrKeyStorageStatus ->
                lists.walletProviders to "WP"
            VerificationContext.WalletRelyingPartyAccessCertificate -> lists.wrpac to "WRPAC"
            VerificationContext.WalletRelyingPartyRegistrationCertificate,
            VerificationContext.WalletRelyingPartyRegistrationCertificateStatus,
            -> lists.wrprc to "WRPRC"
            else -> return null
        }
        return url?.let { it to scheme }
    }

    private suspend fun anchorsFor(url: String, scheme: String): Anchors = mutex.withLock {
        val now = clock.instant()
        val cached = cache[url]
        if (cached != null && now.isBefore(cached.fetchedAt.plus(cacheTtl)) && now.isBefore(cached.validUntil)) {
            return Anchors.Found(cached.anchors)
        }
        when (val fresh = fetch(url, scheme, now)) {
            is Fetched.Ok -> {
                cache[url] = fresh.list
                Anchors.Found(fresh.list.anchors)
            }
            is Fetched.Failed ->
                if (cached != null && now.isBefore(cached.validUntil)) {
                    log.log(System.Logger.Level.WARNING, "No se pudo actualizar $scheme (${fresh.reason}); se usa la copia vigente")
                    Anchors.Found(cached.anchors)
                } else {
                    Anchors.Unavailable("lista $scheme no disponible: ${fresh.reason}")
                }
        }
    }

    private sealed interface Fetched {
        data class Ok(val list: CachedList) : Fetched
        data class Failed(val reason: String) : Fetched
    }

    private suspend fun fetch(url: String, expectedScheme: String, now: Instant): Fetched =
        try {
            val response = withTimeout(fetchTimeout.toMillis()) { httpClient.get(url) }
            if (!response.status.isSuccess()) {
                Fetched.Failed("HTTP ${response.status.value}")
            } else {
                parse(response.bodyAsText().trim(), expectedScheme, now)
            }
        } catch (e: Exception) {
            Fetched.Failed("${e::class.simpleName}: ${e.message}")
        }

    private fun parse(jwt: String, expectedScheme: String, now: Instant): Fetched =
        when (val outcome = verifier.verify(jwt, now)) {
            is TlJwtOutcome.Rejected -> Fetched.Failed(outcome.reason)
            is TlJwtOutcome.Verified -> {
                val payload = json.decodeFromString<CivicaTlPayload>(outcome.payloadJson)
                val expiry = Instant.ofEpochSecond(payload.exp)
                when {
                    payload.scheme != expectedScheme -> Fetched.Failed("se esperaba la lista $expectedScheme y llegó ${payload.scheme}")
                    !now.isBefore(expiry) -> Fetched.Failed("lista caducada el $expiry")
                    Instant.ofEpochSecond(payload.iat).isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS)) -> Fetched.Failed("lista emitida en el futuro")
                    else -> {
                        val anchors = activeAnchors(payload, now)
                        if (anchors.isEmpty()) {
                            Fetched.Failed("la lista no tiene servicios vigentes")
                        } else {
                            Fetched.Ok(CachedList(anchors, now, expiry))
                        }
                    }
                }
            }
        }

    private fun activeAnchors(payload: CivicaTlPayload, now: Instant): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return payload.services
            .filter { it.status.endsWith("/granted") && !Instant.parse(it.statusStart).isAfter(now) }
            .flatMap { it.certificates }
            .map { factory.generateCertificate(ByteArrayInputStream(java.util.Base64.getDecoder().decode(it.derB64))) as X509Certificate }
            .distinctBy { CivicaTlJwtVerifier.sha256Hex(it.encoded) }
    }

    private fun notTrusted(reason: String): CertificationChainValidation<TrustAnchor> =
        CertificationChainValidation.NotTrusted(IllegalStateException(reason))

    private companion object {
        const val CLOCK_SKEW_SECONDS = 300L
    }
}
