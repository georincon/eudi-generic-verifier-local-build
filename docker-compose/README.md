# docker-compose: avance-verifier

Levanta **avance-verifier**, el verificador con el que Corporación Avance comprueba las certificaciones académicas de las personas candidatas en sus procesos de contratación (`avance-verifier-api`) y, opcionalmente, su Postgres.

## Uso

```bash
cd docker-compose
./scripts/preparar-entorno.sh        # una sola vez: .env, PKI, certificado de registro, contraseña de BD
docker compose up -d --build         # verificador
docker compose --profile persistencia up -d --build   # además Postgres (la app aún NO lo usa)
```

Abrir `http://localhost:8480/`. Salud: `http://localhost:8480/actuator/health`.

## Puertos

Elegidos para no chocar con los stacks que ya corren (80/443 `haproxy-uis`, 5256, 5433 a 5436, 8090, 8095, 8100, 9010).

| Servicio | Contenedor | Anfitrión | Interno |
|---|---|---|---|
| Verificador | `avance-verifier-api` | `127.0.0.1:8480` | 8080 |
| Postgres (perfil `persistencia`) | `postgres-avance-verifier` | `127.0.0.1:5437` | 5432 |

Ambos escuchan solo en `127.0.0.1` por defecto. Se cambia con `VERIFIER_BIND_ADDRESS` en `.env`.
Red propia `avance-verifier_default`. No se conecta a ningún otro stack salvo que se use el override del haproxy (abajo).

## Qué crea `preparar-entorno.sh`

| Archivo | Contenido | ¿Secreto? |
|---|---|---|
| `.env` | Puertos y URL pública | No, pero está en `.gitignore` |
| `secretos/pki/*.key`, `verifier-keystore.p12`, `keystore.pass` | Claves privadas, keystore del carnet y su contraseña | **Sí** (600/700, nunca al repositorio) |
| `secretos/db.pass` | Contraseña de la BD | **Sí** |
| `pki/verifier-access-root.crt` | Raíz del carnet del verifier | No: **es lo que se publica en la lista WRPAC** |
| `pki/rp-registrar-root.crt` | Raíz del registrador de pruebas | No: iría en la lista WRPRC |
| `pki/wrprc.jwt` | Certificado de registro (WRPRC) | No: viaja dentro de cada solicitud |

Las contraseñas se pasan por **docker secrets** (archivos montados), no por variables de entorno.
El script no sobrescribe una PKI existente: regenerarla invalidaría lo ya publicado en las listas de confianza.

## Publicarlo bajo el dominio compartido (opcional)

1. En `.env`: `VERIFIER_PUBLICURL=https://civica-desarrollo.avance.org.co/verifier`.
2. Unir el contenedor a la red del haproxy:
   `docker compose -f docker-compose.yaml -f docker-compose.haproxy.yaml up -d`
3. Quien administre `haproxy-uis` debe aplicar `haproxy/verifier.snippet.cfg`. **No lo apliqué**: es configuración de otro stack.

## Qué hace falta para que una wallet acepte este verificador

Que levante bien **no basta**. La wallet debe confiar en el carnet del verifier:

1. Publicar `pki/verifier-access-root.crt` en una lista **WRPAC** del `tl-publisher`. Hoy esa lista responde "lista desconocida".
2. Que la wallet lea esa lista en `wrpacProviders` (hoy apunta a la de la Comisión).
3. Solo si la wallet exige comprobación de registro: publicar `pki/rp-registrar-root.crt` en una lista WRPRC y apuntar `wrprcProviders`.

Mientras no se cumpla 1 y 2, la wallet rechazará la solicitud ("no pudo verificar a la parte receptora").

## Seguridad del contenedor

Sin privilegios (`cap_drop: ALL`, `no-new-privileges`), sistema de archivos de solo lectura con `/tmp` en memoria, usuario no root.
Corre con el UID del anfitrión (`LOCAL_UID`/`LOCAL_GID`, 1000 por defecto) para poder leer los secretos 600.

## Limitaciones

- **El Postgres todavía no lo usa la aplicación**: las presentaciones están en memoria y se pierden al reiniciar. Hay un esquema borrador en `generic-verifier/verifier-persistence`.
- El `healthcheck` solo comprueba que el puerto acepta conexiones.
- Las listas de confianza se descargan del `tl-publisher` público; si no es accesible desde el contenedor, no habrá confianza en ningún emisor (falla cerrado).
