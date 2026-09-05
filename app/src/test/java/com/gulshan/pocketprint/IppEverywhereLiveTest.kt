package com.gulshan.pocketprint

import com.gulshan.pocketprint.ipp.IppCapabilityMapper
import com.gulshan.pocketprint.ipp.IppClient
import com.gulshan.pocketprint.ipp.IppJobState
import com.gulshan.pocketprint.ipp.IppResponse
import com.gulshan.pocketprint.ipp.IppValue
import com.gulshan.pocketprint.ipp.PrinterTrust
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.render.PwgRasterEncoder
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The IPP client against a real IPP server: ippeveprinter, the IPP Everywhere
 * reference implementation that ships with CUPS. Not a mock of one. It
 * negotiates versions, validates media against what it supports, moves jobs
 * through pending, processing and completed, and stores exactly the bytes it
 * was sent - which is what makes "the page is not garbled" checkable here.
 *
 * What it is not is a physical printer, and the two things only hardware can
 * show are stated in the README rather than claimed: that mDNS discovery on
 * Android finds it, and what a print head does with the raster.
 *
 * Skipped, visibly, when ippeveprinter is not installed. Any other failure is
 * a failure: a server that is present but will not start is a signal, not a
 * reason to look away.
 */
class IppEverywhereLiveTest {

    companion object {
        private lateinit var server: Emulator

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val binary = Emulator.locate()
            Assume.assumeTrue(
                "ippeveprinter is not installed (apt: cups-ipp-utils; on macOS it ships with the OS)",
                binary != null,
            )
            server = Emulator.start(binary!!, tls = false)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            if (::server.isInitialized) server.close()
        }
    }

    private val client = IppClient()
    private val address get() = server.address

    private fun attributes(): IppResponse = runBlocking { client.getPrinterAttributes(address) }

    private fun supportedMedia(): List<String> =
        attributes().printerGroup()?.get("media-supported")?.asStrings().orEmpty()

    private fun supportedResolutions(): List<Int> =
        IppCapabilityMapper.toCapabilities(attributes()).resolutionsDpi

    // ---- 1. capabilities ----------------------------------------------------

    @Test
    fun `get-printer-attributes returns sane capabilities`() {
        val response = attributes()
        assertTrue(response.statusText, response.isSuccess)

        val caps = IppCapabilityMapper.toCapabilities(response)
        assertTrue("$caps", PrintLanguage.PDF in caps.languages)
        assertTrue("$caps", PrintLanguage.PWG_RASTER in caps.languages)
        assertTrue("$caps", caps.mediaSizes.any { it.id == MediaSize.A4.id })
        assertTrue("$caps", caps.mediaSizes.any { it.id == MediaSize.LETTER.id })
        assertTrue("$caps", 600 in caps.resolutionsDpi)
        assertEquals(999, caps.maxCopies)

        assertTrue(IppCapabilityMapper.isAcceptingJobs(response))
        assertEquals("printer-state idle is enum 3", 3, IppCapabilityMapper.printerState(response))
        val makeAndModel = IppCapabilityMapper.makeAndModel(response)
        assertTrue("$makeAndModel", makeAndModel?.contains(Emulator.MODEL) == true)
    }

    // ---- 2. PDF, end to end -------------------------------------------------

    @Test
    fun `print-job with application-pdf completes, and the server holds the exact bytes sent`() {
        val pdf = resource("one-page-rectangle.pdf")
        val media = supportedMedia()
        val options = PrintOptions(mediaSize = MediaSize.A4, dpi = 300)

        val validated = runBlocking {
            client.validateJob(address, "live pdf", PrintLanguage.PDF, options, media, supportedResolutions())
        }
        assertTrue(validated.explain(), validated.isSuccess)

        val submitted = runBlocking {
            client.printJob(address, "live pdf", PrintLanguage.PDF, options, media, pdf.size.toLong(), supportedResolutions = supportedResolutions()) {
                pdf.inputStream()
            }
        }
        assertTrue(submitted.explain(), submitted.isSuccess)
        val jobId = IppCapabilityMapper.jobId(submitted)
        assertNotNull("Print-Job answered with no job-id", jobId)

        assertEquals(IppJobState.COMPLETED, awaitTerminal(jobId!!))
        assertTrue(
            "no spooled document is byte-identical to the PDF that was sent",
            server.spooledDocuments().any { it.contentEquals(pdf) },
        )
    }

    // ---- 3. PWG raster, end to end, header checked independently ------------

    @Test
    fun `the independent header parser reads what Apple's rastertopwg wrote`() {
        // If this parser cannot read a header written by somebody else's
        // implementation, its verdict on ours means nothing.
        val header = PwgHeader.parse(resource("apple-rastertopwg-reference.pwg"))
        assertEquals("PwgRaster", header.mediaClass)
        assertEquals(203, header.dpiX)
        assertEquals(203, header.dpiY)
        assertEquals(406, header.width) // 2 in at 203 dpi
        assertEquals(812, header.height) // 4 in at 203 dpi
        assertEquals(8, header.bitsPerPixel)
        assertEquals(406, header.bytesPerLine)
        assertEquals(1, header.numColors)
    }

    @Test
    fun `print-job with pwg raster completes, and the header the printer received is right`() {
        val width = 400
        val height = 100
        val raw = ByteArray(width * height) { 0xFF.toByte() }
        for (y in 20 until 80) for (x in 50 until 250) raw[y * width + x] = 0
        val dpi = supportedResolutions().first()
        val options = PrintOptions(mediaSize = MediaSize.A4, dpi = dpi, copies = 1)

        val stream = ByteArrayOutputStream().apply {
            write("RaS2".toByteArray(Charsets.US_ASCII))
            write(
                PwgRasterEncoder.pageHeader(
                    width = width,
                    height = height,
                    bytesPerLine = width,
                    options = options,
                    mode = PwgRasterEncoder.Mode.GRAY_8,
                    totalPages = 1,
                ),
            )
            PwgRasterEncoder.encodeLines(this, raw, width, height, 1)
        }.toByteArray()

        val media = supportedMedia()
        val submitted = runBlocking {
            client.printJob(address, "live raster", PrintLanguage.PWG_RASTER, options, media, stream.size.toLong(), supportedResolutions = supportedResolutions()) {
                stream.inputStream()
            }
        }
        assertTrue(submitted.explain(), submitted.isSuccess)
        assertEquals(IppJobState.COMPLETED, awaitTerminal(IppCapabilityMapper.jobId(submitted)!!))

        val received = server.spooledDocuments().firstOrNull { it.contentEquals(stream) }
        assertNotNull("the server did not store the raster byte for byte", received)

        // Read the stored file the way a printer would: fixed offsets from the
        // spec, not this app's constants. The issue that led here named the
        // 1796-byte header offsets as the likeliest place for a bug.
        val header = PwgHeader.parse(received!!)
        assertEquals("PwgRaster", header.mediaClass)
        assertEquals(dpi, header.dpiX)
        assertEquals(dpi, header.dpiY)
        assertEquals(width, header.width)
        assertEquals(height, header.height)
        assertEquals(8, header.bitsPerColor)
        assertEquals(8, header.bitsPerPixel)
        assertEquals(width, header.bytesPerLine)
        assertEquals("sGray is CUPS colour space 18", 18, header.colorSpace)
        assertEquals(1, header.numColors)
        assertEquals(1, header.copies)

        // And the pixels after the header decode back to the page. A header one
        // byte too long or short would turn this into noise.
        val pixels = PwgHeader.decodeLines(received, width, height, 1)
        assertArrayEquals(raw, pixels)
    }

    // ---- 4. job template attributes are honoured ----------------------------

    @Test
    fun `copies, media and page-ranges reach the printer as sent`() {
        val pdf = resource("one-page-rectangle.pdf")
        val media = supportedMedia()
        val options = PrintOptions(copies = 2, mediaSize = MediaSize.LETTER, pageFrom = 1, pageTo = 1)

        val submitted = runBlocking {
            client.printJob(address, "live options", PrintLanguage.PDF, options, media, pdf.size.toLong(), supportedResolutions = supportedResolutions()) {
                pdf.inputStream()
            }
        }
        assertTrue(submitted.explain(), submitted.isSuccess)
        val jobId = IppCapabilityMapper.jobId(submitted)!!
        assertEquals(IppJobState.COMPLETED, awaitTerminal(jobId))

        val job = runBlocking { client.getJobAttributes(address, jobId) }.jobGroup()
        assertNotNull(job)
        assertEquals(2, job!!["copies"]?.asInt())
        assertEquals("na_letter_8.5x11in", job["media"]?.asString())
        val range = job["page-ranges"]?.values?.firstOrNull()
        assertTrue("page-ranges came back as $range", range is IppValue.IntRangeValue)
        assertEquals(1, (range as IppValue.IntRangeValue).lower)
        assertEquals(1, range.upper)
    }

    @Test
    fun `a resolution the printer does not offer is replaced by the nearest it does`() {
        // The bug this whole suite found: 300 dpi is this app's default, the
        // reference printer offers 600 only, and the job was refused outright.
        val supported = supportedResolutions()
        Assume.assumeTrue("needs a printer that does not offer 300 dpi", 300 !in supported)

        val pdf = resource("one-page-rectangle.pdf")
        val submitted = runBlocking {
            client.printJob(
                address, "live 300 on a 600 printer", PrintLanguage.PDF,
                PrintOptions(mediaSize = MediaSize.A4, dpi = 300), supportedMedia(),
                pdf.size.toLong(), supportedResolutions = supported,
            ) { pdf.inputStream() }
        }
        assertTrue(submitted.explain(), submitted.isSuccess)
        val jobId = IppCapabilityMapper.jobId(submitted)!!
        assertEquals(IppJobState.COMPLETED, awaitTerminal(jobId))

        val ticket = runBlocking { client.getJobAttributes(address, jobId) }.jobGroup()!!
        val sent = ticket["printer-resolution"]?.values?.firstOrNull()
        assertTrue("printer-resolution came back as $sent", sent is IppValue.Resolution)
        assertEquals(supported.minByOrNull { kotlin.math.abs(it - 300) }, (sent as IppValue.Resolution).x)
    }

    // ---- 5. IPPS, if this build of the emulator can serve it ----------------

    @Test
    fun `an ipps printer is refused until its certificate is pinned, then works`() {
        val tls = Emulator.start(Emulator.locate()!!, tls = true)
        try {
            Assume.assumeTrue(
                "this ippeveprinter does not serve implicit TLS (macOS's build keeps credentials " +
                    "in the keychain and ignores -K), so trust-on-first-use stays unit-tested only",
                tls.answersTls(),
            )
            val expected = PrinterTrust.fingerprint(tls.certificate!!)
            val secure = tls.address.copy(secure = true)

            val refusal = try {
                runBlocking { client.getPrinterAttributes(secure) }
                fail("a self-signed certificate was accepted with no pin")
                null
            } catch (e: Exception) {
                PrinterTrust.untrustedCause(e)
            }
            assertNotNull("the failure was not a named certificate refusal", refusal)
            assertEquals(expected, refusal!!.fingerprint)

            val pinned = secure.copy(certificateSha256 = refusal.fingerprint)
            val response = runBlocking { client.getPrinterAttributes(pinned) }
            assertTrue(response.statusText, response.isSuccess)
        } finally {
            tls.close()
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun awaitTerminal(jobId: Int): IppJobState? {
        val deadline = System.currentTimeMillis() + 30_000
        var last: IppJobState? = null
        while (System.currentTimeMillis() < deadline) {
            val response = runBlocking { client.getJobAttributes(address, jobId) }
            assertTrue(response.statusText, response.isSuccess)
            last = IppCapabilityMapper.jobState(response)
            if (last?.terminal == true) return last
            Thread.sleep(200)
        }
        fail("job $jobId never reached a terminal state; last seen $last")
        return null
    }

    /** The server's own account of what it would not accept: IPP's unsupported-attributes group (tag 5). */
    private fun IppResponse.explain(): String =
        statusText + groups.filter { it.tag == 0x05 }.flatMap { it.attributes }
            .joinToString(prefix = " unsupported: ") { "${it.name}=${it.values}" }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes() }
}

/**
 * A PWG raster page header read at the offsets PWG 5102.4 fixes, independently
 * of PwgRasterEncoder. Validated against a file Apple's rastertopwg wrote.
 */
private class PwgHeader(private val bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes)
    private fun u32(offset: Int) = buffer.getInt(SYNC + offset)
    private fun cstr(offset: Int): String =
        String(bytes, SYNC + offset, 64, Charsets.US_ASCII).takeWhile { it != '\u0000' }

    val mediaClass = cstr(0)
    val dpiX = u32(276)
    val dpiY = u32(280)
    val copies = u32(340)
    val width = u32(372)
    val height = u32(376)
    val bitsPerColor = u32(384)
    val bitsPerPixel = u32(388)
    val bytesPerLine = u32(392)
    val colorSpace = u32(400)
    val numColors = u32(420)

    companion object {
        private const val SYNC = 4
        private const val HEADER = 1796

        fun parse(bytes: ByteArray): PwgHeader {
            assertEquals("RaS2", String(bytes, 0, SYNC, Charsets.US_ASCII))
            return PwgHeader(bytes)
        }

        /** The reference line decoder from PwgRasterTest, applied after the header. */
        fun decodeLines(bytes: ByteArray, width: Int, height: Int, bpp: Int): ByteArray {
            val out = ByteArray(width * height * bpp)
            var i = SYNC + HEADER
            var y = 0
            while (y < height) {
                val repeat = bytes[i++].toInt() and 0xFF
                val line = ByteArray(width * bpp)
                var x = 0
                while (x < width) {
                    val count = bytes[i++].toInt() and 0xFF
                    if (count <= 127) {
                        repeat(count + 1) {
                            System.arraycopy(bytes, i, line, x * bpp, bpp)
                            x++
                        }
                        i += bpp
                    } else {
                        val literal = 257 - count
                        System.arraycopy(bytes, i, line, x * bpp, literal * bpp)
                        i += literal * bpp
                        x += literal
                    }
                }
                repeat(repeat + 1) {
                    System.arraycopy(line, 0, out, y * width * bpp, line.size)
                    y++
                }
            }
            return out
        }
    }
}

/** One running ippeveprinter. */
private class Emulator private constructor(
    private val process: Process,
    private val root: File,
    private val spool: File,
    val address: PrinterAddress.Ipp,
    val certificate: X509Certificate?,
) : AutoCloseable {

    fun spooledDocuments(): List<ByteArray> =
        spool.listFiles().orEmpty().filter { it.isFile }.map { it.readBytes() }

    /**
     * Whether anything answers a TLS handshake on the port. The trust-all
     * manager here is a capability probe that sends no request and is never
     * handed to the client under test.
     */
    fun answersTls(): Boolean = runCatching {
        val context = SSLContext.getInstance("TLS")
        context.init(
            null,
            arrayOf(
                object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                },
            ),
            null,
        )
        (context.socketFactory.createSocket(address.host, address.port) as SSLSocket).use {
            it.soTimeout = 3000
            it.startHandshake()
            true
        }
    }.getOrDefault(false)

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        root.deleteRecursively()
    }

    companion object {
        const val MODEL = "Emulated Printer"

        fun locate(): File? {
            val candidates = listOfNotNull(System.getenv("IPPEVEPRINTER")) +
                listOf("/usr/bin", "/usr/sbin", "/usr/local/bin", "/usr/local/sbin", "/opt/homebrew/bin")
                    .map { "$it/ippeveprinter" } +
                System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/ippeveprinter" }
            return candidates.map(::File).firstOrNull { it.canExecute() }
        }

        fun start(binary: File, tls: Boolean): Emulator {
            val root = Files.createTempDirectory("ippeve").toFile()
            val spool = File(root, "spool").apply { mkdirs() }
            val port = ServerSocket(0).use { it.localPort }
            val address = PrinterAddress.Ipp(host = "localhost", port = port)

            var certificate: X509Certificate? = null
            val args = mutableListOf(
                binary.path,
                "-p", port.toString(),
                "-n", "localhost",
                "-d", spool.path,
                "-k", // keep spool files: they are the evidence
                "-c", trueBinary(), // "process" a job instantly instead of sleeping
                "-f", "application/pdf,image/pwg-raster",
                "-m", MODEL,
            )
            if (helpMentions(binary, "--no-dns-sd")) args += "--no-dns-sd"
            if (tls) {
                val keys = File(root, "keys").apply { mkdirs() }
                certificate = selfSigned(keys)
                if (certificate == null) {
                    // No openssl: hand back something answersTls() says no to.
                    return Emulator(ProcessBuilder("/bin/true").start(), root, spool, address, null)
                }
                args += listOf("-K", keys.path)
            }
            // Bonjour refuses a second registration under the same name
            // (kDNSServiceErr_NameConflict, -65548) and ippeveprinter exits, so
            // the TLS instance must not be called what the plain one is.
            args += "PocketPrint Live $port"

            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(File(root, "server.log"))
                .start()

            val client = IppClient()
            val deadline = System.currentTimeMillis() + 15_000
            var lastError: Throwable? = null
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) break
                val ok = runCatching { runBlocking { client.getPrinterAttributes(address) }.isSuccess }
                    .onFailure { lastError = it }
                    .getOrDefault(false)
                if (ok) return Emulator(process, root, spool, address, certificate)
                Thread.sleep(250)
            }
            val log = File(root, "server.log").takeIf { it.exists() }?.readText().orEmpty()
            process.destroyForcibly()
            throw AssertionError(
                "ippeveprinter did not come up on port $port (alive=${process.isAlive}): " +
                    "$lastError\n--- server log ---\n$log",
            )
        }

        /** macOS has only /usr/bin/true; Linux has both. */
        private fun trueBinary(): String =
            listOf("/usr/bin/true", "/bin/true").firstOrNull { File(it).canExecute() }
                ?: error("no `true` binary to hand ippeveprinter as its print command")

        private fun helpMentions(binary: File, flag: String): Boolean = runCatching {
            val p = ProcessBuilder(binary.path, "--help").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            flag in text
        }.getOrDefault(false)

        /** localhost.crt / localhost.key, the names CUPS looks for. Null if openssl is absent. */
        private fun selfSigned(dir: File): X509Certificate? = runCatching {
            val p = ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", File(dir, "localhost.key").path,
                "-out", File(dir, "localhost.crt").path,
                "-days", "30", "-subj", "/CN=localhost/O=PocketPrint test",
            ).redirectErrorStream(true).redirectOutput(File(dir, "openssl.log")).start()
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return@runCatching null
            File(dir, "localhost.crt").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        }.getOrNull()
    }
}
