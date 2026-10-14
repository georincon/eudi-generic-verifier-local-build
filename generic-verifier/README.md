# generic-verifier

Verifier genérico para el ecosistema EUDI Wallet. Kotlin, Spring Boot 4.1 (WebFlux), PostgreSQL. Java 25.
Plan completo en `../README.md`.

## Módulos

| Módulo | Estado | Contenido |
|---|---|---|
| `verifier-protocol` | Copiado y adaptado | Dominio, puertos y adapters de protocolo del `verifier-endpoint` (DCQL, JAR, mdoc, SD-JWT VC, status list, cifrado de respuesta). Paquete `co.org.avance.verifier.protocol`. **No contiene Spring** |
| `verifier-trust` | Nuevo | Confianza basada en las libretas (listas LoTE): `LoteChainTrust` (receta oficial con caché y PKIX) y `PinnedLoteJwtVerifier` (verifica la firma de la lista **y fija quién la firmó**). **Todavía no está conectado al protocolo ni a `verifier-api`** |
| `verifier-catalog` | Nuevo, mínimo | `UseCase`, `UseCaseCatalog` y una implementación en memoria. Reutiliza el `DCQL` del protocolo |
| `verifier-persistence` | Solo esquema | Migración Flyway `V1__base_schema.sql`. **Sin código ni conexión a Postgres todavía** |
| `verifier-api` | Esqueleto | App Spring Boot: `GET /api/ping` y `/actuator/health` públicos, todo lo demás devuelve 401 |

Pendiente que no existe aún: `verifier-ui`. Falta además **conectar `verifier-trust` al protocolo** (hoy el protocolo copiado sigue pudiendo usar `Ignored`, que confía en todo).

## Comandos

```bash
./gradlew test                     # 23 tests sin red
LIVE_LOTE=1 ./gradlew test         # añade 1 prueba contra la libreta real de la Comisión (necesita red)
./gradlew :verifier-api:bootJar    # JAR de ~84 MB
$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/java -jar verifier-api/build/libs/verifier-api-0.1.0-SNAPSHOT.jar
```

Gradle descarga solo JDK 25 (plugin foojay). No usar el `java` del sistema (21): el JAR está compilado para 25.

## Estado verificado (2026-10-01)
- Compila y pasan 23 tests (protocol 10, trust 9 [8 sin red + 1 opcional con red], catalog 2, api 2).
- La app arranca y responde `ping` 200, `health` UP y 401 en rutas protegidas.
- **El flujo de verificación aún no existe en este proyecto**: no hay endpoints de `/ui/presentations` ni `/wallet/*`. El backend de referencia (`../core`) sigue siendo el que lo hace.

## Qué se dejó fuera de la copia y por qué
- `adapter/input/web`, `VerifierContext.kt`: se reescriben (sin auth, CSRF off, un solo tenant).
- `adapter/out/persistence` (en memoria): se reemplaza por Postgres.
- `adapter/out/keystore`, `adapter/out/config`: acoplados a Spring, se reescriben en `verifier-api`.
- `adapter/out/consultation` **sí se copió** porque `mso` y `sdjwtvc` dependen de él, pero es la confianza "por defecto" (confía en todos o llama al servicio externo). Se reemplaza en la Fase 2.

## Tests pendientes de portar (`pending-tests/`)
`JweTest`, `CreateJarNimbusTest`, `DeviceResponseValidatorTest` e `InitTransactionTest` dependen del `TestContext` del `core` (Spring, keystore y repositorio en memoria). Se portan cuando exista el wiring en `verifier-api`. **`DeviceResponseValidatorTest` es el más importante**: es la cobertura de la validación de mdoc.

## Limitación conocida
`domain` y `port/input` importan de adapters (`json`, `jose`, `mso`, `sdjwtvc`), por lo que no se pudieron separar en un módulo de dominio puro sin refactorizar. Está todo en `verifier-protocol`.

## verifier-trust: qué hace y qué se verificó (2026-10-13)

`LoteChainTrust.create(...)` devuelve el mismo tipo de confianza que ya espera el protocolo copiado (`IsChainTrustedForContextF<NonEmptyList<X509Certificate>, VerificationContext, TrustAnchor>`), así que el cambio de `Ignored` a esta implementación es de una línea cuando se conecte.

| Prueba | Resultado |
|---|---|
| Cadena que llega a una CA de la libreta | De confianza |
| Cadena de una CA ajena | No de confianza |
| Lista firmada por un firmante distinto del fijado | Rechazada (la carga falla cerrada, con excepción) |
| Lista firmada por el firmante fijado | Aceptada |
| Contexto que ninguna lista cubre (PID con solo la lista WRPAC) | Sin confianza |
| JWT de lista alterado | Rechazado |
| **Libreta real de la Comisión** con su firmante fijado (opcional, `LIVE_LOTE=1`) | La CA `WRPAC Issuer CA 01` es de confianza; una CA ajena no |

### Hallazgos
1. **La cadena de ejemplo del upstream no sirve ante un validador estricto.** Su `intermediate` está marcada "no es CA": `basic constraints check failed: this is not a CA certificate`. Falla **aunque su raíz estuviera en la libreta**. Hay una prueba de regresión que lo documenta. Para pruebas con wallet hay que generar una cadena bien formada (las pruebas ya incluyen cómo, con BouncyCastle).
2. **El verificador de listas del wallet de referencia no ancla al firmante**: solo comprueba la firma con el `x5c` de la propia lista (visto en el código fuente de `LoteJwtVerifier`). Aquí lo hemos hecho distinto: `PinnedLoteJwtVerifier.pinned(...)`. `unpinnedForDevelopment()` reproduce el comportamiento del wallet y solo debe usarse en desarrollo.
3. Las listas reales de desarrollo de la Comisión van firmadas por `CN=PY PID Issuer DEV` (sha256 `d88326a0…d8512a`). Son de pruebas: en producción habrá que fijar los firmantes que se publiquen oficialmente.

### Limitaciones
- La revocación de certificados (`checkRevocation`) está implementada pero **no probada** (las pruebas la desactivan).
- No hay verificación de la firma de la **lista de estado** (pizarra); sigue pendiente en el protocolo.
- La librería `etsi-119602-consultation` es **0.4.0-alpha.1**: su API puede cambiar.
- La receta de `LoteChainTrust` está tomada de `EtsiTrustProvider` del wallet-core 0.30.2 (código fuente en la caché de Gradle). **Licencia de esa pieza no verificada.**
- Las pruebas usan como fixture el contenido real de `WRPACProviders` y el keystore de ejemplo del upstream (datos públicos de pruebas).
