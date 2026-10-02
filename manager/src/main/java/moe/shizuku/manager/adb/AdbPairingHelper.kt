package moe.shizuku.manager.adb

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.home.AdbPairDialogFragment
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.content.asActivity

object AdbPairingHelper {

    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun handlePairing(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
        } else if ((context.display?.displayId ?: -1) > 0 || ShizukuSettings.getLegacyPairing()) {
            // Running in a multi-display environment (e.g., Windows Subsystem for Android),
            // pairing dialog can be displayed simultaneously with Shizuku.
            // Input from notification is harder to use under this situation.
            val activity = context.asActivity<FragmentActivity>()
            AdbPairDialogFragment().show(activity.supportFragmentManager)
        } else {
            context.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
        }
    }
}
