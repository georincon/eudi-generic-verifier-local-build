# Plan: avance-verifier, el verificador de certificaciones académicas de Corporación Avance

Fecha: 2026-10-14. Sustituye al caso de edad, que queda **abandonado por ahora**.

## 1. Objetivo y alcance

**Contexto:** Corporación Avance contrata personal y necesita verificar las certificaciones académicas de las personas candidatas. Verifier de la empresa (relying party) que pide por QR una credencial académica `dc+sd-jwt` con `vct = urn:eudi:academic:credential:1` (la que emite el `uis-issuer`), la valida y muestra el resultado. Debe ser aceptado por el wallet `co.org.avance.wallet.dev`.

**Dentro del alcance:** carnet del verifier (CA propia), certificado de registro, adaptador de confianza para tus libretas, solicitud por QR, vistas con el diseño del `uis-issuer`, contenedores.

**Fuera del alcance (no lo toco sin tu orden):** el `tl-publisher`, el código de tu wallet, el `haproxy-uis`, el repo del emisor y el wallet provider. Donde haga falta un cambio ahí, lo dejo preparado como petición.

## 2. Cómo se relacionan las piezas (lo que ya verificamos)

| Pregunta | Quién responde | Estado |
|---|---|---|
| ¿Es de confianza el emisor (UIS)? | Nuestro verifier, con la libreta **PubEAA** (la raíz `UIS Issuer Root CA` figura ahí, sha256 `f1e15d56…`) | Libreta existe |
| ¿Es legítimo el verifier? | El wallet, con la libreta **WRPAC** | **No existe en tu publicador** ("lista desconocida") |
| ¿Qué puede pedir el verifier? | El wallet, con la libreta **WRPRC** y el certificado de registro | **No existe en tu publicador** |
| ¿Está revocada la credencial? | Nuestro verifier, con la lista de estado | **La credencial académica se emite sin referencia de estado** (análisis de código, sin probar) |

## 3. Piezas que hay que construir

1. **PKI del verifier** (script, patrón de tus `generar-ca-uis*.sh`):
   - `Civica Verifier Access CA` (raíz, CA=true, P-256) que firma el **carnet** (WRPAC) del verifier. Hoja con `SAN` del dominio público.
   - `Civica RP Registrar`: una hoja firmante que emite el **certificado de registro** (WRPRC, `rc-wrp+jwt`) que el protocolo exige en **toda** transacción.
2. **WRPRC** del verifier: nombre, finalidad ("verificar título académico"), y los atributos que puede pedir (solo los necesarios).
3. **Confianza**: lector del formato `tl+jwt` de tu publicador, con **firmante fijado** y comprobación de **frescura** (las listas caducan en 24 h), conectado a mdoc y SD-JWT VC en lugar del modo "confiar en todos".
4. **Flujo del verifier** en `verifier-api`: crear solicitud, entregar el Request Object, recibir la respuesta cifrada, consultar el estado.
5. **Caso de uso académico fijo** (el cliente no envía DCQL libre).
6. **Vistas** (Thymeleaf + Bootstrap, diseño del `uis-issuer`): inicio, QR con espera, resultado verificado, error.
7. **Contenedores**: carpeta `docker-compose/` con Postgres y la API, puertos que no chocan con tus stacks.

## 4. Solicitud académica (DCQL)

Se pide lo mínimo para "acreditar el título":

`issuing_institution_name`, `awarded_title`, `program_name`, `academic_status`, `graduation_date`, `family_name`, `given_name`.

**No se piden:** `accumulated_average`, `document_number`, `student_id`, `email`, `phone_number`.

Aviso (análisis de código del emisor, sin probar): 21 de 27 atributos viajan siempre en claro en el SD-JWT, así que el verifier los recibiría aunque no los pida. Eso se corrige en el emisor, no aquí. La pantalla de resultado solo muestra los pedidos.

## 5. Puertos y contenedores

Ocupados hoy en el host: 80, 443 (`haproxy-uis`), 5256, 5433, 5434, 5435, 5436, 8090, 8095, 8100, 8888, 9010.

| Servicio | Contenedor | Host | Interno |
|---|---|---|---|
| API del verifier | `verifier-api` | **8480** | 8080 |
| Postgres | `postgres-verifier` | **5437** | 5432 |
| Red | `verifier_default` | n/a | n/a |

`docker-compose/` contendrá: `docker-compose.yaml`, `.env.example`, `Dockerfile` del verifier, scripts de PKI y un fragmento de `haproxy` **sin aplicar** (ruta `/verifier` para publicarlo bajo el mismo dominio que los demás).

## 6. Fases

- [x] **F1. PKI y WRPRC** (scripts + artefactos locales, claves fuera de git). Carnet: `CN=Avance Verifier,O=Corporacion Avance`
- [x] **F2. Confianza**: lector `tl+jwt`, firmante fijado, frescura, pruebas con la lista real de PubEAA
- [x] **F3. Flujo del verifier** en `verifier-api` (persistencia en memoria). Probado con una **billetera simulada**, no con una real
- [x] **F4. Vistas** con el diseño del `uis-issuer` (sin la marca de la UIS)
- [x] **F5. Contenedores** (`docker-compose/`): `avance-verifier-api` corre sano; Postgres definido pero la app aún no lo usa
- [ ] **F6. Prueba con wallet real**, bloqueada hasta cumplir las dependencias de abajo
- [ ] **F7. Acceso del personal de selección** (hoy cualquiera con acceso a la URL puede generar solicitudes a nombre de Corporación Avance)

## 7. Dependencias que no dependen de mí

Para que **tu wallet acepte** a este verifier hacen falta, de quien administra el publicador y el wallet:

1. Una lista **WRPAC** en el `tl-publisher` con la raíz `Civica Verifier Access CA` (la entrego en `docker-compose/pki/`).
2. Una lista **WRPRC** con la raíz del registrador, **solo si** el wallet tiene activada la comprobación de registro (`isRegistrationCheckEnabled`, valor sin leer).
3. Que el wallet lea esas listas en `wrpacProviders` y `wrprcProviders` (hoy apunta a las de la Comisión).
4. Que la credencial académica lleve **referencia de estado** si queremos comprobar revocación.

Sin 1 y 3, el wallet rechazará al verifier igual que en la prueba del 1 de octubre.

## 8. Riesgos y huecos

- El formato `tl+jwt` de tu publicador no es el que lee la librería oficial: se necesita lector propio.
- El wallet no se ha probado leyendo tus listas con este verifier.
- La firma de la lista de estado no se verifica aún en el protocolo copiado.
- La librería oficial de confianza es alpha.
- Claves privadas de la PKI: nunca al repositorio, permisos 600.
