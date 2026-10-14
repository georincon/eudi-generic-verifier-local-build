package co.org.avance.verifier.catalog

import co.org.avance.verifier.protocol.domain.DCQL

/** Clasificación de la credencial; decide qué lista de confianza aplica. */
enum class Classification { PID, QEAA, PUB_EAA, EAA }

/** Qué se conserva del resultado tras entregarlo. */
enum class Retention { DISCARD, KEEP_ASSERTION_ONLY }

/**
 * Caso de uso declarativo: qué se pide, a quién se confía y cómo se presenta.
 * Añadir un caso nuevo no requiere código, solo una definición de este tipo.
 */
data class UseCase(
    val key: String,
    val version: Int,
    val dcql: DCQL,
    val classification: Classification,
    val trustProfile: String,
    val retention: Retention = Retention.DISCARD,
    val titles: Map<String, String> = emptyMap(),
    val active: Boolean = true,
)

/** Fuente de casos de uso. La implementación definitiva leerá de PostgreSQL. */
interface UseCaseCatalog {
    suspend fun find(key: String): UseCase?
}

class InMemoryUseCaseCatalog(useCases: List<UseCase>) : UseCaseCatalog {
    private val byKey = useCases.filter { it.active }.associateBy { it.key }
    override suspend fun find(key: String): UseCase? = byKey[key]
}
