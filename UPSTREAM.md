# Origen de lo copiado (para seguir el upstream)

Copiado el 2026-10-01 desde `/home/geovani/Workspace/Wallet/Verifier Apps and Services`, sin el directorio `.git`.

| Carpeta local | Origen | Commit | Licencia |
|---|---|---|---|
| `core/` | https://github.com/eu-digital-identity-wallet/eudi-srv-verifier-endpoint | `11a091f` (0.12.1-SNAPSHOT) | Apache 2.0 |
| `ui/` | https://github.com/eu-digital-identity-wallet/eudi-web-verifier | `99bb2fa` (0.13.1-SNAPSHOT) | Apache 2.0 |
| `docs/references/` (RFC EWC) | https://github.com/EWC-consortium/eudi-wallet-rfcs | no registrado | Apache 2.0 (RFC007 y RFC008: CC BY 4.0, atribuir) |

Para integrar cambios futuros: clonar el upstream en otro directorio, comparar con `git diff <commit> HEAD` y aplicar sobre `core/` o `ui/`. Anotar aquí cada rebase.

## Archivos de desarrollo que NO son para producción
- `core/docker/keystore.jks`, `core/docker/haproxy.pem`, `core/src/main/resources/keystore.jks`: certificados de prueba del upstream.
- `core/src/main/resources/trusted-issuers.jks`: no verificado si se usa.
- `core/src/main/resources/application-develop.properties`: perfil solo local, con credenciales y tipos de ejemplo.
- `ui/.env`: apunta a `http://localhost:8080`.

## No copiado a propósito
- `eudi-srv-statuslist-py`: lado emisor.
- `eudi-srv-web-relyingparty-registration-py`: registro de RP, requiere EJBCA y MariaDB. Solo para pruebas de WRPAC/WRPRC si hacen falta.
- RFC001, 004, 005, 006, 010 y 011 de EWC: emisión, atestaciones de wallet, LPID, firma y recibos.

## Código de `core/` reutilizado en `generic-verifier/` (2026-10-01)
- `domain/`, `port/` y `adapter/out/{cfg,consultation,digest,encoding,jose,json,mso,presentation,qrcode,sdjwtvc,tokenstatuslist,utils,x509}` → `generic-verifier/verifier-protocol`, con el paquete renombrado de `eu.europa.ec.eudi.verifier.endpoint` a `co.org.avance.verifier.protocol`. Resto del código sin modificar. Cabeceras de licencia de la Comisión Europea conservadas (Apache 2.0).
- Origen: `core/` = commit `11a091f`. Los arreglos del upstream posteriores a ese commit hay que portarlos a mano.
- Tests copiados: ExamplesTest, QesApproval/QesRequestSerializationTest, SdJwtVcValidationErrorCodeTOTest y los recursos JSON de `src/test/resources`.
