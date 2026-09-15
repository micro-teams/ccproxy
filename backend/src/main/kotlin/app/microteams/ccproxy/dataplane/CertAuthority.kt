/*
 *  Description: Kotlin port of ccproxy_engine.py's gen_cert — CA-signed per-domain leaf certificate
 *               generation for MITM, replacing the Python engine's `openssl` shell-outs with
 *               BouncyCastle. Generates a 2048-bit RSA leaf key + self-built cert signed by the
 *               mounted CA (same env-var-equivalent config: caCertPath/caKeyPath), caches per-domain
 *               key+cert to disk (certsDir) so each domain's cert is only ever generated once — and
 *               caches the resulting SSLContext in memory so repeat MITM connections to the same
 *               domain (the whole point: only api.anthropic.com / platform.claude.com) don't reload
 *               from disk either.
 *
 *               Design note: unlike a generic SNI-serving reverse proxy, this engine already knows
 *               the target host from the CONNECT line BEFORE the TLS handshake starts (the client's
 *               HTTPS_PROXY CONNECT target), so a per-connection SSLContext built for that exact host
 *               is sufficient — no SNI-dispatching KeyManager/Netty SniHandler is needed, matching
 *               Python's own per-accept `ctx.load_cert_chain(cf, kf)`. This keeps the implementation
 *               on plain javax.net.ssl / java.net sockets without adding Netty as a dependency.
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package app.microteams.ccproxy.dataplane

import java.io.File
import java.io.FileReader
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class CertAuthority(
    private val caCertPath: String,
    private val caKeyPath: String,
    private val certsDir: String,
) {
    companion object {
        init {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        }
    }

    private val genLock = ReentrantLock()
    private val contextCache = ConcurrentHashMap<String, SSLContext>()

    private val caCert: X509Certificate by lazy {
        PEMParser(FileReader(caCertPath)).use { p ->
            val holder = p.readObject() as org.bouncycastle.cert.X509CertificateHolder
            JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
        }
    }

    private val caKey: PrivateKey by lazy {
        PEMParser(FileReader(caKeyPath)).use { p ->
            val obj = p.readObject()
            val converter = JcaPEMKeyConverter().setProvider("BC")
            when (obj) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(obj)
                else -> throw IllegalStateException("unrecognized CA key format at $caKeyPath")
            }
        }
    }

    /**
     * Get (or lazily generate+cache) an SSLContext presenting a CA-signed leaf cert for [domain].
     */
    fun contextFor(domain: String): SSLContext =
        contextCache.computeIfAbsent(domain) { buildContext(it) }

    private fun buildContext(domain: String): SSLContext {
        val (key, certChain) = leafFor(domain)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        val password = "changeit".toCharArray()
        ks.setKeyEntry("leaf", key, password, arrayOf(certChain, caCert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        return ctx
    }

    /** Generate (or load from disk cache) the leaf key+cert pair for [domain]. */
    private fun leafFor(domain: String): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(certsDir, "$domain.key.der")
        val certFile = File(certsDir, "$domain.crt.der")
        genLock.lock()
        try {
            if (keyFile.exists() && certFile.exists()) {
                val kf = java.security.KeyFactory.getInstance("RSA")
                val key =
                    kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert =
                    java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(certFile.inputStream()) as X509Certificate
                return key to cert
            }
            File(certsDir).mkdirs()
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val now = Date()
            val notAfter = Date(now.time + 365L * 24 * 3600 * 1000)
            val serial = BigInteger(64, SecureRandom())
            val issuer = X500Name(caCert.subjectX500Principal.name)
            val subject = X500Name("CN=$domain")
            val builder: X509v3CertificateBuilder =
                JcaX509v3CertificateBuilder(issuer, serial, now, notAfter, subject, kp.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(caKey)
            val holder = builder.build(signer)
            val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(cert.encoded)
            return kp.private to cert
        } finally {
            genLock.unlock()
        }
    }
}
