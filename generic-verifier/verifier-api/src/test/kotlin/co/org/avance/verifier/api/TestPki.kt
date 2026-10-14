package co.org.avance.verifier.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.util.Date

/** PKI de prueba bien formada (raíz CA -> hoja), keystore PKCS12 y WRPRC, en un directorio temporal. */
class TestPki(dir: Path) {
    val password = "test-pass"
    val keystore: Path = dir.resolve("verifier-keystore.p12")
    val wrprc: Path = dir.resolve("wrprc.jwt")
    val leaf: X509Certificate

    init {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val rootKp = gen.generateKeyPair()
        val leafKp = gen.generateKeyPair()
        val root = issue("CN=test-root", rootKp.public, "CN=test-root", rootKp.private, ca = true)
        leaf = issue("CN=test-verifier", leafKp.public, "CN=test-root", rootKp.private, ca = false)

        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("access_certificate", leafKp.private, password.toCharArray(), arrayOf(leaf, root))
            Files.newOutputStream(keystore).use { store(it, password.toCharArray()) }
        }
        Files.writeString(wrprc, registrationCertificate(leafKp))
    }

    private fun registrationCertificate(kp: KeyPair): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType("rc-wrp+jwt")).x509CertChain(listOf(Base64.encode(leaf.encoded))).build()
        val payload = """{"name":"Verificador de prueba","country":"CO","credentials":[{"format":"dc+sd-jwt","meta":{"vct_values":["urn:eudi:academic:credential:1"]},"claim":[{"path":["awarded_title"]}]}],"iat":${System.currentTimeMillis() / 1000}}"""
        return JWSObject(header, Payload(payload)).apply { sign(ECDSASigner(kp.private as ECPrivateKey)) }.serialize()
    }

    private fun issue(subject: String, key: PublicKey, issuer: String, issuerKey: PrivateKey, ca: Boolean): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name(subject), key)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)))
    }
}
