package moe.shizuku.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pictures for the wall, including for the contributors who are not carried in the app.
 *
 * There is no image loading library here and there does not need to be one: the wall wants circles
 * at one size, from one URL each, and it wants them to survive being offline after they have been
 * seen once. So this is a small loader - the account's own URL, and on disk whatever has been
 * fetched before.
 *
 * The wall holds well over a hundred faces, so nothing here may assume it is asked for one picture
 * at a time: decodes are downsampled to the size actually being drawn, the fetches are capped so a
 * wall does not open a hundred connections at once, and the whole thing is bounded in memory.
 */
internal object ContributorAvatars {

    private const val DISK_DIR = "contributor-avatars"

    /** Enough for a screenful at the wall's closest zoom, and no more. */
    private const val MEMORY_BYTES = 16 * 1024 * 1024

    /** Loading a wall is many small requests; this is what keeps it from being a flood. */
    private const val MAX_PARALLEL_FETCHES = 6

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** A picture is a few kilobytes. Anything this size is not one, and is not worth keeping. */
    private const val MAX_BYTES = 512 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private val fetches = Semaphore(MAX_PARALLEL_FETCHES)

    /**
     * The picture for a contributor, at roughly [targetPx] pixels across.
     *
     * Returns null when there is nothing to draw, which the wall answers with the person's initial.
     */
    suspend fun load(context: Context, contributor: Contributor, targetPx: Int): ImageBitmap? {
        val url = contributor.avatarUrl ?: return null

        val key = cacheKey(contributor, targetPx)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bitmap = readRemote(context, url, targetPx)
            bitmap?.let { memory.put(key, it) }
            bitmap
        }
    }

    private suspend fun readRemote(context: Context, url: String, targetPx: Int): ImageBitmap? {
        val file = fileFor(context, url)
        // The permit is held only for the network, so a disk hit never waits on somebody else's
        // download.
        if (!file.isFile && !fetches.withPermit { fetch(url, file, targetPx) }) return null
        if (!file.isFile) return null

        val bitmap = decode({ FileInputStream(file) }, targetPx)
        // Whatever came back was not a picture - a redirect that landed on a web page, a captive
        // portal answering for the host - and keeping it would poison this face for good.
        if (bitmap == null) file.delete()
        return bitmap
    }

    private fun fetch(url: String, target: File, targetPx: Int): Boolean = runCatching {
        // Ask for the size being drawn rather than the full-size original: at the wall's widest
        // view this turns a forty-kilobyte download into a three-kilobyte one, over a hundred
        // times.
        val sized = requestedSize(url, targetPx)
        val connection = (URL(sized).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Shizuku-Next")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return false
            val parent = target.parentFile
            parent?.mkdirs()
            val staging = File(parent, target.name + ".part")
            connection.inputStream.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) error("avatar is larger than an avatar")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            true
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /**
     * Ask GitHub's avatar host for a small copy.
     *
     * The size is a query parameter there, and the URL in the snapshot points at the original. Any
     * other host is left exactly as it was.
     */
    internal fun requestedSize(url: String, targetPx: Int): String {
        if (!url.contains("avatars.githubusercontent.com")) return url
        val size = when {
            targetPx <= 64 -> 64
            targetPx <= 128 -> 128
            targetPx <= 256 -> 256
            else -> 460
        }
        val base = url.substringBefore("?")
        val params = url.substringAfter("?", "")
            .split("&")
            .filter { it.isNotEmpty() && !it.startsWith("s=") }
        // The base keeps its own question mark: dropping it turns the query into a path, and the
        // host answers that with a web page rather than a picture.
        return "$base?" + (params + "s=$size").joinToString("&")
    }

    private fun decode(open: () -> InputStream, targetPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0) return null

        // Halve until the next halving would go under what is being drawn.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap: Bitmap = runCatching {
            open().use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null
        return bitmap.asImageBitmap()
    }

    /**
     * What a picture is held in memory under.
     *
     * A name is the last resort rather than a shared blank: two people who are both without a
     * picture must still not be one entry in the cache, or the first of them is drawn for the
     * other.
     */
    internal fun cacheKey(contributor: Contributor, targetPx: Int): String =
        "${contributor.avatarUrl ?: contributor.name}@$targetPx"

    private fun fileFor(context: Context, url: String): File {
        val directory = File(context.cacheDir, DISK_DIR).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.img")
    }

}
