package com.gulshan.pocketprint

import android.print.PrintAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gulshan.pocketprint.model.ColorMode
import com.gulshan.pocketprint.model.DuplexMode
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.printservice.optionsFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * What the system print dialog decides, translated into what this app prints.
 *
 * On device because PrintAttributes is a framework class with real behaviour -
 * isPortrait is computed from the media size, and getLabel goes to the package
 * manager - none of which a JVM stub reproduces. A real PrintJob cannot be made
 * outside the framework, so onPrintJobQueued itself stays out of reach; this is
 * the part of that path a test can hold, and the part where a wrong number
 * silently prints at the wrong size.
 */
@RunWith(AndroidJUnit4::class)
class PrintAttributesTest {

    private val packageManager =
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager

    private fun attributes(
        media: PrintAttributes.MediaSize = PrintAttributes.MediaSize.ISO_A4,
        color: Int = PrintAttributes.COLOR_MODE_MONOCHROME,
        duplex: Int = PrintAttributes.DUPLEX_MODE_NONE,
        dpi: PrintAttributes.Resolution =
            PrintAttributes.Resolution("r", "300 dpi", 300, 300),
    ): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(media)
        .setColorMode(color)
        .setDuplexMode(duplex)
        .setResolution(dpi)
        .build()

    @Test
    fun milsAreConvertedToMicronsAgainstTheFrameworksOwnNumbers() {
        // Checked against what the framework actually hands over rather than
        // against constants typed here: one mil is a thousandth of an inch, so
        // 25.4 microns, and that relationship is the whole of this conversion.
        listOf(
            PrintAttributes.MediaSize.ISO_A4,
            PrintAttributes.MediaSize.NA_LETTER,
            PrintAttributes.MediaSize.ISO_A5,
            PrintAttributes.MediaSize.NA_INDEX_4X6,
        ).forEach { media ->
            val options = optionsFrom(attributes(media = media), 1, packageManager)

            assertEquals(
                "width of ${media.id}",
                Math.round(media.widthMils * 25.4f),
                options.mediaSize.widthMicrons,
            )
            assertEquals(
                "height of ${media.id}",
                Math.round(media.heightMils * 25.4f),
                options.mediaSize.heightMicrons,
            )
            assertEquals("id of ${media.id}", media.id, options.mediaSize.id)
        }
    }

    @Test
    fun theFrameworksA4IsNotQuiteA4AndThatIsWorthKnowing() {
        // ISO A4 is 210.0 x 297.0 mm. The framework rounds it to whole mils,
        // which lands 58 microns wide - a sixteenth of a millimetre. Harmless on
        // a sheet printer, and pinned here because it is the kind of drift that
        // gets "corrected" into a real bug on stock that is cut to size.
        val options = optionsFrom(attributes(), copies = 1, packageManager = packageManager)

        assertEquals(210_058, options.mediaSize.widthMicrons)
        assertTrue(
            "framework A4 drifted further than a tenth of a millimetre from real A4",
            abs(options.mediaSize.widthMicrons - 210_000) < 100 &&
                abs(options.mediaSize.heightMicrons - 297_000) < 100,
        )
    }

    @Test
    fun letterIsWithinARoundingStepOfOurOwnConstant() {
        val options = optionsFrom(
            attributes(media = PrintAttributes.MediaSize.NA_LETTER),
            copies = 1,
            packageManager = packageManager,
        )

        // MediaSize.LETTER derives from inches; the framework rounds to mils.
        assertTrue(
            "letter width ${options.mediaSize.widthMicrons} vs ${MediaSize.LETTER.widthMicrons}",
            abs(options.mediaSize.widthMicrons - MediaSize.LETTER.widthMicrons) < 100,
        )
        assertTrue(
            "letter height ${options.mediaSize.heightMicrons} vs ${MediaSize.LETTER.heightMicrons}",
            abs(options.mediaSize.heightMicrons - MediaSize.LETTER.heightMicrons) < 100,
        )
    }

    @Test
    fun aLandscapeMediaSizeBecomesLandscape() {
        // The framework has no orientation field: it hands back a rotated media
        // size, and isPortrait is derived from its own width and height.
        val landscape = PrintAttributes.MediaSize.ISO_A4.asLandscape()

        val options = optionsFrom(
            attributes(media = landscape), copies = 1, packageManager = packageManager,
        )

        assertEquals(Orientation.LANDSCAPE, options.orientation)
    }

    @Test
    fun aPortraitMediaSizeStaysPortrait() {
        val options = optionsFrom(
            attributes(media = PrintAttributes.MediaSize.ISO_A4.asPortrait()),
            copies = 1,
            packageManager = packageManager,
        )

        assertEquals(Orientation.PORTRAIT, options.orientation)
    }

    @Test
    fun colourIsOnlyClaimedWhenTheDialogAsksForIt() {
        assertEquals(
            ColorMode.COLOR,
            optionsFrom(
                attributes(color = PrintAttributes.COLOR_MODE_COLOR),
                1, packageManager,
            ).colorMode,
        )
        assertEquals(
            ColorMode.MONOCHROME,
            optionsFrom(
                attributes(color = PrintAttributes.COLOR_MODE_MONOCHROME),
                1, packageManager,
            ).colorMode,
        )
    }

    @Test
    fun eachDuplexModeMapsToItsOwn() {
        assertEquals(
            DuplexMode.LONG_EDGE,
            optionsFrom(
                attributes(duplex = PrintAttributes.DUPLEX_MODE_LONG_EDGE), 1, packageManager,
            ).duplex,
        )
        assertEquals(
            DuplexMode.SHORT_EDGE,
            optionsFrom(
                attributes(duplex = PrintAttributes.DUPLEX_MODE_SHORT_EDGE), 1, packageManager,
            ).duplex,
        )
        assertEquals(
            DuplexMode.SIMPLEX,
            optionsFrom(
                attributes(duplex = PrintAttributes.DUPLEX_MODE_NONE), 1, packageManager,
            ).duplex,
        )
    }

    @Test
    fun resolutionComesFromTheDialog() {
        assertEquals(
            600,
            optionsFrom(
                attributes(dpi = PrintAttributes.Resolution("r", "600", 600, 600)),
                1, packageManager,
            ).dpi,
        )
        assertEquals(
            203,
            optionsFrom(
                attributes(dpi = PrintAttributes.Resolution("r", "203", 203, 203)),
                1, packageManager,
            ).dpi,
        )
    }

    @Test
    fun aZeroResolutionCannotArrive() {
        // optionsFrom guards with takeIf { it > 0 }. That guard is unreachable
        // through the framework, and this records why rather than leaving a
        // reader to wonder whether it is load-bearing: Resolution's own
        // constructor rejects a non-positive dpi, so the only way to reach the
        // 300 fallback is a null resolution or null attributes, which the test
        // below covers.
        val rejected = runCatching { PrintAttributes.Resolution("r", "0", 0, 0) }

        assertTrue(
            "the framework accepted a zero dpi; the guard in optionsFrom is now live",
            rejected.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun attributesWithoutAResolutionFallBackTo300() {
        val noResolution = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()

        assertEquals(300, optionsFrom(noResolution, 1, packageManager).dpi)
    }

    @Test
    fun copiesAreNeverZeroOrNegative() {
        // The framework should not send these, but a job that prints nothing
        // looks exactly like a job that failed silently.
        assertEquals(1, optionsFrom(attributes(), copies = 0, packageManager = packageManager).copies)
        assertEquals(1, optionsFrom(attributes(), copies = -3, packageManager = packageManager).copies)
        assertEquals(4, optionsFrom(attributes(), copies = 4, packageManager = packageManager).copies)
    }

    @Test
    fun noAttributesAtAllFallBackToSomethingPrintable() {
        // attributes is nullable in PrintJobInfo, and a null must not become a
        // zero-sized page.
        val options = optionsFrom(null, copies = 1, packageManager = packageManager)

        assertEquals(MediaSize.A4, options.mediaSize)
        assertEquals(Orientation.PORTRAIT, options.orientation)
        assertEquals(ColorMode.MONOCHROME, options.colorMode)
        assertEquals(DuplexMode.SIMPLEX, options.duplex)
        assertEquals(300, options.dpi)
    }
}
