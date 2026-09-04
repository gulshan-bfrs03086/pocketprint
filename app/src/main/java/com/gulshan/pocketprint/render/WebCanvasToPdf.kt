package com.gulshan.pocketprint.render

import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import com.gulshan.pocketprint.model.PrintOptions
import com.gulshan.pocketprint.print.Diagnostics
import java.io.File

/**
 * Renders a loaded WebView to PDF using nothing but public API.
 *
 * The path this backs up drives WebView's own PrintDocumentAdapter, which is
 * the right way to do this and produces much better output: it honours CSS
 * print rules, page-break properties and headers. Reaching it without the
 * system print dialog needs a class planted inside the android.print package,
 * because the two result callbacks have package-private constructors — a
 * long-standing workaround that is on the unsupported list and has no
 * replacement.
 *
 * The day that stops working, every shared link and every HTML document stops
 * printing at once, silently, with nothing in the app able to explain it. So
 * there is this: measure the page, draw the view onto a PdfDocument canvas, and
 * translate down a page at a time.
 *
 * It is honestly worse. There are no CSS print rules here and no page breaks,
 * so a line of text can be cut in half at a page boundary. It is a fallback and
 * is used only as one — but a document with an awkward page break is a document
 * that printed.
 */
internal object WebCanvasToPdf {

    private const val TAG = "WebCanvasToPdf"

    /**
     * Runaway content is possible - a page that lays out to a hundred thousand
     * pixels is a rendering bug somewhere, not a document - and this is a
     * printer on the other end.
     */
    private const val MAX_PAGES = 200

    /**
     * Must run on the main thread, with a WebView that has finished loading.
     * Returns false when there was nothing to draw.
     */
    fun render(webView: WebView, output: File, options: PrintOptions): Boolean {
        val media = options.mediaSize
        val pageWidthPoints = media.widthPoints.toInt().coerceAtLeast(1)
        val pageHeightPoints = media.heightPoints.toInt().coerceAtLeast(1)

        // Lay the view out at the page's own dot width. Nothing has attached
        // this WebView to a window, so it has no size of its own to work from.
        val widthPx = media.dotsWide(options.dpi).coerceAtLeast(1)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val contentHeightPx = webView.measuredHeight.takeIf { it > 0 }
            ?: return false
        webView.layout(0, 0, widthPx, contentHeightPx)

        val scale = pageWidthPoints.toFloat() / widthPx
        val pageHeightPx = (pageHeightPoints / scale).toInt().coerceAtLeast(1)

        val document = PdfDocument()
        var drawn = 0
        try {
            var top = 0
            while (top < contentHeightPx && drawn < MAX_PAGES) {
                val page = document.startPage(
                    PdfDocument.PageInfo
                        .Builder(pageWidthPoints, pageHeightPoints, drawn + 1)
                        .create(),
                )
                page.canvas.apply {
                    // Points, not dots: the canvas is the size of the paper, the
                    // view is the size of the raster.
                    scale(scale, scale)
                    translate(0f, -top.toFloat())
                    webView.draw(this)
                }
                document.finishPage(page)
                top += pageHeightPx
                drawn++
            }

            if (drawn == 0) return false
            output.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }

        Diagnostics.record(
            TAG,
            "rendered $drawn page(s) through the public canvas path, not the print adapter",
        )
        return true
    }
}
