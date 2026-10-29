package com.gulshan.pocketprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gulshan.pocketprint.printservice.PrintServiceRebind
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

    // The same instance Root() asks for below: both resolve against this
    // activity's store, under the same key.
    private val printers: PrintersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, which is where the launch theme is swapped for
        // the app one. Android 12 shows a splash whether an app asks for one or
        // not; this makes it the app's rather than a default built from the
        // icon, and backports the same thing to the versions the legacy build
        // still runs on.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only on a fresh start. A rotation recreates the activity with the
        // intent it was first launched by, and answering that again would
        // scroll somebody back to the setup card they had already left.
        if (savedInstanceState == null) noteHowLaunched(intent)

        // Opening the app is the one moment it can repair the system print
        // framework's binding to it, if a stop or an update left that binding
        // dead - see Rebind. Not on a rotation: nothing has changed since the
        // first time.
        if (savedInstanceState == null) PrintServiceRebind.onUserLaunch(this)

        setContent {
            PocketPrintTheme {
                Root()
            }
        }
    }

    /** For a launch that reaches an activity that is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteHowLaunched(intent)
    }

    /**
     * The system's "Add printer using PocketPrint" entry opens the app by a
     * second name, [AddPrintersEntry]; that is the only way to know it was the
     * entry and not the launcher.
     */
    private fun noteHowLaunched(intent: Intent?) {
        if (AddPrintersEntry.isLaunchedVia(intent?.component?.className)) {
            printers.addPrinterRequest.request()
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
