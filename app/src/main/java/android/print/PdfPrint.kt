package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Drives a PrintDocumentAdapter straight to a file.
 *
 * This lives in the android.print package on purpose: the constructors of
 * LayoutResultCallback and WriteResultCallback are package-private, so they can
 * only be subclassed from inside that package. This is the long-standing
 * workaround for turning a WebView into a PDF without going through the system
 * print dialog.
 */
class PdfPrint(private val attributes: PrintAttributes) {

    fun interface Callback {
        fun onFinished(result: File?, error: String?)
    }

    fun print(adapter: PrintDocumentAdapter, output: File, callback: Callback) {
        adapter.onLayout(
            null,
            attributes,
            CancellationSignal(),
            object : PrintDocumentAdapter.LayoutResultCallback() {

                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    val descriptor = try {
                        ParcelFileDescriptor.open(
                            output,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                ParcelFileDescriptor.MODE_TRUNCATE,
                        )
                    } catch (t: Throwable) {
                        callback.onFinished(null, "Cannot open spool file: ${t.message}")
                        return
                    }

                    adapter.onWrite(
                        arrayOf(PageRange.ALL_PAGES),
                        descriptor,
                        CancellationSignal(),
                        object : PrintDocumentAdapter.WriteResultCallback() {

                            override fun onWriteFinished(pages: Array<out PageRange>?) {
                                runCatching { descriptor.close() }
                                if (pages.isNullOrEmpty()) {
                                    callback.onFinished(null, "Renderer produced no pages")
                                } else {
                                    callback.onFinished(output, null)
                                }
                            }

                            override fun onWriteFailed(error: CharSequence?) {
                                runCatching { descriptor.close() }
                                callback.onFinished(null, error?.toString() ?: "Write failed")
                            }

                            override fun onWriteCancelled() {
                                runCatching { descriptor.close() }
                                callback.onFinished(null, "Write cancelled")
                            }
                        },
                    )
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    callback.onFinished(null, error?.toString() ?: "Layout failed")
                }

                override fun onLayoutCancelled() {
                    callback.onFinished(null, "Layout cancelled")
                }
            },
            null,
        )
    }
}
