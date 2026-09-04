package com.gulshan.pocketprint

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gulshan.pocketprint.render.DocumentTooLarge
import com.gulshan.pocketprint.render.RenderPipeline
import com.gulshan.pocketprint.render.Spool
import com.gulshan.pocketprint.ui.AppNav
import com.gulshan.pocketprint.ui.theme.PocketPrintTheme
import com.gulshan.pocketprint.ui.vm.PrintersViewModel
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Receives files, text and URLs shared from other apps, so "Share -> Print with
 * PocketPrint" works from anywhere without going through the system dialog.
 *
 * This is the app's only exported entry point that takes data, and it is
 * exported to everything on the device. The manifest filter decides what the
 * share sheet *offers*, which is not the same as what can arrive: any app can
 * start an exported activity with an explicit intent carrying whatever it
 * likes. So the checks live here rather than in the filter, and everything
 * that arrives is treated as hostile until it has passed them.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The share target drew under the status bar's colour rather than
        // through it, so arriving here from another app was a visible seam.
        enableEdgeToEdge()

        setContent {
            PocketPrintTheme {
                val viewModel: PrintersViewModel = viewModel()
                LaunchedEffect(Unit) { handleIntent(intent, viewModel) }
                AppNav(viewModel)
            }
        }
    }

    private suspend fun handleIntent(intent: Intent?, viewModel: PrintersViewModel) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    accept(uri, viewModel)
                    return
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                    acceptText(shared, intent.getStringExtra(Intent.EXTRA_SUBJECT), viewModel)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multi-document printing is not implemented; take the first and
                // say so rather than silently dropping the rest.
                val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if ((uris?.size ?: 0) > 1) {
                    toast(
                        "PocketPrint prints one document at a time; using the first of " +
                            "${uris?.size} shared files.",
                    )
                }
                uris?.firstOrNull()?.let { accept(it, viewModel) }
            }

            Intent.ACTION_VIEW -> intent.data?.let { accept(it, viewModel) }
        }
    }

    /**
     * Takes a shared document if it passes every check, and says why if it does
     * not.
     *
     * The copy happens now, not at print time, for two reasons. The read grant
     * on a shared URI is scoped to this Activity and is gone by the time the
     * print service opens it; and holding the bytes ourselves means the sending
     * app cannot swap the contents between the check and the print.
     */
    private suspend fun accept(uri: Uri, viewModel: PrintersViewModel) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            // A file:// URI is a caller asking us to read a path of its
            // choosing with our own identity. Sharing apps have not been
            // allowed to send one since API 24 in any case.
            toast("PocketPrint accepts shared files, not raw file paths.")
            return
        }

        val described = try {
            Spool.describe(this, uri)
        } catch (failure: Exception) {
            toast("That file could not be read: ${failure.message}")
            return
        }

        if (!RenderPipeline.canRender(described.mimeType, described.extension)) {
            toast("PocketPrint can't print ${described.displayName} (${described.mimeType}).")
            return
        }

        val local = try {
            val suffix = described.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
            Spool.copyToCache(this, uri, suffix)
        } catch (tooBig: DocumentTooLarge) {
            toast(
                "${described.displayName} is over the " +
                    "${Spool.MAX_DOCUMENT_BYTES / (1024 * 1024)} MB limit for a print job.",
            )
            return
        } catch (timeout: TimeoutCancellationException) {
            toast("${described.displayName} took too long to read; nothing was printed.")
            return
        } catch (failure: Exception) {
            toast("That file could not be read: ${failure.message}")
            return
        }

        viewModel.setDocument(
            described.copy(
                uri = Uri.fromFile(local).toString(),
                sizeBytes = local.length(),
            ),
        )
    }

    /**
     * Shared text is bounded too. A megabyte of plain text is a five-figure
     * page count, which is a printer jammed for an afternoon rather than a
     * document, so take the front of it and say that is what happened.
     */
    private suspend fun acceptText(
        text: String,
        subject: String?,
        viewModel: PrintersViewModel,
    ) {
        if (text.length > MAX_SHARED_TEXT_CHARS) {
            toast(
                "That text is very long; printing the first " +
                    "$MAX_SHARED_TEXT_CHARS characters.",
            )
        }
        viewModel.setSharedText(text.take(MAX_SHARED_TEXT_CHARS), subject)
    }

    // Every caller is already on the main thread: handleIntent runs in the
    // composition's scope, and the suspending work below it returns there.
    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // getParcelableExtra without a class argument is deprecated from API 33.
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(
        name: String,
    ): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(name)
    }

    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(
        name: String,
    ): ArrayList<T>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
    }

    private companion object {
        /** Roughly a hundred pages of dense text. */
        const val MAX_SHARED_TEXT_CHARS = 200_000
    }
}
