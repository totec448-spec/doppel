package de.totec.doppel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import de.totec.doppel.ui.LocalHideNumbers
import de.totec.doppel.ui.DoppelScreen
import de.totec.doppel.ui.theme.DoppelTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private var startAfterNotificationPermission = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (startAfterNotificationPermission) {
                startAfterNotificationPermission = false
                (application as DoppelApplication).appController.startService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DoppelTheme {
                val app = application as DoppelApplication
                val controller = remember { app.appController }
                val chats = remember { app.chatsController }
                // Provided here rather than inside the screen because every surface below it paints
                // numbers, and only the flag itself is collected: the whole tree recomposes when it
                // flips, which is the point, and must not recompose for any other state change.
                val hideNumbers by
                    remember(controller) {
                        controller.state
                            .map { it.hideSensitiveData }
                            .distinctUntilChanged()
                    }.collectAsState(initial = false)
                CompositionLocalProvider(LocalHideNumbers provides hideNumbers) {
                    DoppelScreen(
                        controller = controller,
                        chats = chats,
                        onStartService = { startBotWithNotificationPermission(controller::startService) },
                    )
                }
            }
        }
    }

    private fun startBotWithNotificationPermission(start: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startAfterNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
        }
    }
}
