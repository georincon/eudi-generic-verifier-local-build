package co.org.avance.verifier.trust

import arrow.core.NonEmptyList
import eu.europa.ec.eudi.etsi119602.consultation.DownloadSingleLoTE
import eu.europa.ec.eudi.etsi119602.consultation.LoadLoTEAndPointers
import eu.europa.ec.eudi.etsi119602.consultation.LoadSingleLoTEWithFileCache
import eu.europa.ec.eudi.etsi119602.consultation.LotEMeta
import eu.europa.ec.eudi.etsi119602.consultation.ProvisionTrustAnchorsFromLoTEs
import eu.europa.ec.eudi.etsi119602.consultation.VerifyJwtSignature
import eu.europa.ec.eudi.etsi119602.consultation.eu
import eu.europa.ec.eudi.etsi119602.consultation.eudiwJvm
import eu.europa.ec.eudi.etsi119602.datamodel.Uri
import eu.europa.ec.eudi.etsi1196x2.consultation.DisposableScope
import eu.europa.ec.eudi.etsi1196x2.consultation.IsChainTrustedForContextF
import eu.europa.ec.eudi.etsi1196x2.consultation.SupportedLists
import eu.europa.ec.eudi.etsi1196x2.consultation.ValidateCertificateChainUsingPKIXJvm
import eu.europa.ec.eudi.etsi1196x2.consultation.VerificationContext
import io.ktor.client.HttpClient
import kotlinx.io.files.Path
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.nio.file.Path as NioPath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Decide si una cadena de certificados es de confianza consultando las listas (libretas) de
 * confianza: descarga cada lista, verifica su firma, extrae las CA ("muñecas grandes") que
 * autorizan cada contexto (PID, WRPAC, ...) y valida la cadena hasta una de ellas (PKIX).
 *
 * Sigue la receta de la implementación de referencia del wallet (`EtsiTrustProvider`), pero con
 * un verificador de listas que puede fijar el firmante ([PinnedLoteJwtVerifier]).
 */
object LoteChainTrust {

    /** Mismo tipo que usa el protocolo copiado del `core` para decidir confianza. */
    typealias ChainTrust = IsChainTrustedForContextF<NonEmptyList<X509Certificate>, VerificationContext, TrustAnchor>

    fun create(
        locations: SupportedLists<Uri>,
        jwtVerifier: VerifyJwtSignature,
        httpClient: HttpClient,
        cacheDirectory: NioPath,
        lifecycle: DisposableScope,
        fileCacheExpiration: Duration = 24.hours,
        cacheTtl: Duration = 20.minutes,
        relaxCertificateProfiles: Boolean = false,
        checkRevocation: Boolean = true,
    ): ChainTrust {
        val loadLoTE = LoadSingleLoTEWithFileCache(
            cacheDirectory = Path(cacheDirectory.toString()),
            downloadSingleLoTE = DownloadSingleLoTE(httpClient),
            fileCacheExpiration = fileCacheExpiration,
        )
        val loadLoTEAndPointers = LoadLoTEAndPointers(
            constraints = LoadLoTEAndPointers.Constraints.DoNotLoadOtherPointers,
            verifyJwtSignature = jwtVerifier,
            loadLoTE = loadLoTE,
        )
        val svcTypePerCtx = SupportedLists.eu().let { if (relaxCertificateProfiles) relaxProfiles(it) else it }
        val provision = ProvisionTrustAnchorsFromLoTEs.eudiwJvm(
            loadLoTEAndPointers = loadLoTEAndPointers,
            svcTypePerCtx = svcTypePerCtx,
            pkix = ValidateCertificateChainUsingPKIXJvm(customization = { isRevocationEnabled = checkRevocation }),
        )
        val compose = provision.cached(lifecycle, locations, ttl = cacheTtl)
        // NonEmptyList de Arrow es una List, así que el adaptador de tipos es directo.
        return IsChainTrustedForContextF { chain, context -> compose(chain, context) }
    }

    private fun relaxProfiles(eu: SupportedLists<LotEMeta<VerificationContext>>): SupportedLists<LotEMeta<VerificationContext>> {
        fun LotEMeta<VerificationContext>.relax() = copy(
            svcTypePerCtx = svcTypePerCtx.mapValues { (_, v) -> v.copy(endEntityProfile = null) },
        )
        return eu.copy(
            pidProviders = eu.pidProviders?.relax(),
            walletProviders = eu.walletProviders?.relax(),
            wrpacProviders = eu.wrpacProviders?.relax(),
            wrprcProviders = eu.wrprcProviders?.relax(),
            pubEaaProviders = eu.pubEaaProviders?.relax(),
            qeaProviders = eu.qeaProviders?.relax(),
            eaaProviders = eu.eaaProviders.mapValues { (_, v) -> v.relax() },
        )
    }
}
