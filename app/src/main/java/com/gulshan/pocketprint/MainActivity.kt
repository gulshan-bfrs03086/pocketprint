package com.gulshan.pocketprint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gulshan.pocketprint.ui.AppNav
import com.gulshan.pocketprint.ui.screens.FirstRunScreen
import com.gulshan.pocketprint.ui.theme.PocketPrintTheme
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

/**
 * Asks for nothing on the way in.
 *
 * This used to fire every runtime permission request on the first frame, with
 * an empty result handler: a stack of dialogs about an app the user had not
 * seen yet. Refusing them is the reasonable response to that, and two refusals
 * is permanent on modern Android — so the app could be made permanently unable
 * to reach a Bluetooth printer before it had drawn a single screen, with
 * nothing anywhere noticing or offering a way back.
 *
 * Permissions are now requested where they are used, by the screen that needs
 * them, at the moment somebody asks for the thing that needs them.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PocketPrintTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root(viewModel: PrintersViewModel = viewModel()) {
    val firstRunDone by viewModel.firstRunDone.collectAsStateWithLifecycle()

    when (firstRunDone) {
        // Still reading. A blank frame beats flashing the welcome screen at
        // somebody who saw it months ago.
        null -> Unit
        false -> FirstRunScreen(onContinue = { viewModel.completeFirstRun() })
        true -> AppNav(viewModel)
    }
}
