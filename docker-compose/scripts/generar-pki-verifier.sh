#!/usr/bin/env bash
#
# PKI de avance-verifier, el verificador de Corporacion Avance (patron de generar-ca-uis.sh de eudi-generic-issuer-local-build):
#
#   1) Avance Verifier Access CA  (raiz, CA:TRUE)  ->  carnet del verifier (WRPAC, CA:FALSE, con SAN)
#      El carnet firma el Request Object que el wallet valida. Solo la RAIZ (pki/verifier-access-root.crt)
#      debe publicarse en la lista WRPAC del tl-publisher.
#   2) Avance RP Registrar Root CA (raiz, CA:TRUE) -> Avance RP Registrar (CA:FALSE)   [registrador EMULADO, de pruebas]
#      Firma el certificado de registro (WRPRC) del verifier. Solo la RAIZ (pki/rp-registrar-root.crt)
#      iria en la lista WRPRC, y unicamente si el wallet exige comprobacion de registro.
#
# Las claves privadas y las contrasenas quedan en docker-compose/secretos/pki/ (permisos 700/600,
# gitignored). NUNCA se publican ni se imprimen. Si ya existe una PKI, el script no la sobrescribe
# salvo que se use --force (eso invalida lo que ya se haya publicado en las listas de confianza).
#
# Uso:  VERIFIER_PUBLIC_HOST=civica-desarrollo.avance.org.co ./generar-pki-verifier.sh [--force]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS="$ROOT/secretos/pki"
PUBLIC="$ROOT/pki"
HOST="${VERIFIER_PUBLIC_HOST:-civica-desarrollo.avance.org.co}"
FORCE="${1:-}"

ACCESS_ROOT_SUBJECT="/C=CO/O=Corporacion Avance/CN=Avance Verifier Access CA"
ACCESS_LEAF_SUBJECT="/C=CO/O=Corporacion Avance/CN=Avance Verifier"
REG_ROOT_SUBJECT="/C=CO/O=Corporacion Avance/CN=Avance RP Registrar Root CA (pruebas)"
REG_LEAF_SUBJECT="/C=CO/O=Corporacion Avance/CN=Avance RP Registrar (pruebas)"

if [[ -f "$SECRETS/access-root.key" && "$FORCE" != "--force" ]]; then
  echo "Ya existe una PKI en $SECRETS. No se sobrescribe (use --force, e invalidara lo publicado)." >&2
  exit 1
fi

umask 077
mkdir -p "$SECRETS" "$PUBLIC"
chmod 700 "$SECRETS"
cd "$SECRETS"

# contrasena aleatoria del keystore: se guarda en un archivo 600, no se imprime
openssl rand -base64 24 | tr -d '\n=+/' > keystore.pass
KSPASS="$(cat keystore.pass)"

gen_ec() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1"; }

echo "==> 1/2 Avance Verifier Access CA (raiz, 10 anios) y carnet del verifier (2 anios)"
gen_ec access-root.key
openssl req -new -x509 -key access-root.key -sha256 -days 3650 -out access-root.crt \
  -subj "$ACCESS_ROOT_SUBJECT" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash"

gen_ec access-leaf.key
openssl req -new -key access-leaf.key -out access-leaf.csr -subj "$ACCESS_LEAF_SUBJECT"
openssl x509 -req -in access-leaf.csr -CA access-root.crt -CAkey access-root.key -CAcreateserial \
  -days 730 -sha256 -out access-leaf.crt \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nsubjectAltName=DNS:%s,DNS:localhost\nauthorityKeyIdentifier=keyid\nsubjectKeyIdentifier=hash" "$HOST")
rm -f access-leaf.csr access-root.srl

echo "==> 2/2 Avance RP Registrar Root CA (raiz) y registrador (2 anios)"
gen_ec registrar-root.key
openssl req -new -x509 -key registrar-root.key -sha256 -days 3650 -out registrar-root.crt \
  -subj "$REG_ROOT_SUBJECT" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash"

gen_ec registrar-leaf.key
openssl req -new -key registrar-leaf.key -out registrar-leaf.csr -subj "$REG_LEAF_SUBJECT"
openssl x509 -req -in registrar-leaf.csr -CA registrar-root.crt -CAkey registrar-root.key -CAcreateserial \
  -days 730 -sha256 -out registrar-leaf.crt \
  -extfile <(printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nauthorityKeyIdentifier=keyid\nsubjectKeyIdentifier=hash")
rm -f registrar-leaf.csr registrar-root.srl

echo "==> Keystore PKCS12 del verifier (alias access_certificate, cadena hoja + raiz)"
openssl pkcs12 -export -inkey access-leaf.key -in access-leaf.crt -certfile access-root.crt \
  -name access_certificate -out verifier-keystore.p12 -passout file:keystore.pass

echo "==> Comprobando la cadena y las restricciones CA"
openssl verify -CAfile access-root.crt access-leaf.crt
openssl verify -CAfile registrar-root.crt registrar-leaf.crt
openssl x509 -in access-root.crt -noout -ext basicConstraints | tail -1
openssl x509 -in access-leaf.crt -noout -ext basicConstraints | tail -1

# publicos: solo certificados, nunca claves
cp access-root.crt "$PUBLIC/verifier-access-root.crt"
cp registrar-root.crt "$PUBLIC/rp-registrar-root.crt"
chmod 644 "$PUBLIC"/*.crt
chmod 600 "$SECRETS"/*

echo
echo "Listo. Para publicar en las listas de confianza (parte publica):"
for f in verifier-access-root.crt rp-registrar-root.crt; do
  printf "  %-28s sha256=%s\n" "$f" "$(openssl x509 -in "$PUBLIC/$f" -noout -fingerprint -sha256 | cut -d= -f2)"
done
echo "Privado (no compartir): $SECRETS"
