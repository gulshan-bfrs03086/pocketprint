package com.gulshan.pocketprint.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.model.Printer
import com.gulshan.pocketprint.model.PrinterAddress
import com.gulshan.pocketprint.ui.theme.PocketPrintTheme
import com.gulshan.pocketprint.ui.vm.CertificatePrompt
import com.gulshan.pocketprint.ui.vm.PreviewState

/**
 * Previews for the states that are expensive or impossible to reach on real
 * hardware.
 *
 * This app is mostly answers to questions the network asked: a printer that
 * presents a certificate nobody vouches for, a certificate that changed
 * between one job and the next, a raster that dithered to mud. Reaching those
 * on a device means owning a printer in that state - and the certificate-
 * changed dialog in particular is the one screen that has to be right the one
 * time anybody ever sees it, because it is the screen shown when the thing
 * answering at the printer's address might not be the printer.
 *
 * So they are pinned here instead. Every value is a literal - no clocks, no
 * random ids - because a preview that renders differently on Tuesday is not a
 * reference for anything.
 *
 * Note the deliberately short heights on some of these. A dialog is only as
 * good as its worst case, and the worst case is a small screen with the
 * keyboard up, not the 640dp of empty canvas a default preview gives you.
 */

private val PREVIEW_PRINTER = Printer(
    id = "ipps-officejet-9015",
    displayName = "HP OfficeJet Pro 9015",
    address = PrinterAddress.Ipp(host = "192.168.1.42", port = 631, secure = true),
    makeAndModel = "HP OfficeJet Pro 9015e",
    location = "Study",
)

/** Real shape, fixed value: 64 lowercase hex characters, as PrinterTrust stores them. */
private const val FINGERPRINT =
    "9f2a4c81d3e6b705a1c8f42d9b6e03a7c5d18f2b4e9a06c3d7f158b2e4a9c60d"

private const val PREVIOUS_FINGERPRINT =
    "1b7e40c92a5d836f0e4c17b9d2a806f35c9e1d47b08a2f6c3d95e1a704b8c26f"

/** 2027-03-14, so the rendered date never moves. */
private const val NOT_AFTER = 1_804_982_400_000L

@Preview(name = "Certificate - untrusted", showBackground = true, widthDp = 360)
@Composable
private fun CertificateUntrustedPreview() = PocketPrintTheme {
    CertificatePromptDialog(
        prompt = CertificatePrompt(
            printer = PREVIEW_PRINTER,
            fingerprint = FINGERPRINT,
            subject = "CN=HP OfficeJet Pro 9015, O=HP Inc.",
            notAfterEpochMs = NOT_AFTER,
            previousPin = null,
        ),
        onTrust = {},
        onDismiss = {},
    )
}

/**
 * The longest state this dialog has: prose containing one fingerprint, then a
 * second fingerprint below it. Short height on purpose - if the buttons are
 * not reachable here, they are not reachable on a phone in landscape.
 */
@Preview(name = "Certificate - changed, short screen", showBackground = true, widthDp = 360, heightDp = 420)
@Composable
private fun CertificateChangedPreview() = PocketPrintTheme {
    CertificatePromptDialog(
        prompt = CertificatePrompt(
            printer = PREVIEW_PRINTER,
            fingerprint = FINGERPRINT,
            subject = "CN=HP OfficeJet Pro 9015, O=HP Inc.",
            notAfterEpochMs = NOT_AFTER,
            previousPin = PREVIOUS_FINGERPRINT,
        ),
        onTrust = {},
        onDismiss = {},
    )
}

@Preview(name = "Certificate - already trusted", showBackground = true, widthDp = 360)
@Composable
private fun CertificateTrustedPreview() = PocketPrintTheme {
    CertificatePromptDialog(
        prompt = CertificatePrompt(
            printer = PREVIEW_PRINTER,
            fingerprint = FINGERPRINT,
            subject = "CN=HP OfficeJet Pro 9015, O=HP Inc.",
            notAfterEpochMs = NOT_AFTER,
            previousPin = null,
            alreadyTrusted = true,
        ),
        onTrust = {},
        onDismiss = {},
    )
}

/**
 * The preview dialog before the raster comes back, and when it cannot. The
 * bitmap state is left to the device: a real one-bit raster is the whole point
 * of that state, and a rectangle drawn here would only prove the Image
 * composable works.
 */
@Preview(name = "Print preview - loading", showBackground = true, widthDp = 360)
@Composable
private fun PrintPreviewLoadingPreview() = PocketPrintTheme {
    PrintPreviewDialog(
        state = PreviewState(printerName = "4BARCODE 4B-2044PA", loading = true),
        onDismiss = {},
    )
}

@Preview(name = "Print preview - not applicable", showBackground = true, widthDp = 360)
@Composable
private fun PrintPreviewMessagePreview() = PocketPrintTheme {
    PrintPreviewDialog(
        state = PreviewState(
            printerName = "HP OfficeJet Pro 9015",
            loading = false,
            message = "PDF takes the document as it is, so there is nothing here that " +
                "the document itself does not already show.",
        ),
        onDismiss = {},
    )
}

@Preview(name = "Banners and header", showBackground = true, widthDp = 360)
@Preview(
    name = "Banners and header - dark",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BannersPreview() = PocketPrintTheme {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        SectionHeader("Printers")
        InfoBanner(
            "This printer answers on port 9100, which carries no status back. " +
                "A job that vanishes looks exactly like a job that printed.",
        )
        SectionHeader("Something went wrong")
        WarningBanner(
            "Saved printers could not be read back and have been left alone. " +
                "Adding one now would overwrite them.",
        )
    }
}

@Preview(name = "Auto setup - no candidates", showBackground = true, widthDp = 360)
@Composable
private fun AutoSetupCardEmptyPreview() = PocketPrintTheme {
    Column(Modifier.padding(16.dp)) {
        AutoSetupCard(
            candidateCount = 0,
            stock = MediaSize.LABEL_4X6,
            onStockChange = {},
            onStart = {},
        )
    }
}

@Preview(name = "Auto setup - candidates found", showBackground = true, widthDp = 360)
@Composable
private fun AutoSetupCardFoundPreview() = PocketPrintTheme {
    Column(Modifier.padding(16.dp)) {
        AutoSetupCard(
            candidateCount = 3,
            stock = MediaSize.RECEIPT_80,
            onStockChange = {},
            onStart = {},
        )
    }
}
