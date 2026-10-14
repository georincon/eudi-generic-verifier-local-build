package co.org.avance.verifier.protocol.domain

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contrato entre el generador (docker-compose/scripts/generar-wrprc.py) y el parser del protocolo.
 * Se omite si el WRPRC todavia no se ha generado en este equipo.
 */
class GeneratedRegistrationCertificateTest {

    private val wrprc: Path = Path.of("../../docker-compose/pki/wrprc.jwt")

    @Test
    fun `el WRPRC generado por el script lo acepta el parser del protocolo`() {
        assumeTrue(Files.exists(wrprc), "wrprc.jwt no generado: ejecute docker-compose/scripts/generar-pki-verifier.sh")

        val parsed = RegistrationCertificate.parse(Files.readString(wrprc).trim())

        val claims = parsed.value.jwtClaimsSet
        assertEquals("CO", claims.getStringClaim("country"))
        val credentials = claims.getListClaim("credentials")
        assertEquals(1, credentials.size)
    }
}
