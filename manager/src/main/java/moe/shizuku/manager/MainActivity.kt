package moe.shizuku.manager

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.ui.ShizukuApp
import moe.shizuku.manager.utils.AppLocale

class MainActivity : ComponentActivity() {

    /**
     * Starting Shizuku is announced in a notification, which also carries the buttons to
     * retry or cancel the attempt but on Android 13+ the app has to ask for the
     * notification permission at runtime, or none of it is ever shown.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * The whole Compose UI lives here, and unlike the activities that come from `MaterialActivity`
     * this one used to attach with the device's language whatever the user had chosen. On
     * Android 13+ the per-app locale is already in the configuration and [AppLocale.wrap] leaves
     * it alone; below that this is what makes the choice take effect.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * The chosen language is not only chosen here: from Android 13 the system settings screen
     * changes the per-app locale too, and the framework only rebuilds the activities when it
     * does - the process, and with it the stored copy of the choice, survives. Reconciling on
     * the way back to the foreground is what keeps the picker and the row from reporting a
     * language that is no longer in force.
     */
    override fun onResume() {
        super.onResume()
        AppLocale.reconcile(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        requestNotificationPermission()
        setContent {
            ShizukuApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(AppConstants.EXTRA_START_SERVICE_VIA_WADB, false)) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(AdbPairingService.NOTIFICATION_ID)
            ShizukuReceiverStarter.start(this, forceStart = true, userInitiated = true)
        }

        if (intent.getBooleanExtra(AppConstants.EXTRA_SHOW_PAIRING_DIALOG, false)) {
            showAccessibilityDialog()
        }
    }
}
