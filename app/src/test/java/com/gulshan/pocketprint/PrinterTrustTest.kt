package com.gulshan.pocketprint

import com.gulshan.pocketprint.ipp.PrinterTrust
import com.gulshan.pocketprint.ipp.UntrustedCertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Trust on first use, exercised against two real self-signed certificates
 * rather than mocks of one. Their fingerprints were computed by openssl
 * independently of the code under test, which is the point: if the app and
 * the printer's status page disagree about a fingerprint, the user cannot
 * make the comparison this whole feature rests on.
 */
class PrinterTrustTest {

    private val printer = certificate(PRINTER_PEM)
    private val other = certificate(OTHER_PEM)

    /** What the platform says about a self-signed certificate: no. */
    private val platformRefuses = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Trust anchor for certification path not found.")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val platformAccepts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `the fingerprint matches what openssl computed`() {
        assertEquals(PRINTER_SHA256, PrinterTrust.fingerprint(printer))
        assertEquals(OTHER_SHA256, PrinterTrust.fingerprint(other))
    }

    @Test
    fun `display form is what a printer status page shows`() {
        assertEquals(
            "F1:2F:FA:29:AD:F7:CB:1F:0F:70:8B:D7:3A:80:A9:0E:DB:20:DB:A9:6F:34:46:C5:0E:24:D1:6C:4F:6E:26:FB",
            PrinterTrust.display(PRINTER_SHA256),
        )
    }

    @Test
    fun `first sight of a self-signed certificate is refused, and the refusal names it`() {
        val trust = PrinterTrust.PinningTrustManager(platformRefuses, pin = null)
        try {
            trust.checkServerTrusted(arrayOf(printer), "RSA")
            fail("a certificate nobody vouched for was accepted")
        } catch (e: UntrustedCertificateException) {
            assertEquals(PRINTER_SHA256, e.fingerprint)
            assertTrue(e.subject, "CN=printer.local" in e.subject)
            assertFalse(e.changed)
            assertNull(e.pinned)
            assertTrue(e.message, "not trusted" in e.message!!)
            assertTrue("the platform's own refusal is kept as the cause", e.cause is CertificateException)
        }
    }

    @Test
    fun `the pinned certificate is accepted`() {
        val trust = PrinterTrust.PinningTrustManager(platformRefuses, pin = PRINTER_SHA256)
        trust.checkServerTrusted(arrayOf(printer), "RSA")
    }

    @Test
    fun `the pin is compared case-insensitively`() {
        val trust = PrinterTrust.PinningTrustManager(platformRefuses, pin = PRINTER_SHA256.uppercase())
        trust.checkServerTrusted(arrayOf(printer), "RSA")
    }

    @Test
    fun `a different certificate at a pinned printer is a change, not a first sight`() {
        val trust = PrinterTrust.PinningTrustManager(platformRefuses, pin = PRINTER_SHA256)
        try {
            trust.checkServerTrusted(arrayOf(other), "RSA")
            fail("a certificate other than the pinned one was accepted")
        } catch (e: UntrustedCertificateException) {
            assertTrue(e.changed)
            assertEquals(PRINTER_SHA256, e.pinned)
            assertEquals(OTHER_SHA256, e.fingerprint)
            assertTrue(e.message, "has changed" in e.message!!)
        }
    }

    @Test
    fun `a certificate the platform trusts needs no pin, and a wrong pin does not reject it`() {
        val trust = PrinterTrust.PinningTrustManager(platformAccepts, pin = OTHER_SHA256)
        trust.checkServerTrusted(arrayOf(printer), "RSA")
    }

    @Test
    fun `an empty chain is the platform's refusal, not a fingerprint of nothing`() {
        val trust = PrinterTrust.PinningTrustManager(platformRefuses, pin = null)
        try {
            trust.checkServerTrusted(emptyArray(), "RSA")
            fail()
        } catch (e: UntrustedCertificateException) {
            fail("there was no leaf to name")
        } catch (e: CertificateException) {
            assertTrue(e.message, e.message!!.contains("trust anchor", ignoreCase = true))
        }
    }

    @Test
    fun `a pinned leaf satisfies hostname verification, whatever the certificate calls itself`() {
        val never = HostnameVerifier { _, _ -> false }
        val pinned = PrinterTrust.PinningHostnameVerifier(never, PRINTER_SHA256)
        assertTrue(pinned.verify("192.168.1.50", session(printer)))
        assertFalse("a different leaf falls through to the real verifier", pinned.verify("192.168.1.50", session(other)))
        assertFalse("no pin, no shortcut", PrinterTrust.PinningHostnameVerifier(never, null).verify("printer.local", session(printer)))
    }

    @Test
    fun `the refusal is found through the handshake exception OkHttp wraps it in`() {
        val refusal = UntrustedCertificateException(PRINTER_SHA256, "CN=x", 0L, null)
        val handshake = SSLHandshakeException("Handshake failed").apply {
            initCause(CertificateException("wrapped").apply { initCause(refusal) })
        }
        assertSame(refusal, PrinterTrust.untrustedCause(handshake))
        assertNull(PrinterTrust.untrustedCause(SSLHandshakeException("some other failure")))
        assertNull(PrinterTrust.untrustedCause(null))
    }

    private fun session(leaf: X509Certificate): SSLSession =
        Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf<java.security.cert.Certificate>(leaf)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SSLSession

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate

    companion object {
        // openssl x509 -fingerprint -sha256, lowercased and stripped of colons.
        const val PRINTER_SHA256 =
            "f12ffa29adf7cb1f0f708bd73a80a90edb20dba96f3446c50e24d16c4f6e26fb"
        const val OTHER_SHA256 =
            "a3c34ac9418c729b406e63d1c5b44c9e01a29fe59124e17521cb42c69e075dc1"

        val PRINTER_PEM = """
-----BEGIN CERTIFICATE-----
MIIDAjCCAeqgAwIBAgIJAOVHzEUsB15BMA0GCSqGSIb3DQEBDAUAMC8xFTATBgNV
BAoTDFRlc3QgUHJpbnRlcjEWMBQGA1UEAxMNcHJpbnRlci5sb2NhbDAeFw0yNjA5
MDUwNjUwMTdaFw0zNjA5MDIwNjUwMTdaMC8xFTATBgNVBAoTDFRlc3QgUHJpbnRl
cjEWMBQGA1UEAxMNcHJpbnRlci5sb2NhbDCCASIwDQYJKoZIhvcNAQEBBQADggEP
ADCCAQoCggEBAJZpwYRuhA+uVGv1MXCR+O+3ztB6/SJ+Gfj3xVbsuPHaJm0SuHW6
77GKBVumjJIMZzt2iDpGolA4KA5/F/bkwGJTCBTqQkJj+JE0Zn1UVbWBr30axrzp
4a8jz/Yw+7MDjz5gFB5pMY4YRE2eSRCZrYkmIVoFxG5nVT2vVvC/pud5BsWNXgu6
176Z87CM9kIypuZcgRSlLn98bQZ5Jyjv7RkhvVF3yd0uxaICpfMRelOEAC6gN1cp
MSuj1rrcfuDLOWgTWCBU/78b+6zzuTT7yZcq6bGZaq8YI2ZQ50L1vHR8w8wUakhj
ara4r9jbtgdLE4gCHvUZmWXsEbmXMWGUwfMCAwEAAaMhMB8wHQYDVR0OBBYEFGqO
vaVwLGBS9j5lRdsa5Ju2lZlAMA0GCSqGSIb3DQEBDAUAA4IBAQCMWcevd57C3EwA
+9+Ti9VTSkpaIOYfZCfi2MuFJA8t/hE1Eh/7FojAramnMMRzg29Kd2MN5aXYFr/Y
2zsWOV19gmQhwAUdewTIzCvh7bhUMXDbUS2q6LwaE44VDxHQMVVtdXmrTdeeQ+Qy
VSDzOiiq6C/2j8FIpwOSqZ3Kc5+R3t27P1s4qp7c6ND2HC7lqWBqdYZYfnQ9QbI4
NhSfprdzekYzRfvWaTh6SyR1Gn4ZNinjFqA4gnen53Zo//gC0lsrG7JYfKtgg8Id
JjXA+nwkFuQykeYY3ld1UVLfCyAzchtad4pWU5WfYmK1giqxB74SrBzT800rL5Pj
LO62EWOI
-----END CERTIFICATE-----
        """.trimIndent()

        val OTHER_PEM = """
-----BEGIN CERTIFICATE-----
MIIC0DCCAbigAwIBAgIJAIfdaYC3ODcuMA0GCSqGSIb3DQEBDAUAMBYxFDASBgNV
BAMTC290aGVyLmxvY2FsMB4XDTI2MDkwNTA2NTAxOFoXDTM2MDkwMjA2NTAxOFow
FjEUMBIGA1UEAxMLb3RoZXIubG9jYWwwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw
ggEKAoIBAQCe2n5l627zNw0VYOCqqeI0kRoI0AYPSrUUq3D44uG2rL7PTkFGMfQY
c0gczrgNAam9iDquzWWGwYeF6/KjQffFsiZ0ZKj3jBIfppQKk6ZVuObOrjAsGPw0
TCWCixlKaek/CWyWTjhZIxbvsf2xhZCrt1ch+c+qEsB9ZHLDYDgSSIddjy0IO75r
eGhhXRYawllDgI7e0kSa5/xD06/J+DB1zTcpeUMyPTmXMJh5W5gMUufk6TTAeUUN
x5WcWp8ASch5Z7b6F/rpTWd8modWHfaomg5iyUNWoQ1FBVgtH+hdQvM25xHzBpQz
P67StMd6C8xJ+hslg5bBKoL37PPU43apAgMBAAGjITAfMB0GA1UdDgQWBBTl8kWA
a/HjJ5T9TAMFv5Cg1gDX9jANBgkqhkiG9w0BAQwFAAOCAQEABqRj0PpdtMSlRzLs
q7Z9MPiedoLG+GWiap644GDyQaBRYi3KEmJFFLgruaAAidSMImhhqXlkeEq8vxaY
qLcFBX3g+/kcjCcP96xdZ28C8/K7mSAlwJdOuY7gBOIA2ZKSO7c7/DWt0VcWkjO2
/FXIXSFH/jqwEK4/mZECkcGbQd21vMa61RtU3FCaStRdDNuzd8oMcyfvhsaBhNnL
gT9HalzgdFiy5op+n0a/6psQInUDCmuQ001lZvP/d0hqeEkqmkrElPpbqU0o3u9x
iWer0UTtfqiFIJY622kgKEAGnfLoTv1Ym+ik7Tx4v3sCiqY7U1IFzrWO8irdroT+
NXzt8Q==
-----END CERTIFICATE-----
        """.trimIndent()
    }
}
