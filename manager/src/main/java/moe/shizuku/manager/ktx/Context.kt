package moe.shizuku.manager.ktx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import moe.shizuku.manager.ShizukuApplication

val Context.application: ShizukuApplication
    get() {
        return applicationContext as ShizukuApplication
    }

fun Context.createDeviceProtectedStorageContextCompat(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

fun Context.createDeviceProtectedStorageContextCompatWhenLocked(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

/**
 * Whether credential-encrypted storage is readable yet. False from boot until the first unlock,
 * which is when the app can run (it is direct-boot aware) and WorkManager's database cannot be read.
 */
fun Context.isUserUnlockedCompat(): Boolean =
    getSystemService(UserManager::class.java)?.isUserUnlocked != false

/**
 * Registers a dynamic broadcast receiver that does not accept broadcasts from other apps on
 * Android 13+.
 *
 * Uses the platform flag directly instead of `ContextCompat.registerReceiver`, because the manifest
 * removes `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` which `ContextCompat` requires on Android 12
 * and below and throws a [RuntimeException] when absent.
 */
fun Context.registerNotExportedReceiver(
    receiver: BroadcastReceiver,
    filter: IntentFilter,
): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
} else {
    @Suppress("UnspecifiedRegisterReceiverFlag")
    registerReceiver(receiver, filter)
}
