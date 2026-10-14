# Borrador: qué hace falta en el `tl-publisher` y en la wallet para probar avance-verifier

> Borrador para quien administra el publicador de listas y la wallet. **No se ha enviado a nadie.**

avance-verifier (Corporación Avance) ya corre y su solicitud está bien formada, pero la wallet de desarrollo
la rechaza con "no pudo verificar a la parte receptora" porque el carnet del verifier no llega a ninguna CA que la wallet conozca.

## 1. En el `tl-publisher`: una lista WRPAC

Hoy `GET /tl-publisher/TL/WRPAC/current.json` responde `{"detail":"lista desconocida"}` (igual `WRPRC`).

Se necesita una lista **WRPAC**, con el mismo formato y firmante que las de `PID`, `PUBEAA` y `WP`, con un servicio `granted` cuyo certificado sea:

| Archivo | Sujeto | SHA-256 |
|---|---|---|
| `docker-compose/pki/verifier-access-root.crt` | `CN=Avance Verifier Access CA, O=Corporacion Avance, C=CO` | `EA:F3:4B:FE:F8:98:C4:A8:E2:F8:F9:67:E7:B0:E5:A2:E0:22:A7:EE:04:42:FC:E3:F3:B3:CF:F8:46:F0:E7:70` |

Es la **raíz** (autofirmada, `CA:TRUE`); el carnet (hoja) firma las solicitudes y viaja en el `x5c`, la raíz no.
Solo debe publicarse ese `.crt` (público). Las claves privadas están en `docker-compose/secretos/` y no deben salir de ahí.

## 2. En la wallet (flavor `dev`, `WalletCoreConfigImpl.kt`)

`wrpacProviders` apunta hoy a `https://trustedlist.serviceproviders.eudiw.dev/LOTE/json/WRPACProviders.jwt` (Comisión).
Debe apuntar a la lista del punto 1, por ejemplo `https://civica.avance.org.co/tl-publisher/TL/WRPAC/current.json`.

**Pendiente de verificar por quien conozca la wallet:** si su librería lee el formato `tl+jwt` del publicador
(no es el LoTE de la Comisión). Con PID/PubEAA/WP ya apuntando al publicador, parece que sí, pero no lo comprobé.

## 3. Solo si la wallet exige comprobación de registro

Si `isRegistrationCheckEnabled` está activo (no leí su valor), publicar en una lista **WRPRC** la raíz
`docker-compose/pki/rp-registrar-root.crt` (`CN=Avance RP Registrar Root CA (pruebas), …`, SHA-256
`A9:7A:FF:EB:76:F6:79:6C:22:1D:D0:4D:60:43:B6:C2:10:9F:B4:02:38:62:B2:87:6A:39:8E:64:F0:AB:C8:0A`)
y apuntar `wrprcProviders` a ella. Ese registrador es de pruebas y lo emulamos nosotros.

## 4. Opcional, mejora la privacidad y la revocación (emisor `uis-issuer`)

Del análisis de código (sin probar en ejecución):
- 21 de 27 atributos de la credencial académica se emiten **en claro**; solo 6 son de divulgación selectiva. Un verifier los recibe aunque no los pida.
- La credencial se emite **sin referencia a lista de estado**, así que no se puede revocar ni comprobar.

## Cómo comprobar que quedó bien

1. `curl -s https://civica.avance.org.co/tl-publisher/TL/WRPAC/current.json | head -c 40` debe devolver un JWT (`eyJ…`), no el error.
2. Con avance-verifier arriba, abrir `/verify`, generar el QR y escanearlo con la wallet: debe mostrar la pantalla de consentimiento en vez de "Presentación bloqueada".
