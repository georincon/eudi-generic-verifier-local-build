#!/usr/bin/env bash
#
# Prepara lo necesario para `docker compose up`. Es idempotente: no sobrescribe nada que ya exista.
#   1) .env a partir de .env.example
#   2) PKI del verifier (carnet WRPAC, registrador) y certificado de registro (WRPRC)
#   3) contrasena de la base de datos (solo se usa con --profile persistencia)
# No imprime contrasenas ni claves.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
HOST="${VERIFIER_PUBLIC_HOST:-civica-desarrollo.avance.org.co}"

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "==> .env creado (revise VERIFIER_PUBLICURL)"
else
  echo "==> .env ya existe, no se toca"
fi

umask 077
mkdir -p secretos
chmod 700 secretos
if [[ ! -f secretos/pki/access-root.key ]]; then
  VERIFIER_PUBLIC_HOST="$HOST" ./scripts/generar-pki-verifier.sh >/dev/null
  echo "==> PKI del verifier generada"
else
  echo "==> PKI ya existe, no se regenera (regenerarla invalidaria lo publicado en las listas de confianza)"
fi

if [[ ! -f pki/wrprc.jwt ]]; then
  python3 ./scripts/generar-wrprc.py secretos/pki pki/wrprc.jwt "$HOST"
else
  echo "==> WRPRC ya existe, no se regenera"
fi

if [[ ! -f secretos/db.pass ]]; then
  openssl rand -base64 24 | tr -d '\n=+/' > secretos/db.pass
  chmod 600 secretos/db.pass
  echo "==> contrasena de BD generada"
fi

echo
echo "Listo. Siguiente:  docker compose up -d --build"
echo "Con Postgres:      docker compose --profile persistencia up -d --build"
echo "Publicar la raiz del carnet en la lista WRPAC (parte publica):  pki/verifier-access-root.crt"
