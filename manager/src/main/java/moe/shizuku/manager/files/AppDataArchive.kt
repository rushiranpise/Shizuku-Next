package moe.shizuku.manager.files

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.manage.PackageTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two pieces of an app's data that the shell can reach, as one archive.
 *
 * Android/data holds an app's external files and Android/obb its expansion files. Both are
 * readable and writable by the adb shell, which is the uid [PrivilegedFiles] runs under. Internal
 * app data is deliberately absent: the shell is denied on /data/user/0, so an archive that claimed
 * to contain it would be lying.
 *
 * The archive has a manifest, then one top-level directory per section. Import only replaces the
 * sections the manifest names: an archive made when an app had no OBB does not delete an OBB that
 * exists on the device now.
 */
object AppDataArchive {

    private const val FORMAT = "shizuku-next-app-data"
    private const val VERSION = 1

    private const val MANIFEST = "manifest.json"
    private const val DATA = "data"
    private const val OBB = "obb"

    private const val CHUNK = 64 * 1024
    private const val MAX_MANIFEST = 64 * 1024
    private const val WORKSPACE = "/data/local/tmp"

    private data class Section(val key: String, val root: String)

    private val SECTIONS = listOf(
        Section(DATA, "/sdcard/Android/data"),
        Section(OBB, "/sdcard/Android/obb")
    )
    private val KEYS = SECTIONS.map { it.key }.toSet()

    private data class Manifest(val packageName: String, val sections: List<String>)

    private class Target(val key: String, val path: String) {
        var backup: String? = null
        var touched: Boolean = false
    }

    private data class Scale(val bytes: Long, val files: Int)

    sealed interface Outcome {
        data class Done(val sections: List<String>) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    /** A name that says whose data it is and when it was taken. */
    fun fileName(packageName: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "shizuku-next-$packageName-data-$stamp.zip"
    }

    /** The sections this app has right now. Empty when Shizuku is not available. */
    fun availableSections(packageName: String): List<String> = runCatching {
        SECTIONS.filter { PrivilegedFiles.exists(pathFor(packageName, it)) }.map { it.key }
    }.getOrDefault(emptyList())

    /**
     * Writes the app's OBB and external data to [uri].
     *
     * The app is stopped first so the archive is a snapshot rather than a copy of files that are
     * being changed underneath it. The user picked [uri] through CreateDocument, so a failed or
     * cancelled export removes the partial file instead of leaving something that looks usable.
     */
    suspend fun export(
        context: Context,
        packageName: String,
        uri: Uri,
        onProgress: (FileOperations.Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = withContext(Dispatchers.IO) {
        var keep = false
        try {
            val result = exportNow(context, packageName, uri, onProgress, cancelled)
            keep = result is Outcome.Done
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Outcome.Failed(e.message ?: "could not export app data")
        } finally {
            if (!keep) deleteDocument(context, uri)
        }
    }

    private fun exportNow(
        context: Context,
        packageName: String,
        uri: Uri,
        onProgress: (FileOperations.Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome {
        if (cancelled()) return Outcome.Cancelled

        val present = SECTIONS.filter { PrivilegedFiles.exists(pathFor(packageName, it)) }
        if (present.isEmpty()) {
            return Outcome.Failed("this app has no OBB or external data")
        }

        if (!PackageTools.forceStop(context, packageName)) {
            return Outcome.Failed("could not stop the app")
        }

        val total = present.fold(0L) { sum, section ->
            sum + weigh(pathFor(packageName, section), cancelled).bytes
        }
        if (cancelled()) return Outcome.Cancelled

        val output = context.contentResolver.openOutputStream(uri)
            ?: return Outcome.Failed("could not open the chosen file")

        output.use { raw ->
            ZipOutputStream(raw).use { zip ->
                writeManifest(zip, packageName, present.map { it.key })
                onProgress(FileOperations.Progress(FileOperations.Stage.COMPRESSING, "", 0, total))

                var done = 0L
                for (section in present) {
                    val root = pathFor(packageName, section)
                    zip.putNextEntry(ZipEntry("${section.key}/"))
                    zip.closeEntry()

                    val entries = ArrayList<Pair<String, Boolean>>()
                    collect(root, cancelled) { path, directory, _ ->
                        if (path != root) entries.add(path to directory)
                    }
                    if (cancelled()) return Outcome.Cancelled

                    for ((path, directory) in entries) {
                        if (cancelled()) return Outcome.Cancelled
                        val relative = path.removePrefix(root).removePrefix("/")
                        val entryName = "${section.key}/$relative"
                        if (directory) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                            continue
                        }

                        zip.putNextEntry(ZipEntry(entryName))
                        PrivilegedFiles.openRead(path).use { descriptor ->
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                val moved = pump(input, zip, cancelled)
                                    ?: return Outcome.Cancelled
                                done += moved
                                onProgress(
                                    FileOperations.Progress(
                                        FileOperations.Stage.COMPRESSING,
                                        File(path).name,
                                        done,
                                        total
                                    )
                                )
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        return Outcome.Done(present.map { it.key })
    }

    /**
     * Replaces the sections named by an archive.
     *
     * Everything is unpacked under the shell's own directory and checked there before the target
     * is touched. Existing directories are renamed aside rather than deleted first, so a failed
     * copy can put the old data back. The app is stopped before that rename and is left stopped:
     * starting it while its files are being replaced is how a game writes over the restore.
     */
    suspend fun import(
        context: Context,
        packageName: String,
        uri: Uri,
        onProgress: (FileOperations.Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = "$WORKSPACE/shizuku-appdata-$id.zip"
        val work = "$WORKSPACE/shizuku-appdata-$id"
        var targets: MutableList<Target>? = null

        try {
            stage(context, uri, staging)
            if (cancelled()) return@withContext Outcome.Cancelled

            val manifest = readManifest(staging)
                ?: return@withContext Outcome.Failed("that file is not a Shizuku app data archive")
            if (manifest.packageName != packageName) {
                return@withContext Outcome.Failed("that archive belongs to ${manifest.packageName}")
            }
            if (cancelled()) return@withContext Outcome.Cancelled

            when (val extracted = FileOperations.extract(staging, work, onProgress, cancelled)) {
                FileOperations.Outcome.Done -> Unit
                FileOperations.Outcome.Cancelled -> return@withContext Outcome.Cancelled
                is FileOperations.Outcome.Failed ->
                    return@withContext Outcome.Failed(extracted.message)
            }

            val expected = (manifest.sections + MANIFEST).toSet()
            val top = PrivilegedFiles.list(work).map { it.name }.toSet()
            if (top != expected) {
                return@withContext Outcome.Failed("the archive does not match its manifest")
            }
            for (section in manifest.sections) {
                if (!PrivilegedFiles.exists("$work/$section")) {
                    return@withContext Outcome.Failed("the archive is missing $section")
                }
            }

            if (!PackageTools.forceStop(context, packageName)) {
                return@withContext Outcome.Failed("could not stop the app")
            }

            val targetList = manifest.sections.mapTo(ArrayList()) { key ->
                Target(key, targetFor(packageName, key))
            }
            targets = targetList

            // Rename every existing target before copying anything. If one cannot be moved, put
            // back the ones that already were and leave the device exactly as it was.
            for (target in targetList) {
                if (cancelled()) {
                    rollback(targetList)
                    return@withContext Outcome.Cancelled
                }
                if (!PrivilegedFiles.exists(target.path)) continue
                val backup = "${target.path}.shizuku-import-$id"
                if (!PrivilegedFiles.rename(target.path, backup)) {
                    rollback(targetList)
                    return@withContext Outcome.Failed("could not move ${target.path} aside")
                }
                target.backup = backup
            }

            for (target in targetList) {
                if (cancelled()) {
                    rollback(targetList)
                    return@withContext Outcome.Cancelled
                }
                val source = "$work/${target.key}"
                if (!PrivilegedFiles.exists(target.path)) {
                    PrivilegedFiles.mkdir(target.path)
                    target.touched = true
                }
                val children = PrivilegedFiles.list(source).map { "$source/${it.name}" }
                when (val copied = FileOperations.copy(children, target.path, onProgress, cancelled)) {
                    FileOperations.Outcome.Done -> Unit
                    FileOperations.Outcome.Cancelled -> {
                        rollback(targetList)
                        return@withContext Outcome.Cancelled
                    }
                    is FileOperations.Outcome.Failed -> {
                        rollback(targetList)
                        return@withContext Outcome.Failed(copied.message)
                    }
                }
            }

            // The new data is in place. The old copies are no longer needed.
            targetList.forEach { target ->
                target.backup?.let { backup -> runCatching { PrivilegedFiles.delete(backup) } }
            }
            Outcome.Done(manifest.sections)
        } catch (e: CancellationException) {
            targets?.let(::rollback)
            throw e
        } catch (e: Throwable) {
            targets?.let(::rollback)
            Outcome.Failed(e.message ?: "could not import app data")
        } finally {
            runCatching { if (PrivilegedFiles.exists(work)) PrivilegedFiles.delete(work) }
            runCatching { if (PrivilegedFiles.exists(staging)) PrivilegedFiles.delete(staging) }
        }
    }

    private fun stage(context: Context, uri: Uri, target: String) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("could not open the chosen file")
        input.use { source ->
            PrivilegedFiles.openWrite(target).use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { sink ->
                    source.copyTo(sink, CHUNK)
                }
            }
        }
    }

    private fun readManifest(path: String): Manifest? {
        var manifest: Manifest? = null
        PrivilegedFiles.openRead(path).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name == MANIFEST) {
                            if (manifest != null) return null
                            val text = readLimited(zip) ?: return null
                            manifest = parseManifest(text) ?: return null
                        } else if (!name.startsWith("$DATA/") && !name.startsWith("$OBB/")) {
                            return null
                        }
                    }
                }
            }
        }
        return manifest
    }

    private fun parseManifest(text: String): Manifest? = runCatching {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) return null
        if (root.optInt("version", -1) != VERSION) return null
        val packageName = root.optString("package")
        if (packageName.isBlank()) return null

        val array = root.optJSONArray("sections") ?: return null
        val sections = ArrayList<String>()
        for (index in 0 until array.length()) {
            val key = array.optString(index)
            if (key !in KEYS || key in sections) return null
            sections.add(key)
        }
        if (sections.isEmpty()) return null
        Manifest(packageName, sections)
    }.getOrNull()

    private fun readLimited(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (output.size() + read > MAX_MANIFEST) return null
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun writeManifest(zip: ZipOutputStream, packageName: String, sections: List<String>) {
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("package", packageName)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put("sections", JSONArray(sections))
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun collect(
        path: String,
        cancelled: () -> Boolean,
        onEach: (String, Boolean, Long) -> Unit
    ) {
        if (cancelled()) return
        val entry = entryOf(path) ?: throw IOException("$path is no longer there")
        onEach(path, entry.directory, entry.size)
        if (!entry.directory) return

        for (child in PrivilegedFiles.list(path)) {
            if (cancelled()) return
            collect(join(path, child.name), cancelled, onEach)
        }
    }

    private fun weigh(path: String, cancelled: () -> Boolean): Scale {
        if (cancelled()) return Scale(0, 0)
        val entry = entryOf(path) ?: throw IOException("$path is no longer there")
        if (!entry.directory) return Scale(entry.size, 1)

        var scale = Scale(0, 0)
        for (child in PrivilegedFiles.list(path)) {
            if (cancelled()) return scale
            val childScale = weigh(join(path, child.name), cancelled)
            scale = Scale(scale.bytes + childScale.bytes, scale.files + childScale.files)
        }
        return scale
    }

    private fun entryOf(path: String): PrivilegedFiles.Entry? = runCatching {
        PrivilegedFiles.list(File(path).parent ?: "/")
            .firstOrNull { it.name == File(path).name }
    }.getOrNull()

    private fun pump(input: InputStream, output: OutputStream, cancelled: () -> Boolean): Long? {
        val buffer = ByteArray(CHUNK)
        var moved = 0L
        while (true) {
            if (cancelled()) return null
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            moved += read
        }
        output.flush()
        return moved
    }

    private fun rollback(targets: List<Target>) {
        for (target in targets.asReversed()) {
            val backup = target.backup
            if (backup != null) {
                runCatching { PrivilegedFiles.delete(target.path) }
                runCatching { PrivilegedFiles.rename(backup, target.path) }
            } else if (target.touched) {
                runCatching { PrivilegedFiles.delete(target.path) }
            }
        }
    }

    private fun deleteDocument(context: Context, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun pathFor(packageName: String, section: Section): String {
        if (packageName.isBlank() || packageName.contains('/') || packageName.contains('\\')) {
            throw IllegalArgumentException("invalid package name")
        }
        return "${section.root}/$packageName"
    }

    private fun targetFor(packageName: String, key: String): String {
        val section = SECTIONS.first { it.key == key }
        return pathFor(packageName, section)
    }

    private fun join(parent: String, child: String): String =
        if (parent.endsWith("/")) parent + child else "$parent/$child"
}
