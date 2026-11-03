package moe.shizuku.manager.utils.ApkUtils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.STUB_DEX_ASSET
import moe.shizuku.manager.utils.renameHiddenCopy
import moe.shizuku.manager.utils.renamePackage
import moe.shizuku.manager.utils.stubApkModule
import moe.shizuku.manager.utils.ApkSigner
import java.io.File
import moe.shizuku.manager.utils.AppStrings

private const val TAG = "ApkUtils"

const val ORIGINAL_PACKAGE_NAME = "moe.shizuku.privileged.api"

/**
 * The version code the stub is installed with.
 *
 * Above every version of the original app there can ever be, which is the point: the stub exists to
 * hold the original package name while the real app is hidden under another one, and anything the
 * store could publish above it would be offered as an update for a package it does not own. Being
 * over Google Play's own limit (2,100,000,000) is what makes that permanently true.
 */
private const val STUB_VERSION_CODE = Int.MAX_VALUE

private val app = ShizukuApplication.application
private val appContext = ShizukuApplication.appContext

val workDir by lazy {
    File(appContext.cacheDir, "patcher").also {
        it.deleteRecursively()
        it.mkdirs()
    }
}

fun File.changePackageName(
    newPkgName: String,
    maybeCreateSigningKey: Boolean = false,
    displayName: String? = null,
): File {
    Log.i(TAG, "Loading APK")
    val module = ApkModule.loadApkFile(this)
    // A module whose manifest could not be parsed comes back with none, and passing that on ends
    // in an NPE from somewhere inside the library rather than in a sentence the screen can show.
    val manifest = module.androidManifest
        ?: throw IllegalStateException(AppStrings.get(R.string.apk_err_manifest_unreadable))

    Log.i(TAG, "Changing package name")
    val oldPkgName = manifest.packageName
        ?: throw IllegalStateException(AppStrings.get(R.string.apk_err_manifest_no_package))

    // Renaming the package renames the app but not everything it is called by. Intent filter
    // actions (`<pkg>.START`, `.STOP`, the watchdog actions, the two request actions), the
    // non-exported-receiver permission and provider authorities are all strings in this same
    // manifest that carry the old name, and leaving them is what makes a hidden copy unreachable:
    // its own actions match no filter, and the actions that do match are rejected by code that
    // compares against the runtime package name (issue #86). Authorities used to be the only one
    // handled here; the walk below does all of them, and is what makes the copy whole. The new
    // name goes on in [renamePackage], after that walk, and not here before it: see there for what
    // writing it first did to every hidden copy (issue #94).
    Log.i(TAG, "Rewriting what the package name is written into")
    val rewritten = manifest.renamePackage(oldPkgName, newPkgName)
    Log.i(TAG, "Rewrote $rewritten manifest values that carried $oldPkgName")

    // The name the copy is listed under, when the user asked for one - and after the rewrite, not
    // before it: whatever they typed is theirs, and a name that happens to begin with the old
    // package name is not something to rewrite a second time.
    if (manifest.renameHiddenCopy(displayName)) {
        Log.i(TAG, "The copy is called $displayName in the launcher")
    }

    Log.i(TAG, "Inserting signing key")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    val keystore = ApkSigner.keystoreFile
    val keyInputSource = FileInputSource(keystore, "assets/${keystore.name}")
    module.add(keyInputSource)

    val outFile = File(workDir, "signed.apk")
    return module.buildAndSign(outFile)
}

/**
 * The APK installed at [pkgName] while the app is hidden, which passes on what clients that still
 * name the app by that package ask for.
 *
 * [targetPackage] is the running copy and is written into the stub, so [pkgName] must be a name
 * other than the app's own - the stub has nothing to pass anything to otherwise, and says so.
 *
 * The contents are built in [stubApkModule] and only signed here, because the signing key and the
 * file to write live on the device and the manifest does not: see that function for what the stub
 * declares and why it declares so little.
 */
fun createStubApk(pkgName: String, targetPackage: String): File {
    Log.i(TAG, "Assembling the stub APK for $targetPackage")
    val outFile = File(appContext.filesDir, "stub.apk")

    val dex = appContext.assets.open(STUB_DEX_ASSET).use { it.readBytes() }

    val module =
        stubApkModule(
            dex = dex,
            pkgName = pkgName,
            targetPackage = targetPackage,
            label = "${getAppLabel()} Stub",
            iconReference = R.drawable.ic_launcher,
            // The most this package can ever claim. The stub carries the original package name, so
            // Google Play sees that app installed and offers the store's version over it - which
            // is what issue #73 reports: an update that cannot work, and that would take the
            // stub's place if it did. A code above the store's own ceiling (2,100,000,000) is the
            // one no release can outrank, so nothing is ever offered for it again.
            versionCode = STUB_VERSION_CODE,
            // The fork's own version name rather than a made-up one: this is what the app-info
            // page and the store's row show, and a stub reading "1.0.0" is what made it look like
            // an outdated copy of the original.
            versionName = getVersionName(),
            targetSdk = app.applicationInfo.targetSdkVersion,
            minSdk = app.applicationInfo.minSdkVersion
        )

    return module.buildAndSign(outFile, maybeCreateSigningKey = true)
}

private fun ApkModule.buildAndSign(
    outFile: File,
    maybeCreateSigningKey: Boolean = false
): File {
    Log.i(TAG, "Building new APK")
    val unsignedApk = File(workDir, "unsigned.apk")
    writeApk(unsignedApk)

    Log.i(TAG, "Signing APK")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    ApkSigner.sign(unsignedApk, outFile, key)

    return outFile
}

fun getAppLabel(): String =
    app
        .applicationInfo
        .loadLabel(appContext.packageManager)
        .toString()

fun getVersionName(): String =
    appContext
        .packageManager
        .getPackageInfo(app.packageName, 0)
        .versionName
        .orEmpty()

fun buildApkFilename(): String {
    val safeLabel =
        getAppLabel()
            .lowercase()
            .replace("[^a-z0-9._-]".toRegex(), "-")

    return "$safeLabel-${getVersionName()}"
}

fun Context.installPackage(
    apk: File,
    cb: ((Boolean, String?) -> Unit)? = null
) {
    val installer = appContext.packageManager.packageInstaller

    // Asked for the install the platform can make without a dialog: an update of this app, or of
    // an app this one installed, from an app holding UPDATE_PACKAGES_WITHOUT_USER_ACTION. When the
    // platform will not do that - a fresh install, another installer's app, a throttled repeat - it
    // refuses the session outright or answers PENDING_USER_ACTION, and both are handled: the first
    // by creating it again in the shape that asks, the second by the receiver opening the dialog.
    val sessionId = runCatching { installer.createSession(silentSessionParams(apk)) }
        .getOrElse { installer.createSession(plainSessionParams(apk)) }
    val session = installer.openSession(sessionId)

    apk.inputStream().use { input ->
        session.openWrite("base.apk", 0, apk.length()).use { output ->
            input.copyTo(output)
            session.fsync(output)
        }
    }

    val pendingIntent = createInstallerPendingIntent(sessionId, cb)

    session.commit(pendingIntent.intentSender)
    session.close()
}

/**
 * A session that asks the platform to install without a dialog, where it can.
 *
 * `USER_ACTION_NOT_REQUIRED` needs `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, which is a normal
 * permission and is in the manifest; on its own that is not enough for the platform to be silent -
 * it also wants the install to be a self update or one by the app that installed the package, and
 * falls back to the dialog otherwise. `setRequestUpdateOwnership` is the same request in the newer
 * spelling: on Android 14 and up it asks to be the owner of the update, which keeps a later update
 * of the same app silent as well.
 */
private fun silentSessionParams(apk: File): PackageInstaller.SessionParams =
    plainSessionParams(apk).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setRequestUpdateOwnership(true)
        }
    }

/** A session in the platform's default shape, which asks the user. */
private fun plainSessionParams(apk: File): PackageInstaller.SessionParams =
    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setSize(apk.length())
    }

/**
 * Wakes the stub the moment it is installed.
 *
 * A package that has never been started is in the platform's "stopped" state, and a broadcast to a
 * stopped package is dropped unless the sender asked for stopped packages as well. rish does ask,
 * which is why the binder request works straight away, but an automation app does not - so a stub
 * that nothing had woken would sit there answering nothing, silently, until something else happened
 * to start it. One broadcast is enough: the platform clears the state as it delivers it. The status
 * question is the one action here that changes nothing, and its answer is thrown away - the state
 * change is the point, not the reply.
 */
fun wakeStub(context: Context, stubPackage: String) {
    val intent = Intent("$stubPackage.WATCHDOG_STATUS")
        .setPackage(stubPackage)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    runCatching {
        context.sendOrderedBroadcast(intent, null, null, null, 0, null, null)
    }
}

fun Context.uninstallPackage(
    pkgName: String,
    cb: ((Boolean, String?) -> Unit)? = null,
) {
    val installer = appContext.packageManager.packageInstaller
    val pendingIntent = createInstallerPendingIntent(0, cb)
    installer.uninstall(pkgName, pendingIntent.intentSender)
}

private var callback: ((Boolean, String?) -> Unit)? = null

val installerReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmationIntent != null) {
                        context.startActivity(confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }

                else -> {
                    val isSuccess = (status == PackageInstaller.STATUS_SUCCESS)
                    callback?.invoke(isSuccess, msg)
                    context.unregisterReceiver(this)
                }
            }
        }
    }

private fun Context.createInstallerPendingIntent(
    sessionId: Int,
    cb: ((Boolean, String?) -> Unit)? = null
): PendingIntent {
    callback = cb

    val installerAction = "${app.packageName}.INSTALLER_RESULT"
    val filter =
        IntentFilter().apply {
            addAction(installerAction)
        }
    // ContextCompat, not the plain call: the three-argument registerReceiver only exists from
    // Android 8 (API 26) and the NOT_EXPORTED flag from Android 13 (API 33); the compat helper
    // picks the right form for whichever platform is underneath.
    ContextCompat.registerReceiver(
        this,
        installerReceiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )

    val callbackIntent =
        Intent(installerAction).apply {
            setPackage(app.packageName)
        }

    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    return PendingIntent.getBroadcast(
        this,
        sessionId,
        callbackIntent,
        flags,
    )
}
