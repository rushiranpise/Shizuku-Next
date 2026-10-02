package moe.shizuku.manager.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.ApkUtils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object UpdateHelper {
    private val app = ShizukuApplication.application
    private val appContext = ShizukuApplication.appContext

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val commit: Int = 0,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(
                this,
                other,
                { it.major },
                { it.minor },
                { it.patch },
                { it.commit },
            )

        override fun toString(): String =
            if (commit == 0)  "$major.$minor.$patch" else "$major.$minor.$patch.r$commit"

        companion object {
            fun parse(tag: String): Version? {
                val regex = Regex("""(\d+)\.(\d+)\.(\d+)(?:\.r(\d+))?""")
                val match = regex.find(tag) ?: return null
                val (major, minor, patch, commit) = match.destructured
                return Version(
                    major.toInt(),
                    minor.toInt(),
                    patch.toInt(),
                    commit.toIntOrNull() ?: 0
                )
            }
        }
    }

    data class Release(
        val version: Version,
        val filename: String,
        val url: String,
        val digest: String
    )

    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val prerelease: Boolean,
        val assets: List<GitHubAsset>
    )

    @Serializable
    data class GitHubAsset(
        val name: String,
        val browser_download_url: String,
        val digest: String
    )

    private lateinit var latestRelease: Release
    private var fetchedAt = 0L

    /**
     * The outcome of one check. Keeping the failure separate from "nothing newer" is what
     * lets the UI say why a check did not happen - a rate-limited or offline check used to
     * report "You already have the latest version".
     */
    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Available(val release: Release) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /** A check that failed for a reason worth showing the user, unlike a plain [Exception]. */
    private class UpdateCheckException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private fun showToast(message: CharSequence, long: Boolean = false) =
        Toast
            .makeText(
                appContext,
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            )
            .show()

    suspend fun checkAndInstallUpdates() {
        when (val result = check()) {
            is CheckResult.Available -> update()
            is CheckResult.UpToDate -> showToast(appContext.getString(R.string.update_latest_installed))
            is CheckResult.Failed -> showToast(result.message, long = true)
        }
    }

    fun isCheckForUpdatesEnabled(): Boolean = ShizukuSettings.getUpdateMode() != ShizukuSettings.UpdateMode.OFF

    /** A background check: it reports through its result, so it never toasts on its own. */
    suspend fun isNewUpdateAvailable(): Boolean {
        val lastPromptedVersion =
            Version.parse(ShizukuSettings.getLastPromptedVersion())
                ?: Version.parse(getVersionName())
                ?: return false
        val result = check()
        return result is CheckResult.Available && result.release.version > lastPromptedVersion
    }

    /** A background check as well, used as [update]'s guard; failures are swallowed. */
    suspend fun isUpdateAvailable(): Boolean = check() is CheckResult.Available

    /**
     * Asks GitHub for the newest release and compares it with the running version. Every
     * failure is turned into a message instead of an exception so callers can tell a real
     * "up to date" apart from a check that never happened.
     */
    private suspend fun check(): CheckResult =
        try {
            val current = Version.parse(getVersionName())
            val latest = fetchLatestRelease()
            if (current == null || latest.version <= current) CheckResult.UpToDate
            else CheckResult.Available(latest)
        } catch (e: UpdateCheckException) {
            CheckResult.Failed(e.message ?: appContext.getString(R.string.update_check_failed))
        } catch (e: Exception) {
            CheckResult.Failed(appContext.getString(R.string.update_check_failed))
        }

    fun updateLastPromptedVersion() = ShizukuSettings.setLastPromptedVersion(latestRelease.version.toString())

    suspend fun update() {
        if (!::latestRelease.isInitialized && !isUpdateAvailable()) return

        Toast
            .makeText(
                appContext,
                appContext.getString(R.string.update_downloading),
                Toast.LENGTH_SHORT,
            ).show()

        val apk =
            latestRelease.download()?.run {
                val pm = appContext.packageManager
                val apkPackageName = pm.getPackageArchiveInfo(
                    this.path, 0
                )?.packageName
                if (app.packageName != apkPackageName) {
                    try {
                        android.util.Log.d("UpdateHelper", "Changing package name from $apkPackageName to ${app.packageName}")
                        changePackageName(app.packageName)
                    } catch (e: Exception) {
                        Toast
                            .makeText(
                                appContext,
                                appContext.getString(R.string.update_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        return@update
                    }
                } else {
                    this
                }
            }
        if (apk == null) {
            Toast
                .makeText(
                    appContext,
                    appContext.getString(R.string.update_download_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            return
        }

        appContext.installPackage(apk) { isSuccess, _ ->
            val toastMsg =
                if (isSuccess) appContext.getString(R.string.update_success)
                else appContext.getString(R.string.update_failed)
            Toast.makeText(appContext, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * How long a successful response is reused for. The home screen asks on every visit and
     * GitHub allows 60 unauthenticated requests an hour per address, so without this a
     * handful of tab switches is enough to earn a rate limit.
     */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private suspend fun fetchLatestRelease(useCache: Boolean = true): Release =
        withContext(Dispatchers.IO) {
            if (useCache && ::latestRelease.isInitialized &&
                System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS
            ) {
                return@withContext latestRelease
            }

            try {
                requestLatestRelease().also {
                    latestRelease = it
                    fetchedAt = System.currentTimeMillis()
                }
            } catch (e: UpdateCheckException) {
                throw e
            } catch (e: IOException) {
                // No network, DNS failure, TLS trouble: the check never reached GitHub.
                throw UpdateCheckException(appContext.getString(R.string.update_check_offline), e)
            }
        }

    private fun requestLatestRelease(): Release {
        val url = "https://api.github.com/repos/rushiranpise/Shizuku-Next/releases"
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                // An exhausted rate limit comes back as 403 (or 429) with a JSON object
                // describing the limit rather than the release list, so the body cannot be
                // decoded as one. Say so instead of reporting a generic failure.
                if (response.code == 403 || response.code == 429) {
                    throw UpdateCheckException(rateLimitMessage(response))
                }
                throw UpdateCheckException(
                    appContext.getString(R.string.update_check_http_error, response.code)
                )
            }

            val releases =
                try {
                    json.decodeFromString<List<GitHubRelease>>(body)
                } catch (e: SerializationException) {
                    throw UpdateCheckException(
                        appContext.getString(R.string.update_check_failed), e
                    )
                }

            val filtered =
                if (ShizukuSettings.getUpdateMode() == ShizukuSettings.UpdateMode.BETA) {
                    releases
                } else {
                    releases.filter { !it.prerelease }
                }

            return filtered
                .mapNotNull { release ->
                    val version =
                        Version.parse(release.tag_name)
                            ?: return@mapNotNull null
                    // Each release carries the debug APK as well, and the API lists it
                    // first, so prefer the one that is not debug: it is the only one
                    // signed with the release key and able to install over this build.
                    val asset =
                        release.assets.firstOrNull {
                            it.name.endsWith(".apk") && !it.name.endsWith("-debug.apk")
                        } ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
                            ?: return@mapNotNull null

                    Release(
                        version = version,
                        filename = asset.name,
                        url = asset.browser_download_url,
                        digest = asset.digest
                    )
                }.maxByOrNull { it.version }
                ?: throw UpdateCheckException(
                    appContext.getString(R.string.update_check_no_release)
                )
        }
    }

    private fun rateLimitMessage(response: Response): String {
        val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()
        if (reset != null) {
            val minutes = ((reset * 1000 - System.currentTimeMillis()) / 60_000L)
            if (minutes in 1L..120L) {
                return appContext.getString(R.string.update_check_rate_limited_minutes, minutes)
            }
        }
        return appContext.getString(R.string.update_check_rate_limited)
    }

    private suspend fun Release.download(): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            val apkFile = File(appContext.cacheDir, filename)
            apkFile.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
            }

            val downloadedDigest = "sha256:" + apkFile.sha256()
            if (downloadedDigest != digest)
                throw SecurityException("Digest of downloaded file does not match the one reported by GitHub")

            apkFile
        }

    fun File.sha256(): String {
        val bytes = readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

}
