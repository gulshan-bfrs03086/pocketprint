package com.gulshan.pocketprint.ipp

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A printer's TLS certificate was refused, and here is what it presented.
 *
 * Carries enough for a person to make the decision the trust store could not:
 * the fingerprint to compare against the printer's own status page, who the
 * certificate claims to be, and - when a pin already existed - what changed.
 * The message reads as a sentence because it ends up in job history and the
 * printer report, next to raw socket errors that do not.
 */
class UntrustedCertificateException(
    /** SHA-256 of the leaf's DER encoding, lowercase hex, no separators. */
    val fingerprint: String,
    val subject: String,
    val notAfterEpochMs: Long,
    /** The fingerprint that was trusted before, when this is a change rather than a first sight. */
    val pinned: String?,
) : CertificateException(
    if (pinned == null) {
        "The printer's certificate is not trusted by this device " +
            "(SHA-256 ${PrinterTrust.display(fingerprint)})"
    } else {
        "The printer's certificate has changed since it was trusted " +
            "(was ${PrinterTrust.display(pinned)}, now ${PrinterTrust.display(fingerprint)})"
    },
) {
    val changed: Boolean get() = pinned != null
}

/**
 * Trust on first use for IPPS printers.
 *
 * Printers ship certificates they signed themselves. No trust store accepts
 * those, so every IPPS request used to fail in the handshake, and a printer
 * that advertised only _ipps._tcp could not be used at all.
 *
 * The fix is not a trust manager that accepts everything. That switches
 * certificate validation off for the whole client, which is a real regression
 * on a phone that also talks to the internet, and it makes the decision for
 * the user without telling them there was one. Instead the platform's own
 * trust decision is tried first - a printer with a properly signed certificate
 * needs nothing from here - and only when that fails is the leaf compared to
 * the fingerprint the user chose to trust for this printer. No pin, or a
 * different leaf, is a refusal that names the fingerprint so the person can
 * check it against the printer itself. The choice is theirs; this only makes
 * it possible to choose.
 *
 * A pinned leaf also satisfies hostname verification. A self-signed printer
 * certificate names the printer however the firmware felt like naming it, and
 * once the user has vouched for the exact certificate, the certificate is the
 * identity.
 */
object PrinterTrust {

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    /** AB:CD:EF... - the form every printer status page and browser uses. */
    fun display(fingerprint: String): String =
        fingerprint.uppercase().chunked(2).joinToString(":")

    /** The nearest UntrustedCertificateException in a cause chain, or null. */
    fun untrustedCause(throwable: Throwable?): UntrustedCertificateException? {
        var cause = throwable
        repeat(12) {
            if (cause == null) return null
            if (cause is UntrustedCertificateException) return cause
            cause = cause?.cause
        }
        return null
    }

    /** A client that trusts what the platform trusts, plus the one pinned leaf. */
    fun secure(
        base: OkHttpClient,
        pin: String?,
        platform: X509TrustManager = platformTrustManager(),
    ): OkHttpClient {
        val trust = PinningTrustManager(platform, pin)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trust), null)
        }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(PinningHostnameVerifier(base.hostnameVerifier, pin))
            .build()
    }

    fun platformTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /**
     * Platform first, pin second, a named refusal otherwise.
     *
     * The platform decision is consulted every time rather than short-circuited
     * by the pin: a certificate the platform accepts today should keep working
     * if the printer is later given a properly signed one, and a pin should
     * never be the reason a valid chain is rejected.
     */
    class PinningTrustManager(
        private val platform: X509TrustManager,
        private val pin: String?,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val refusal = try {
                platform.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                e
            }

            val leaf = chain.firstOrNull() ?: throw refusal
            val presented = fingerprint(leaf)
            if (pin != null && presented.equals(pin, ignoreCase = true)) return

            throw UntrustedCertificateException(
                fingerprint = presented,
                subject = leaf.subjectX500Principal.name,
                notAfterEpochMs = leaf.notAfter.time,
                pinned = pin?.lowercase(),
            ).apply { initCause(refusal) }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    class PinningHostnameVerifier(
        private val fallback: HostnameVerifier,
        private val pin: String?,
    ) : HostnameVerifier {
        override fun verify(hostname: String, session: SSLSession): Boolean {
            if (pin != null) {
                val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }
                    .getOrNull()
                if (leaf != null && fingerprint(leaf).equals(pin, ignoreCase = true)) return true
            }
            return fallback.verify(hostname, session)
        }
    }
}
