# Verifier genérico EUDI: plan de construcción

Stack objetivo: Kotlin, Spring Boot, PostgreSQL.
Objetivo: un verifier completo (lógica y vistas) que verifique cualquier tipo de credencial del ecosistema EUDI Wallet (edad, identidad, estudios, certificados laborales, etc.), dirigido por un catálogo de casos de uso.

Estado de este documento: plan inicial (2026-10-01). Basado en el análisis de los proyectos de `/home/geovani/Workspace/Wallet/Verifier Apps and Services` y en una búsqueda en GitHub. Los puntos marcados "sin verificar" no se han comprobado.

## Estructura del proyecto

```
core/               fork del verifier-endpoint (Kotlin, Spring WebFlux): núcleo del protocolo
ui/                 base de la UI (Angular 19), a desacoplar del catálogo hardcodeado
docs/references/    RFC de EWC usados: 002, 003, 007, 008, 012, 013, 100 y los CSV de formatos y algoritmos
UPSTREAM.md         origen, commits y licencias de lo copiado, y archivos solo de desarrollo
generic-verifier/   PROYECTO NUEVO (multi-módulo). core/ y ui/ quedan solo como referencia de estudio
```

Entorno local detectado: JDK 21, Node 24.13.1 y Docker 29.8.2. El `core` pide **Java 25** (Adoptium) con Gradle 9.6.1 y Kotlin 2.4.0, así que hay que instalar JDK 25 o dejar que Gradle lo descargue. Es la primera decisión de la Fase 0.

## 1. Conclusión de viabilidad

Es viable y no hace falta empezar de cero. El protocolo (OpenID4VP 1.0, DCQL, JAR, mdoc, SD-JWT VC, status list) ya está resuelto en el verifier oficial. Lo que hay que construir es la capa de producto: catálogo de casos de uso, confianza de emisores, persistencia, seguridad, multi-tenant y UI.

## 2. Proyectos base analizados

| Proyecto | Rol | Uso en este plan |
|---|---|---|
| `eudi-srv-verifier-endpoint` (Kotlin, Spring WebFlux, Apache 2.0) | Motor de verificación | **Base del núcleo** (fork) |
| `eudi-web-verifier` (Angular 19) | UI de referencia | Base de UI, con el catálogo de credenciales hoy fijo en TypeScript |
| `eudi-srv-web-relyingparty-registration-py` | Registro de RP (emite WRPAC y WRPRC, requiere EJBCA y MariaDB) | Solo para pruebas. En producción, el registro oficial del Estado miembro |
| `eudi-srv-statuslist-py` | Servicio de status lists, del lado emisor | No entra. Solo si se emiten credenciales propias |
| `eudi-wallet-rfcs` (EWC) | RFCs del consorcio, sobre OpenID4VP Draft 23 | Capa de compatibilidad y fuente de esquemas, no base del protocolo |

### Hallazgos que condicionan el diseño
1. La confianza de emisores por defecto es "todos confiables". Solo hay un servicio externo no apto para producción o `issuer_chain` por transacción.
2. El backend **no verifica la firma del status list token**.
3. La Verifier API no tiene autenticación y el CORS es `*`. CSRF está deshabilitado.
4. Persistencia solo en memoria: se pierde el estado al reiniciar y no escala.
5. El catálogo de credenciales de la UI está hardcodeado y no hay i18n. Solo hay polling, sin SSE.
6. El backend exige clasificar cada credencial (PID, QEAA, PubEAA, EAA) para decidir qué lista de confianza aplica.
7. El upstream se declara "initial development release". Usa dependencias muy nuevas (Java 25, Spring Boot 4.1).

## 3. Desarrollos existentes en GitHub

| Proyecto | Descripción | Licencia | Decisión |
|---|---|---|---|
| [eudi-srv-verifier-endpoint](https://github.com/eu-digital-identity-wallet/eudi-srv-verifier-endpoint) | Verifier oficial | Apache 2.0 | Base del núcleo |
| [eudi-lib-kmp-etsi-1196x2](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-etsi-1196x2) | Validación de cadenas contra ETSI 119 612 (LOTL/TL, módulo DSS para JVM) y 119 602 (LoTE) | Apache 2.0 | Adaptador de confianza in-process |
| [eudi-srv-trust-validator](https://github.com/eu-digital-identity-wallet/eudi-srv-trust-validator) | Servicio REST de validación de confianza | Apache 2.0 | Descartado: no recomendado para producción, y la firma de LoTE no está implementada |
| [eudi-lib-kmp-statium](https://github.com/eu-digital-identity-wallet/eudi-lib-kmp-statium) | Token Status List | Apache 2.0 | Ya usada por el backend |
| [eudi-lib-jvm-sdjwt-kt](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-sdjwt-kt) | SD-JWT | Apache 2.0 | Ya usada por el backend |
| [eudi-lib-jvm-openid4vp-kt](https://github.com/eu-digital-identity-wallet/eudi-lib-jvm-openid4vp-kt) | OpenID4VP lado wallet | Apache 2.0 | Solo para pruebas con wallet simulada |
| [Age Verification Blueprint](https://github.com/eu-digital-identity-wallet/eudi-srv-web-verifier-endpoint-23220-4-kt) | Verifier de edad (ISO 23220-4) | Sin verificar | Referencia del caso de edad |
| [Zilath](https://github.com/matteopratesi/zilath) | Librería RP en Kotlin | AGPL-3.0 o comercial | Descartado: pre-alpha, enfocada a IT-Wallet y con licencia contagiosa |
| [eudistack-core-verifier](https://github.com/in2workspace/eudistack-core-verifier) | Verifier OID4VP/DCQL en Java 25 y WebFlux, con PostgreSQL y SSE | Sin verificar | Referencia de arquitectura. Revisar licencia antes de copiar código |
| Korrali, [OpenEUDI](https://github.com/openeudi), [eudi-verify](https://github.com/eudi-verify/eudi-verify) | Otros kits de verifier | Sin verificar | Solo referencia |

## 4. Arquitectura

Fork del `verifier-endpoint` como módulo de protocolo (ya es hexagonal), con módulos propios alrededor. Mantener los cambios al fork mínimos para poder seguir el upstream.

```
┌─ gateway/BFF (Spring Security, OIDC, API keys) ─────────────┐
│  admin API · use-case API · webhooks · SSE de estado        │
├─────────────────────────────────────────────────────────────┤
│  usecase-catalog    trust-adapter     result-delivery       │
│  (plantillas→DCQL)  (etsi-1196x2)     (webhook/redirect)    │
├─────────────────────────────────────────────────────────────┤
│  verifier-core  (fork de verifier-endpoint, puertos→Postgres)│
├─────────────────────────────────────────────────────────────┤
│  PostgreSQL            keystore/HSM (WRPAC)                  │
└─────────────────────────────────────────────────────────────┘
```

Un caso de uso es una plantilla declarativa, sin código nuevo:

```yaml
id: age-over-18
name: Verificación de mayoría de edad
dcql: { credentials: [{ id: pid, format: mso_mdoc, meta: { doctype_value: ... }, claims: [age_over_18] }] }
trust: [pid-providers]
classification: PID        # PID | QEAA | PubEAA | EAA
retention: discard         # no guardar atributos
ui: { title: { es: ..., en: ... }, consent_text: ... }
```

## 5. Plan por fases

Estimación de 18–24 semanas con un equipo pequeño para producción mínima (fases 0–5), sin contar la revisión de seguridad externa. Es una estimación sin base en datos del proyecto.

### Fase 0: preparación (1–2 semanas)
- [ ] Fork y build del `verifier-endpoint`. Confirmar toolchain (Java 25 y Spring Boot 4.1 del upstream, o una LTS).
- [ ] Ejecutar el flujo de edad de extremo a extremo con una wallet de pruebas.
- [ ] Decidir perfil (HAIP u `openid4vp`), países y casos de uso del primer despliegue.
- [ ] Revisar `eudistack-core-verifier` y el verifier de edad del blueprint.
- [ ] Verificar las licencias marcadas "sin verificar" antes de reutilizar código.
- [ ] Decidir si la UI se reutiliza (Angular actual) o se rehace.

### Fase 1: núcleo con persistencia (3–4 semanas)
- [ ] Implementar los puertos de almacenamiento sobre PostgreSQL (Flyway; R2DBC o JPA según el stack reactivo del core).
- [ ] Tablas `presentation`, `presentation_event` y `request_object`.
- [ ] Retención mínima: no persistir atributos por defecto y borrar tras la entrega.
- [ ] Jobs de timeout y limpieza que sobrevivan al reinicio.
- [ ] Tests de integración con Testcontainers.
- **Hito:** reiniciar no pierde transacciones y dos réplicas funcionan a la vez.

### Fase 2: confianza y validación segura (3–4 semanas)
- [ ] Adaptador de confianza sobre `etsi-1196x2` (LOTL/TL y LoTE), con anchors por caso de uso y clasificación. Sustituye el "confiar en todos".
- [ ] Política del RFC012: tipo de servicio acorde a la credencial, estado `granted`, ventana de `StatusStartingTime` e historial. Verificar la firma y la frescura de las listas, que el RFC no define.
- [ ] Verificar la firma del status list token.
- [ ] Gestión del WRPAC (keystore o HSM, rotación, alertas de caducidad).
- **Hito:** una credencial de emisor no listado falla y una status list con firma inválida se rechaza.

### Fase 3: catálogo de casos de uso (3–4 semanas)
- [ ] Modelo declarativo (YAML o tabla `use_case`).
- [ ] La API crea la presentación desde `use_case_id`. El DCQL libre solo con permiso explícito.
- [ ] Validar que los atributos coincidan con el *intended use* registrado.
- [ ] Casos iniciales: edad (`age_over_18`), identidad PID, PhotoID (`eu.europa.ec.eudi.photoid.1`).
- [ ] Estudios y empleo: requieren acordar esquema (`vct`), anchors y emisor, porque ningún RFC los define.
- **Hito:** añadir un caso de uso sin recompilar.

### Fase 4: API de producto y seguridad (2–3 semanas)
- [ ] Autenticación de organizaciones (OIDC, API keys con scopes), multi-tenant (`tenant_id`) y CORS por tenant.
- [ ] Entrega del resultado: webhook firmado con reintentos (`webhook_outbox`), redirect con `response_code` y SSE.
- [ ] Rate limiting, auditoría sin atributos personales y revisión de CSRF.
- [ ] Métricas y trazas.

### Fase 5: UI (3–4 semanas)
- [ ] Front dinámico desde el catálogo: QR, mismo dispositivo, DC API, estado en vivo e i18n (es, en y los necesarios).
- [ ] Panel de administración: casos de uso, tenants, anchors, certificados y log de eventos.

### Fase 6: endurecimiento y compatibilidad (continuo)
- [ ] Interoperabilidad con wallets reales y con el ITB (RFC100: `GET /?sessionId&credentialType` y `GET /validationStatus?sessionId`).
- [ ] Opcional según demanda: `transaction_data` (RFC008), `vc+sd-jwt` y Presentation Exchange para wallets de pilotos antiguos (Draft 23).
- [ ] Revisión de seguridad externa y pruebas de carga antes de producción.

## 6. Esquema PostgreSQL (borrador)

```
tenant(id, name, api_key_hash, webhook_url, created_at)
use_case(id, tenant_id, key, version, dcql jsonb, classification, trust_profile_id,
         retention, i18n jsonb, active)
trust_profile(id, name, sources jsonb)
presentation(id, tenant_id, use_case_id, status, nonce, created_at, expires_at, result_ref)
presentation_event(id, presentation_id, type, at, detail jsonb)
webhook_outbox(id, presentation_id, payload, attempts, next_at, delivered_at)
```

## 7. Cobertura de casos de uso

| Caso | Estado | Credencial |
|---|---|---|
| Edad | Viable ya | PhotoID (`age_over_18`, RFC013) o PID (`age_equal_or_over_*`, RFC003) |
| Identidad | Viable ya | PID |
| Empresa (B2B) | Viable, `vct` ambiguo | LPID (RFC005) |
| Pagos | Opcional | Payment Wallet Attestation con `transaction_data` (RFC007/008) |
| Estudios | Pendiente de definir | Sin estándar. Depende del emisor |
| Certificados laborales | Pendiente de definir | Sin estándar. Depende del emisor |

## 8. Riesgos

- **Regulatorio:** un RP en producción debe estar registrado y declarar los atributos que pide. Cada caso nuevo implica modificar el registro.
- **Madurez y dependencias:** el upstream no se recomienda para producción y los protocolos cambian rápido. Prever mantenimiento y rebase del fork.
- **Estudios y empleo:** sin estándar, dependen de un emisor real.
- **Licencias:** evitar Zilath (AGPL). Verificar las demás antes de reutilizar código.
- **Compatibilidad EWC:** los RFC mezclan versiones de OpenID4VP (Draft 18 a 24) y formatos (`vc+sd-jwt`, `dc+sd-jwt`, `vp+sd-jwt`). El núcleo sigue OpenID4VP 1.0 final.
- **PID mdoc:** el ejemplo de RFC002 usa el namespace `org.iso.18013.5.1`, que contradice al ARF (`eu.europa.ec.eudi.pid.1`). Seguir el ARF.

## 9. Pendiente

- Pasada sobre verifiers reales en la UE (blueprint de edad, pilotos, empresas públicas y privadas), no hecha todavía.
- Los PDF de `payment-rfcs/implementation-guides/` y los esquemas JSON (`eudi-wallet-rulebooks-and-schemas`) no se han revisado.
- Los análisis de código y de los RFC vienen de informes de agentes de exploración y no se han verificado archivo por archivo.

## 10. Resultados de la Fase 0 (2026-10-01)

Entorno: JDK 21 del sistema. Gradle descargó solo Gradle 9.6.1 y JDK 25 gracias al plugin foojay. No hizo falta instalar nada a mano.

| Paso | Comando | Resultado |
|---|---|---|
| Compilar | `cd core && ./gradlew bootJar -x test` | OK en 37 s. JAR de 87 MB (los tests no se ejecutaron) |
| Arrancar | `cd core && ./gradlew bootRun --args='--spring.profiles.active=develop'` | OK en unos 2 s. Swagger en `http://localhost:8080/swagger-ui` |
| Crear transacción de edad (`age_over_18`, PID mdoc, `intended_use_id=1`) | `POST /ui/presentations` | 200. `client_id` `x509_hash:...`, `request_uri` por GET |
| Recuperar Request Object | `GET /wallet/request.jwt/{id}` | 200, `application/oauth-authz-req+jwt`. JWT ES512 con cadena `x5c` de 2 certificados, `response_mode=direct_post.jwt`, DCQL correcto, `verifier_info` con el certificado de registro y clave de cifrado ECDH-ES |
| Respuesta inválida de wallet | `POST` a `response_uri` con `response=not.a.jwe` | 400 `InvalidEncryptedResponse`. La transacción sigue sin enviarse |

**No se probó el flujo completo.** No hay wallet ni credencial reales, así que no se verificó una presentación de `age_over_18`, ni la validación de mdoc, ni el estado final. Falta una wallet de pruebas (por ejemplo la de referencia de la UE) o un simulador.

### Hallazgos
1. Sin certificado de registro la petición falla con `MissingRegistrationCertificate` (400). Hace falta `intended_use_id` o `registration_certificate`.
2. El `intended_use_id=1` del perfil `develop` declara claims de PID, pero `age_over_18` no figura entre ellos y el backend aceptó la petición igualmente. Parece que no se comprueba que el DCQL esté dentro de los atributos registrados. Hay que verificarlo en el código. Si se confirma, es una validación que debemos añadir.
3. El esquema del QR es `haip-vp://` aunque el perfil de la transacción sea `openid4vp`.
4. El `GET` de estado devuelve `PresentationNotSubmitted` (400) mientras no hay respuesta, un error HTTP para un estado normal. Complica un polling limpio.
5. Leer el resultado antes de tiempo se registra como evento de fallo ("Verifier failed to get wallet").

## 11. Esqueleto del proyecto nuevo (2026-10-01)

Decisión: proyecto nuevo con paquete `co.org.avance.verifier`, Java 25 y Spring Boot 4.1, reutilizando del `core` solo las piezas de protocolo. Detalle, estado verificado y pendientes en `generic-verifier/README.md`.

- Creados: `verifier-protocol` (copiado), `verifier-catalog`, `verifier-persistence` (solo SQL) y `verifier-api` (solo ping y seguridad mínima).
- Verificado: compila, 14 tests pasan y la app arranca.
- **Todavía no hay flujo de verificación en el proyecto nuevo.** Siguiente paso: wiring de `InitTransaction` y los endpoints `/wallet/*` en `verifier-api`, con almacenamiento en memoria primero, y portar `DeviceResponseValidatorTest`.
- Sin configurar aún: spotless (cabeceras de licencia), kover y dependency-check, que sí tiene el `core`.

## 12. Prueba con wallet real (2026-10-01)

Wallet: `co.org.avance.wallet.dev` en un Xiaomi POCO X3 Pro, conectado por `adb`. Contra el backend de referencia (`core`, perfil `develop`), con túnel público temporal (cloudflared) para tener HTTPS. El túnel ya está cerrado.

| Paso | Resultado |
|---|---|
| Enviar `haip-vp://` por `adb` sin fijar paquete | Android abre el **selector de apps** (varias wallets aceptan `haip-vp://`). La solicitud no llega a nuestro wallet. Usar `am start -p co.org.avance.wallet.dev` |
| `request_uri` con `http://localhost` por `adb reverse` | No se llegó a probar bien por el selector. Pendiente saber si el wallet acepta HTTP |
| `request_uri` HTTPS por túnel | El wallet **recupera el Request Object** (`Request object retrieved`) |
| Validación de la cadena del verifier | **Rechazada**: `Untrusted x5c`, `Path does not chain with any of the trust anchors` (hoja `CN=verifier`) |

**Bloqueo:** el wallet no confía en la cadena de prueba del upstream (`verifier` → `intermediate` → `root`). Es el comportamiento correcto. Para continuar hay que (a) añadir `docs/dev-certs/upstream-test-root-ca.pem` a los anchors del wallet, o (b) usar un certificado de acceso emitido por una CA que el wallet ya confíe.

Datos útiles del wallet (de su log): usa un proveedor de confianza ETSI (`relaxProfiles=true`, `relaxPkixRevocation=true`) y contiene PID en SD-JWT (`urn:eudi:pid:1`) y mdoc (`eu.europa.ec.eudi.pid.1`), más una credencial académica `urn:eudi:academic:credential:1` (candidata para el caso de estudios).

Hallazgo adicional: cada consulta de estado antes de que el wallet responda añade un evento "Verifier failed to get wallet" al log. Un sondeo de 2 minutos generó decenas. El polling debe poder distinguir "pendiente" de error.

## 13. Adaptador de confianza (2026-10-13)

Creado `generic-verifier/verifier-trust`: valida cadenas contra las listas de confianza (la libreta "quién puede emitir/pedir"), con firmante de lista fijado. 23 tests pasan en total, incluida una prueba contra la libreta real de la Comisión. Detalle y hallazgos en `generic-verifier/README.md`.

- **Todavía no conectado** al protocolo ni a `verifier-api`. Siguiente paso: usarlo en la validación de mdoc y SD-JWT VC en lugar de `Ignored`, con una libreta por caso de uso (PID, PubEAA, EAA).
- **Hallazgo:** el keystore de ejemplo del upstream tiene un `intermediate` que no es CA; no sirve para una wallet estricta aunque su raíz estuviera en la libreta.
- Material de estudio: `docs/ecosistema-eudi.html` (mapa visual de actores y libretas).
