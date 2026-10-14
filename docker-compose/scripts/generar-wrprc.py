#!/usr/bin/env python3
"""Genera el certificado de registro (WRPRC, JWT `rc-wrp+jwt`) de avance-verifier (Corporacion Avance).

El protocolo del verifier exige un WRPRC en TODA transaccion. Aqui se emula al registrador:
lo firma `Avance RP Registrar (pruebas)` (ver generar-pki-verifier.sh). Es un registrador de pruebas, no
un organismo oficial.

Solo se declaran los atributos que el verifier necesita para acreditar un titulo; no se piden
promedio, documento, codigo estudiantil, correo ni telefono.

Uso: generar-wrprc.py <dir_secretos_pki> <archivo_salida> [host_publico]
Requiere el paquete `cryptography`.
"""
import base64
import json
import sys
import time
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

VCT = "urn:eudi:academic:credential:1"
CLAIMS = [
    "issuing_institution_name",
    "awarded_title",
    "program_name",
    "academic_status",
    "graduation_date",
    "family_name",
    "given_name",
]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main() -> None:
    secrets = Path(sys.argv[1])
    out = Path(sys.argv[2])
    host = sys.argv[3] if len(sys.argv) > 3 else "civica-desarrollo.avance.org.co"
    base = f"https://{host}/verifier"

    cert = x509.load_pem_x509_certificate((secrets / "registrar-leaf.crt").read_bytes())
    key = serialization.load_pem_private_key((secrets / "registrar-leaf.key").read_bytes(), password=None)
    assert isinstance(key, ec.EllipticCurvePrivateKey) and key.curve.name == "secp256r1"
    leaf_der = cert.public_bytes(serialization.Encoding.DER)

    header = {"typ": "rc-wrp+jwt", "alg": "ES256", "x5c": [base64.b64encode(leaf_der).decode()]}
    payload = {
        "name": "Corporacion Avance - avance-verifier",
        # Identificador de PRUEBA: no es un identificador legal real.
        "sub": "AVANCE-TEST-VERIFIER-001",
        "sub_ln": "Corporacion Avance",
        "country": "CO",
        "entitlements": ["https://uri.etsi.org/19475/Entitlement/Service_Provider"],
        "policy_id": ["0.4.0.19475.3.1"],
        "purpose": [{"lang": "es", "value": "Verificar las certificaciones academicas de las personas candidatas a un cargo en Corporacion Avance (proceso de contratacion)"}],
        "credentials": [
            {
                "format": "dc+sd-jwt",
                "meta": {"vct_values": [VCT]},
                "claim": [{"path": [c]} for c in CLAIMS],
            }
        ],
        "registry_uri": f"{base}/registry",
        "certificate_policy": f"{base}/certificate-policy",
        "privacy_policy": f"{base}/privacy",
        "info_uri": base,
        "support_uri": f"{base}/support",
        "srv_description": [{"lang": "es", "value": "Verificacion, con autorizacion de la persona candidata, de su titulo academico para un proceso de seleccion. No se conservan los datos."}],
        "iat": int(time.time()),
    }

    signing_input = f"{b64url(json.dumps(header, separators=(',', ':')).encode())}.{b64url(json.dumps(payload, separators=(',', ':'), ensure_ascii=False).encode())}"
    r, s = decode_dss_signature(key.sign(signing_input.encode(), ec.ECDSA(hashes.SHA256())))
    signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")  # formato JWS (r||s), no DER

    out.write_text(f"{signing_input}.{b64url(signature)}\n")
    out.chmod(0o644)  # el WRPRC no es secreto: viaja dentro de cada solicitud
    print(f"WRPRC escrito en {out} ({len(CLAIMS)} atributos permitidos, vct {VCT})")


if __name__ == "__main__":
    main()
