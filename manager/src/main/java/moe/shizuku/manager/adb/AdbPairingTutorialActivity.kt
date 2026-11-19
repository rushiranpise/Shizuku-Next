package moe.shizuku.manager.adb

import android.Manifest
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.isGone
import androidx.core.view.isVisible
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AdbPairingTutorialActivityBinding
import moe.shizuku.manager.home.AdbPairDialogFragment
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.start.localHotspotPermission
import moe.shizuku.manager.start.localNetworkPermission
import moe.shizuku.manager.start.needsLocalHotspotPermissionFor
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.SettingsPage
import rikka.compatibility.DeviceCompatibility

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : AppBarActivity() {

    companion object {
        private const val EXTRA_PAIR_IN_APP = "moe.shizuku.manager.extra.PAIR_IN_APP"
        private const val PAIR_DIALOG_TAG = "in_app_pair_dialog"

        /** The manual choice with legacy pairing on: the code is typed in a dialog here. */
        fun inAppPairingIntent(context: Context): Intent =
            Intent(context, AdbPairingTutorialActivity::class.java)
                .putExtra(EXTRA_PAIR_IN_APP, true)
    }

    private lateinit var binding: AdbPairingTutorialActivityBinding

    private var notificationEnabled: Boolean = false
    private var pairInApp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this

        binding = AdbPairingTutorialActivityBinding.inflate(layoutInflater, rootView, true)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pairInApp = intent.getBooleanExtra(EXTRA_PAIR_IN_APP, false)
        if (pairInApp) {
            // The tutorial describes the notification flow, which this mode exists to avoid.
            // The permissions are still needed for discovery, so ask for them first and then
            // go straight to the code dialog.
            binding.scrollView.isGone = true
            startInAppPairing()
            return
        }

        notificationEnabled = isNotificationEnabled()

        if (notificationEnabled) {
            startPairingService()
        }

        binding.apply {
            syncNotificationEnabled()

            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }

            developerOptions.setOnClickListener {
                SettingsHelper.launchOrHighlightWirelessDebugging(context)
            }

            notificationOptions.setOnClickListener {
                SettingsPage.Notifications.NotificationSettings.launch(context)
            }
        }
    }

    private fun syncNotificationEnabled() {
        binding.apply {
            step1.isVisible = notificationEnabled
            step2.isVisible = notificationEnabled
            step3.isVisible = notificationEnabled
            network.isVisible = notificationEnabled
            notification.isVisible = notificationEnabled
            notificationDisabled.isGone = notificationEnabled
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val context = this

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() &&
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    override fun onResume() {
        super.onResume()
        if (pairInApp) return

        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            syncNotificationEnabled()

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
    }

    private val startPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Start pairing whether or not the grants succeeded; a denial simply means
            // discovery/connect will fail and the service surfaces the error.
            if (pairInApp) showPairDialog() else doStartPairingService()
        }

    private fun startPairingService() {
        val missing = missingPairingPermissions()
        if (missing.isEmpty()) {
            doStartPairingService()
        } else {
            startPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Shared with the permissions page, which can grant them before pairing is reached.
     * Pairing always discovers over the local network; the hotspot is needed only for a
     * following no-network (or forced-wireless) start, and asking here means that start does
     * not have to wait for an activity later.
     */
    private fun missingPairingPermissions(): List<String> {
        val method = ShizukuSettings.getStartMethod()
        return listOfNotNull(
            localNetworkPermission()?.takeIf {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            },
            localHotspotPermission()?.takeIf { needsLocalHotspotPermissionFor(method) }
        ).distinct()
    }

    private fun startInAppPairing() {
        val missing = missingPairingPermissions()
        if (missing.isEmpty()) {
            showPairDialog()
        } else {
            startPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showPairDialog() {
        if (isFinishing || supportFragmentManager.isStateSaved) return

        // After a configuration change the fragment manager restores the dialog, and the
        // callback held by the old instance is gone. Reuse that instance instead of showing
        // a second dialog on top of it, and attach the callback again so closing it still
        // finishes the activity.
        val restored = supportFragmentManager
            .findFragmentByTag(PAIR_DIALOG_TAG) as? AdbPairDialogFragment
        val dialog = restored ?: AdbPairDialogFragment()
        dialog.dismissAction = { finish() }
        if (restored == null) {
            dialog.show(supportFragmentManager, PAIR_DIALOG_TAG)
        }
    }

    private fun doStartPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "startForegroundService", e)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                val mode = getSystemService(AppOpsManager::class.java)
                    .noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, "OP_START_FOREGROUND is denied. What are you doing?", Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }
}
