package com.gulshan.pocketprint.render

import android.content.Context
import android.print.PdfPrint
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gulshan.pocketprint.model.Orientation
import com.gulshan.pocketprint.model.PrintLanguage
import com.gulshan.pocketprint.model.PrintOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders a URL or an HTML string to PDF through WebView's own print adapter,
 * which gives proper pagination and CSS print rules for free.
 *
 * WebView is strictly main-thread, so everything here hops to the main
 * dispatcher and suspends until the adapter reports back.
 */
object WebToPdf {

    suspend fun fromUrl(
        context: Context,
        url: String,
        options: PrintOptions,
    ): RenderedDocument = render(context, options) { webView ->
        webView.loadUrl(url)
    }

    suspend fun fromHtml(
        context: Context,
        html: String,
        baseUrl: String? = null,
        options: PrintOptions,
    ): RenderedDocument = render(context, options) { webView ->
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    private suspend fun render(
        context: Context,
        options: PrintOptions,
        load: (WebView) -> Unit,
    ): RenderedDocument {
        val output = Spool.newFile(context, ".pdf")

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = false      // printing static content only
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    blockNetworkImage = false
                    domStorageEnabled = false
                }

                var finished = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (finished) return
                        finished = true

                        // Give layout and late images one frame to settle.
                        view.postDelayed({
                            val adapter = view.createPrintDocumentAdapter("PocketPrint")
                            PdfPrint(printAttributes(options)).print(adapter, output) { file, err ->
                                view.destroy()
                                when {
                                    file != null && cont.isActive -> cont.resume(Unit)
                                    cont.isActive -> cont.resumeWithException(
                                        IllegalStateException(err ?: "Web render failed"),
                                    )
                                }
                            }
                        }, 350)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        if (finished) return
                        finished = true
                        view?.destroy()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException(description ?: "Page failed to load"),
                            )
                        }
                    }
                }

                cont.invokeOnCancellation { runCatching { webView.destroy() } }
                load(webView)
            }
        }

        val pages = runCatching { PdfRasterizer.pageCount(output) }.getOrDefault(1)
        return RenderedDocument(output, PrintLanguage.PDF, pages)
    }

    private fun printAttributes(options: PrintAttributesSource): PrintAttributes {
        val media = PrintAttributes.MediaSize(
            options.mediaId,
            options.mediaLabel,
            options.widthMils,
            options.heightMils,
        ).let { if (options.landscape) it.asLandscape() else it.asPortrait() }

        return PrintAttributes.Builder()
            .setMediaSize(media)
            .setResolution(PrintAttributes.Resolution("default", "Default", options.dpi, options.dpi))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
    }

    /** PrintAttributes measures paper in thousandths of an inch. */
    private class PrintAttributesSource(options: PrintOptions) {
        val mediaId = options.mediaSize.id
        val mediaLabel = options.mediaSize.label
        val widthMils = Math.round(options.mediaSize.widthMicrons / 25.4f)
        val heightMils = Math.round(options.mediaSize.heightMicrons / 25.4f)
        val landscape = options.orientation == Orientation.LANDSCAPE
        val dpi = options.dpi
    }

    private fun printAttributes(options: PrintOptions): PrintAttributes =
        printAttributes(PrintAttributesSource(options))
}
