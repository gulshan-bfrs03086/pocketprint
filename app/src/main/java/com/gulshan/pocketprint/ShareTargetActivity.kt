package com.gulshan.pocketprint

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gulshan.pocketprint.ui.AppNav
import com.gulshan.pocketprint.ui.theme.PocketPrintTheme
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

/**
 * Receives files, text and URLs shared from other apps, so "Share -> Print with
 * PocketPrint" works from anywhere without going through the system dialog.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PocketPrintTheme {
                val viewModel: PrintersViewModel = viewModel()
                LaunchedEffect(Unit) { handleIntent(intent, viewModel) }
                AppNav(viewModel)
            }
        }
    }

    private fun handleIntent(intent: Intent?, viewModel: PrintersViewModel) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    // Persist read access, since the grant dies with this Activity.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    viewModel.selectSharedDocument(uri)
                    return
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                    viewModel.setSharedText(shared, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multi-document printing is not implemented; take the first and
                // say so rather than silently dropping the rest.
                val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.firstOrNull()?.let { viewModel.selectSharedDocument(it) }
                if ((uris?.size ?: 0) > 1) {
                    Toast.makeText(
                        this,
                        "PocketPrint prints one document at a time; using the first of " +
                            "${uris?.size} shared files.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            Intent.ACTION_VIEW -> intent.data?.let { viewModel.selectDocument(it) }
        }
    }

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
}
