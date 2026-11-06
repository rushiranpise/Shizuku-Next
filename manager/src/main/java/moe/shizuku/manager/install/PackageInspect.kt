package moe.shizuku.manager.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.files.PrivilegedFiles
import moe.shizuku.manager.utils.Diag

/**
 * What a package says about itself, before any of it is installed.
 *
 * Everything here comes out of the APK: the name it will install as, its version, the Android it
 * was built for, and what it will ask for. That order matters - knowing that a file is version
 * 1.4.3.6 built for Android 12 and wants the camera is what decides whether to install it at all,
 * and after the install the decision has already been made.
 *
 * The parsing is the platform's own [PackageManager.getPackageArchiveInfo], which is the only
 * thing that reads a manifest the way the installer will. It takes a *path*, though, and this app
 * cannot read the paths it deals in - that is the whole reason it has a shell - so the package is
 * copied through one of the shell's descriptors into a file this app owns, read there, and the
 * copy thrown away. The alternative is a binary manifest parser of our own, which is a great deal
 * of code to arrive at the same answer by a different route.
 *
 * The copy is the price of showing this before installing. It is made in the app's own cache -
 * the one place nothing has to be asked about, and nothing is left behind.
 */
object PackageInspect {

    private const val TAG = "PackageInspect"

    /** What a package is, as its own manifest declares it. */
    data class Details(
        val label: String?,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val targetSdk: Int,
        val minSdk: Int,
        val permissions: List<String>
    )

    /** What the device already has of a package. */
    data class Installed(val versionName: String?, val versionCode: Long?)

    /**
     * The installed copy of [packageName], or null when there is none.
     *
     * Asked of the shell rather than of this app, which cannot see other packages: it declares no
     * `QUERY_ALL_PACKAGES` and has no business doing so, while the shell can be asked about any
     * package on the phone. `dumpsys` answers "Unable to find package" for one that is not there,
     * which is the answer this needs - the interesting case is the absence, not the presence.
     */
    suspend fun installed(packageName: String): Installed? = withContext(Dispatchers.IO) {
        if (packageName.isEmpty()) return@withContext null

        val output = runCatching {
            PrivilegedFiles.exec(
                "dumpsys package ${quoted(packageName)} 2>/dev/null | " +
                    "grep -E 'versionName=|versionCode=' | head -2"
            )
        }.getOrNull() ?: return@withContext null

        val code = Regex("versionCode=(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull()
        val name = Regex("versionName=(\\S+)").find(output)?.groupValues?.get(1)
        if (code == null && name == null) null else Installed(name, code)
    }

    /** Single-quoted, with a quote inside closed and reopened, which is what a shell needs. */
    private fun quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Reads [path], or null when it cannot be read as a package at all. */
    suspend fun read(context: Context, path: String): Details? = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS

        // Named for the file rather than the package: a name that is the same for every read would
        // be a collision if two packages were ever looked at at once, and this is called from a
        // screen that can be left mid-read.
        val copy = File(context.cacheDir, "inspect-${System.nanoTime()}.apk")
        val base = File(context.cacheDir, "inspect-${System.nanoTime()}-base.apk")

        try {
            copyThroughDescriptor(path, copy)
            // A split bundle is a zip of apks, not an apk itself. The platform cannot read one as
            // a package, so the base module is taken out and read on its own.
            val packageFile = if (isBundle(copy)) {
                if (!extractBase(copy, base)) return@withContext null
                base
            } else {
                copy
            }
            val info = manager.getPackageArchiveInfo(packageFile.absolutePath, flags)
                ?: return@withContext null

            // The label lives in the package's own resources, so it is only readable once the
            // platform has been told where the package is - which is the copy, while it lasts.
            val application = info.applicationInfo
            if (application != null) {
                application.sourceDir = packageFile.absolutePath
                application.publicSourceDir = packageFile.absolutePath
            }

            Details(
                label = runCatching { application?.loadLabel(manager)?.toString() }.getOrNull(),
                packageName = info.packageName.orEmpty(),
                versionName = info.versionName,
                versionCode = versionCodeOf(info),
                targetSdk = application?.targetSdkVersion ?: 0,
                minSdk = application?.minSdkVersion ?: 0,
                permissions = info.requestedPermissions?.toList().orEmpty()
            )
        } catch (t: Throwable) {
            Diag.warn(TAG, "reading ${path.substringAfterLast('/')} failed", t)
            null
        } finally {
            // The copies exist to be parsed and nothing else; a cache full of other people's
            // packages is a cache that is never cleaned by anybody.
            runCatching { copy.delete() }
            runCatching { base.delete() }
        }
    }

    /** Whether [file] is a zip holding apks rather than an apk itself. */
    private fun isBundle(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            // An apk has its own manifest at the root. A bundle does not: its apks do.
            if (zip.getEntry("AndroidManifest.xml") != null) return false

            val entries = zip.entries()
            var found = false
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
    }.getOrDefault(false)

    /**
     * Extracts the base module from [bundle] into [into].
     *
     * The names are the only map a bundle gives: the base is `base.apk` in an APKM, the package's
     * own name in an XAPK, and `base-master.apk` in an APKS from bundletool. Config splits are
     * deliberately skipped by the fallback, since one of them is not a package on its own.
     */
    private fun extractBase(bundle: File, into: File): Boolean {
        ZipFile(bundle).use { zip ->
            val entry = baseEntry(zip) ?: return false
            zip.getInputStream(entry).use { input ->
                FileOutputStream(into).use { output -> input.copyTo(output, COPY_CHUNK) }
            }
            return true
        }
    }

    private fun baseEntry(zip: ZipFile): ZipEntry? {
        val apks = ArrayList<ZipEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) apks.add(entry)
        }

        fun name(entry: ZipEntry): String =
            entry.name.substringAfterLast('/').lowercase(Locale.ROOT)

        return apks.firstOrNull { name(it) == "base.apk" }
            ?: apks.firstOrNull { name(it) == "base-master.apk" }
            ?: apks.firstOrNull { name(it).startsWith("base-") }
            ?: apks.firstOrNull {
                val entryName = name(it)
                !entryName.startsWith("config.") && !entryName.startsWith("split_")
            }
            ?: apks.firstOrNull()
    }

    /**
     * The version code, which stopped being an int in Android 9.
     *
     * Read the new way where it exists and the old way where it does not, rather than the new way
     * everywhere: this app runs back to Android 7, and the call that would be a one-liner here
     * would be a NoSuchMethodError on those phones.
     */
    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    /** Streams the package from its own path, wherever that path can only be read as the shell. */
    private fun copyThroughDescriptor(path: String, into: File) {
        PrivilegedFiles.openRead(path).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(into).use { output -> input.copyTo(output, COPY_CHUNK) }
            }
        }
    }

    private const val COPY_CHUNK = 256 * 1024

    /** Android's version numbering beside the number itself, for the one line that wants both. */
    fun androidName(sdk: Int): String = when (sdk) {
        0 -> ""
        24, 25 -> "Nougat"
        26, 27 -> "Oreo"
        28 -> "Pie"
        29 -> "10"
        30 -> "11"
        31, 32 -> "12"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        37 -> "17"
        else -> ""
    }

    /** The label, or the package name when the package has no name of its own to show. */
    fun title(details: Details, fallback: String): String =
        details.label?.takeIf { it.isNotBlank() } ?: details.packageName.ifEmpty { fallback }
}
