package moe.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import android.os.PowerManager
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.start.isDeveloperOptionsEnabled
import moe.shizuku.manager.utils.SettingsPage
import android.widget.Toast
import rikka.shizuku.Shizuku

private const val TAG = "SettingsHelper"

object SettingsHelper {

    fun launchOrHighlightWirelessDebugging(context: Context) {
        // Wireless debugging lives under Developer options, which our "ADB without Developer
        // options" setting can hide: launching anyway would open nothing at all, so say
        // what has to change instead.
        if (!context.isDeveloperOptionsEnabled()) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_developer_options_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Read through the helper: the raw setting is redacted on Android 17.
        if (EnvironmentUtils.isAdbEnabled()) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
        }
        if (launcher != null) {
            launcher.launch(intent)
        } else {
            context.startActivity(intent)
        }
    }

    /**
     * Whitelists this app for battery optimization directly through Shizuku (or
     * root) rather than only opening the system dialog. Falls back to the dialog
     * when neither is available or every command fails.
     */
    fun requestIgnoreBatteryOptimizationsPrivileged(
        context: Context,
        launcher: ActivityResultLauncher<Intent>? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val pkg = context.packageName
        val cmds = listOf(
            "cmd deviceidle whitelist +$pkg",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg REQUEST_IGNORE_BATTERY_OPTIMIZATIONS allow",
            "cmd appops set $pkg AUTO_START allow"
        )

        CoroutineScope(Dispatchers.IO).launch {
            var success = false

            if (Shizuku.pingBinder()) {
                try {
                    // Shizuku#newProcess is private, reach it through reflection.
                    val newProcess = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).apply { isAccessible = true }

                    for (cmd in cmds) {
                        val process = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
                        process?.waitFor()
                    }
                    success = isIgnoringBatteryOptimizations(context)
                } catch (e: Throwable) {
                    Log.w(TAG, "Privileged battery whitelist via Shizuku failed", e)
                }
            }

            if (!success && Shell.isAppGrantedRoot() == true) {
                try {
                    for (cmd in cmds) Shell.cmd(cmd).exec()
                    success = isIgnoringBatteryOptimizations(context)
                } catch (e: Throwable) {
                    Log.w(TAG, "Privileged battery whitelist via root failed", e)
                }
            }

            withContext(Dispatchers.Main) {
                when {
                    success -> onComplete?.invoke(true)
                    // Let the provided launcher deliver the dialog result.
                    launcher != null -> requestIgnoreBatteryOptimizations(context, launcher)
                    else -> {
                        requestIgnoreBatteryOptimizations(context)
                        onComplete?.invoke(false)
                    }
                }
            }
        }
    }

}