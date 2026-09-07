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
import com.gulshan.pocketprint.model.SourceDocument
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
                    acceptOne(uri, viewModel)
                    return
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                    acceptText(shared, intent.getStringExtra(Intent.EXTRA_SUBJECT), viewModel)
                }
            }

            Intent.ACTION_SEND_MULTIPLE ->
                acceptMany(
                    intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty(),
                    viewModel,
                )

            Intent.ACTION_VIEW -> intent.data?.let { acceptOne(it, viewModel) }
        }
    }

    /**
     * The outcome of examining one shared URI, so a batch can be summarised
     * instead of putting up one toast per file.
     */
    private sealed interface Examined {
        data class Ok(val document: SourceDocument) : Examined
        data class Rejected(val reason: String) : Examined
    }

    /** One shared document: staged if it passes, and the reason said if not. */
    private suspend fun acceptOne(uri: Uri, viewModel: PrintersViewModel) {
        when (val examined = examine(uri, ShareBudget())) {
            is Examined.Ok -> viewModel.setDocument(examined.document)
            is Examined.Rejected -> toast(examined.reason)
        }
    }

    /**
     * A shared batch, every document of it checked exactly as a single one is.
     *
     * Two bounds, both applied before anything is read. The count, because
     * nothing stops a caller sending ten thousand URIs and each one costs a
     * content-resolver query and a copy before anything is known about it. And
     * a byte budget across the batch, because the single-document limit
     * multiplied by an unbounded count is not a limit at all.
     *
     * Reported as one summary rather than a toast per file: twenty toasts is
     * not a better error message than one, and the interesting fact about a
     * batch is how much of it got through.
     */
    private suspend fun acceptMany(uris: List<Uri>, viewModel: PrintersViewModel) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            acceptOne(uris.first(), viewModel)
            return
        }

        val attempted = uris.take(ShareBudget.MAX_DOCUMENTS)
        val budget = ShareBudget()
        val accepted = mutableListOf<SourceDocument>()
        val reasons = mutableListOf<String>()

        for (uri in attempted) {
            // Stop rather than fail the rest one at a time: once the budget is
            // gone every remaining document would be reported as too large,
            // which is true of the batch and not of the document.
            if (budget.exhausted) break
            when (val examined = examine(uri, budget)) {
                is Examined.Ok -> accepted += examined.document
                is Examined.Rejected -> reasons += examined.reason
            }
        }

        if (accepted.isEmpty()) {
            toast(reasons.firstOrNull() ?: getString(R.string.share_none_printable))
            return
        }

        viewModel.setDocuments(accepted)

        val dropped = uris.size - accepted.size
        toast(
            if (dropped == 0) {
                resources.getQuantityString(
                    R.plurals.share_batch_ready, accepted.size, accepted.size,
                )
            } else {
                getString(R.string.share_batch_partial, accepted.size, uris.size)
            },
        )
    }

    /**
     * Checks a shared URI and takes a copy if it passes, or says why not.
     *
     * The copy happens now, not at print time, for two reasons. The read grant
     * on a shared URI is scoped to this Activity and is gone by the time the
     * print service opens it; and holding the bytes ourselves means the sending
     * app cannot swap the contents between the check and the print.
     */
    private suspend fun examine(uri: Uri, budget: ShareBudget): Examined {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            // A file:// URI is a caller asking us to read a path of its
            // choosing with our own identity. Sharing apps have not been
            // allowed to send one since API 24 in any case.
            return Examined.Rejected(getString(R.string.share_not_a_file))
        }

        val described = try {
            Spool.describe(this, uri)
        } catch (failure: Exception) {
            return Examined.Rejected(
                getString(R.string.share_unreadable, failure.message.orEmpty()),
            )
        }

        if (!RenderPipeline.canRender(described.mimeType, described.extension)) {
            return Examined.Rejected(
                getString(
                    R.string.share_cannot_print, described.displayName, described.mimeType,
                ),
            )
        }

        val local = try {
            val suffix = described.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
            Spool.copyToCache(this, uri, suffix, maxBytes = budget.allowance())
        } catch (tooBig: DocumentTooLarge) {
            // The exception's own limit, not the per-document constant: in a
            // batch the limit that fired may be what was left of the budget,
            // and quoting the wrong number sends somebody off to shrink a file
            // that was never the problem.
            return Examined.Rejected(
                getString(
                    R.string.share_too_large,
                    described.displayName,
                    (tooBig.limitBytes / (1024 * 1024)).toInt(),
                ),
            )
        } catch (timeout: TimeoutCancellationException) {
            return Examined.Rejected(getString(R.string.share_too_slow, described.displayName))
        } catch (failure: Exception) {
            return Examined.Rejected(
                getString(R.string.share_unreadable, failure.message.orEmpty()),
            )
        }

        budget.record(local.length())
        return Examined.Ok(
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
            toast(getString(R.string.share_text_truncated, MAX_SHARED_TEXT_CHARS))
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
