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
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.archive.FileInputSource
import java.util.function.Predicate
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.STUB_DEX_ASSET
import moe.shizuku.manager.utils.rewrittenPackageReference
import moe.shizuku.manager.utils.stubApkModule
import moe.shizuku.manager.utils.ApkSigner
import java.io.File

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

fun File.changePackageName(newPkgName: String, maybeCreateSigningKey: Boolean = false): File {
    Log.i(TAG, "Loading APK")
    val module = ApkModule.loadApkFile(this)
    val manifest = module.androidManifest

    Log.i(TAG, "Changing package name")
    val oldPkgName = manifest.packageName
    manifest.packageName = newPkgName

    // Renaming the package renames the app but not everything it is called by. Intent filter
    // actions (`<pkg>.START`, `.STOP`, the watchdog actions, the two request actions), the
    // non-exported-receiver permission and provider authorities are all strings in this same
    // manifest that carry the old name, and leaving them is what makes a hidden copy unreachable:
    // its own actions match no filter, and the actions that do match are rejected by code that
    // compares against the runtime package name (issue #86). Authorities used to be the only one
    // handled here; the walk below does all of them, and is what makes the copy whole.
    Log.i(TAG, "Rewriting what the package name is written into")
    val rewritten = manifest.rewritePackageReferences(oldPkgName, newPkgName)
    Log.i(TAG, "Rewrote $rewritten manifest values that carried $oldPkgName")

    Log.i(TAG, "Inserting signing key")
    val key = ApkSigner.getSigningKey(maybeCreateSigningKey)
    val keystore = ApkSigner.keystoreFile
    val keyInputSource = FileInputSource(keystore, "assets/${keystore.name}")
    module.add(keyInputSource)

    val outFile = File(workDir, "signed.apk")
    return module.buildAndSign(outFile)
}

/**
 * Every value in [this] manifest that names [oldPackage], rewritten to [newPackage], and how many
 * were rewritten.
 *
 * Walks the whole document rather than a list of known tags: the strings that carry a package name
 * sit at every level - the permission at the root, the components under `application`, the actions
 * inside their `intent-filter` children - and a new one added later should not have to be added
 * here as well. Nothing but string attribute values is touched.
 */
private fun AndroidManifestBlock.rewritePackageReferences(oldPackage: String, newPackage: String): Int {
    fun walk(element: ResXmlElement): Int {
        var count = 0

        for (index in 0 until element.attributeCount) {
            val attribute = element.getAttributeAt(index)
            val value = attribute.valueAsString ?: continue

            val rewritten = rewrittenPackageReference(value, oldPackage, newPackage)
                ?: continue
            attribute.setValueAsString(rewritten)
            count++
        }

        // The generic child iterator is the only one that can name what it returns, because the base
        // class holding the no-argument version is not public; a predicate that accepts everything
        // says the same thing.
        val children = ArrayList<ResXmlElement>()
        element.getElements(Predicate { true }).forEachRemaining { children.add(it) }
        return children.fold(count) { total, child -> total + walk(child) }
    }

    // The document's own children are the root elements: the permission this app declares, the
    // application, and the uses-permission list. That accessor is the raw one, hence the cast.
    val roots = ArrayList<ResXmlElement>()
    getElements().forEachRemaining { element -> roots.add(element as ResXmlElement) }
    return roots.fold(0) { total, root -> total + walk(root) }
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

    val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = installer.createSession(sessionParams)
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
