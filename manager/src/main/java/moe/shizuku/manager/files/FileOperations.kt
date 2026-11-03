package moe.shizuku.manager.files

import androidx.annotation.StringRes
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import moe.shizuku.manager.utils.AppStrings
import moe.shizuku.manager.R

/**
 * The things done *to* files, as opposed to with them.
 *
 * All of it runs here rather than in the service, which is a decision worth stating: the service
 * hands over descriptors and nothing else, so a copy is one process reading and writing through
 * two of them. That is what makes progress and cancelling possible at all - the app knows how far
 * along it is because it is the one moving the bytes - where a copy implemented in the service
 * would be a blocking call with nothing to report until it finished.
 *
 * Every operation stops at the first thing it cannot do and says which. A half-finished copy or a
 * zip missing its last entry is worse than a failure, because a failure is a thing a person can
 * see and do something about.
 */
object FileOperations {

    /** How much is moved between the two descriptors at a time. */
    private const val Chunk = 64 * 1024

    enum class Stage { COPYING, MOVING, DELETING, COMPRESSING, EXTRACTING }

    /** How far along an operation is, in bytes where bytes are being moved and in files where not. */
    data class Progress(
        val stage: Stage,
        val name: String,
        val done: Long,
        val total: Long
    ) {
        /** 0f..1f, or null where nothing knows the total - a delete, and a walk not yet finished. */
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    sealed interface Outcome {
        data object Done : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** What a walk found: how much is under these paths, and how many files. */
    private data class Scale(val bytes: Long, val files: Int)

    suspend fun copy(
        paths: List<String>,
        into: String,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = run(R.string.files_err_copy) {
        val jobs = plan(paths, into)
        val scale = jobs.fold(Scale(0, 0)) { acc, (from, _) -> acc + weigh(from, cancelled) }
        var moved = 0L
        for ((from, to) in jobs) {
            if (cancelled()) return@run Outcome.Cancelled
            moved += transfer(from, to, moved, scale.bytes, Stage.COPYING, onProgress, cancelled)
                ?: return@run Outcome.Cancelled
        }
        Outcome.Done
    }

    /**
     * Moves, by renaming where it can.
     *
     * A rename is instant and a copy is not, and the only thing that tells them apart is the
     * filesystem: the service is asked first, and only when it says no does the slow path run.
     */
    suspend fun move(
        paths: List<String>,
        into: String,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = run(R.string.files_err_move) {
        val jobs = plan(paths, into)
        val scale = jobs.fold(Scale(0, 0)) { acc, (from, _) -> acc + weigh(from, cancelled) }
        var moved = 0L

        for ((from, to) in jobs) {
            if (cancelled()) return@run Outcome.Cancelled
            val renamed = runCatching { PrivilegedFiles.rename(from, to) }.getOrDefault(false)
            if (renamed) {
                moved += weigh(from, cancelled).bytes
                onProgress(Progress(Stage.MOVING, name(to), moved, scale.bytes))
                continue
            }
            // Another filesystem, or a rename the platform refused: same work as a copy, with the
            // source taken away afterwards - and only afterwards, so a failed move leaves the
            // file where it was.
            val copied = transfer(from, to, moved, scale.bytes, Stage.MOVING, onProgress, cancelled)
                ?: return@run Outcome.Cancelled
            moved += copied
            if (!runCatching { PrivilegedFiles.delete(from) }.isSuccess) {
                return@run Outcome.Failed(AppStrings.get(R.string.files_err_copied_not_removed, from))
            }
        }
        Outcome.Done
    }

    suspend fun remove(paths: List<String>, onProgress: (Progress) -> Unit): Outcome = run(R.string.files_err_remove) {
        paths.forEachIndexed { index, path ->
            onProgress(Progress(Stage.DELETING, name(path), index.toLong(), paths.size.toLong()))
            if (!runCatching { PrivilegedFiles.delete(path) }.isSuccess) {
                return@run Outcome.Failed(AppStrings.get(R.string.files_err_remove_one, name(path)))
            }
        }
        Outcome.Done
    }

    /**
     * Writes a zip of everything under [paths].
     *
     * Entry names are relative to each source's own parent, which is how a zip made from a folder
     * unpacks into that folder rather than into a pile of loose files.
     */
    suspend fun compress(
        paths: List<String>,
        target: String,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = run(R.string.files_err_compress) {
        val scale = paths.fold(Scale(0, 0)) { acc, path -> acc + weigh(path, cancelled) }
        var written = 0L

        ParcelFileDescriptor.AutoCloseOutputStream(PrivilegedFiles.openWrite(target)).use { out ->
            ZipOutputStream(out).use { zip ->
                for (source in paths) {
                    val root = File(source).parent.orEmpty()
                    val entries = ArrayList<Pair<String, Boolean>>()
                    collect(source, cancelled) { path, directory, _ ->
                        entries.add(path to directory)
                    }
                    if (cancelled()) return@run Outcome.Cancelled

                    for ((path, directory) in entries) {
                        if (cancelled()) return@run Outcome.Cancelled
                        val entryName = path.removePrefix(root).removePrefix("/")
                        if (entryName.isEmpty()) continue

                        if (directory) {
                            // The trailing slash is what makes an unzipper see a directory rather
                            // than an empty file.
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                            continue
                        }

                        zip.putNextEntry(ZipEntry(entryName))
                        PrivilegedFiles.openRead(path).use { descriptor ->
                            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                written += pump(input, zip, cancelled)
                                    ?: return@run Outcome.Cancelled
                            }
                        }
                        zip.closeEntry()
                        onProgress(Progress(Stage.COMPRESSING, name(path), written, scale.bytes))
                    }
                }
            }
        }
        Outcome.Done
    }

    /** Unpacks [archive] into [into], refusing any entry that tries to escape it. */
    suspend fun extract(
        archive: String,
        into: String,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean
    ): Outcome = run(R.string.files_err_extract) {
        if (!PrivilegedFiles.exists(into)) PrivilegedFiles.mkdir(into)

        PrivilegedFiles.openRead(archive).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                ZipInputStream(input).use { zip ->
                    var done = 0L
                    while (true) {
                        if (cancelled()) return@run Outcome.Cancelled
                        val entry = zip.nextEntry ?: break
                        val entryName = entry.name

                        // Zip-slip: an archive may name `../../etc/passwd`, and following that
                        // name is how extracting a harmless-looking zip writes outside the folder
                        // it was pointed at.
                        val target = join(into, entryName)
                        if (!isInside(into, target)) {
                            return@run Outcome.Failed(AppStrings.get(R.string.files_err_zip_outside, entryName, name(into)))
                        }

                        if (entry.isDirectory) {
                            runCatching { PrivilegedFiles.mkdir(target) }
                        } else {
                            ParcelFileDescriptor.AutoCloseOutputStream(
                                PrivilegedFiles.openWrite(target)
                            ).use { out -> done += pump(zip, out, cancelled) ?: return@run Outcome.Cancelled }
                        }
                        zip.closeEntry()
                        onProgress(Progress(Stage.EXTRACTING, name(entryName), done, entry.size.coerceAtLeast(0)))
                    }
                }
            }
        }
        Outcome.Done
    }

    // ---- the parts underneath ----------------------------------------------------

    /** Where each source goes when copied into [into], named to avoid what is already there. */
    private fun plan(paths: List<String>, into: String): List<Pair<String, String>> {
        val taken = HashSet<String>()
        return paths.map { from ->
            val wanted = join(into, name(from))
            var candidate = wanted
            var counter = 1
            while (candidate in taken || PrivilegedFiles.exists(candidate)) {
                candidate = "$wanted ($counter)"
                counter++
            }
            taken.add(candidate)
            from to candidate
        }
    }

    /**
     * Copies one path onto another, recursing into directories.
     *
     * Returns the bytes moved, or null if it was cancelled - a count rather than a flag because
     * the caller is keeping the running total the progress bar reads.
     */
    private fun transfer(
        from: String,
        to: String,
        alreadyMoved: Long,
        total: Long,
        stage: Stage,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean
    ): Long? {
        val entry = entryOf(from)
        if (entry == null) return if (cancelled()) null else fail(from)

        if (!entry.directory) {
            PrivilegedFiles.openRead(from).use { source ->
                ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                    PrivilegedFiles.openWrite(to).use { sink ->
                        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { output ->
                            val moved = pump(input, output, cancelled) ?: return null
                            onProgress(Progress(stage, name(from), alreadyMoved + moved, total))
                            return moved
                        }
                    }
                }
            }
        }

        if (!PrivilegedFiles.exists(to) && !runCatching { PrivilegedFiles.mkdir(to) }.isSuccess) {
            return fail(from)
        }

        var moved = 0L
        for (child in PrivilegedFiles.list(from)) {
            if (cancelled()) return null
            val childFrom = join(from, child.name)
            val childTo = join(to, child.name)
            val childMoved = transfer(
                childFrom,
                childTo,
                alreadyMoved + moved,
                total,
                stage,
                onProgress,
                cancelled
            ) ?: return null
            moved += childMoved
        }
        return moved
    }

    /** What the parent believes is at [path], or null when the parent cannot say. */
    private fun entryOf(path: String): PrivilegedFiles.Entry? = runCatching {
        PrivilegedFiles.list(File(path).parent ?: "/").firstOrNull { it.name == name(path) }
    }.getOrNull()

    /** A file that vanished between the listing and the copy is a failure, not a silent skip. */
    private fun fail(path: String): Nothing = throw IOException("$path is no longer there")

    /** How much is under a path, counted so a progress bar has a denominator. */
    private fun weigh(path: String, cancelled: () -> Boolean): Scale {
        if (cancelled()) return Scale(0, 0)
        val entry = entryOf(path) ?: return Scale(0, 0)
        if (!entry.directory) return Scale(entry.size, 1)

        var scale = Scale(0, 0)
        runCatching {
            PrivilegedFiles.list(path).forEach { child ->
                if (cancelled()) return@forEach
                scale += weigh(join(path, child.name), cancelled)
            }
        }
        return scale
    }

    /** Every path under one, deepest last, with what each of them is. */
    private fun collect(path: String, cancelled: () -> Boolean, onEach: (String, Boolean, Long) -> Unit) {
        if (cancelled()) return
        val entry = entryOf(path) ?: return
        onEach(path, entry.directory, entry.size)
        if (!entry.directory) return

        runCatching {
            PrivilegedFiles.list(path).forEach { child ->
                if (cancelled()) return@forEach
                collect(join(path, child.name), cancelled, onEach)
            }
        }
    }

    private inline fun run(@StringRes what: Int, block: () -> Outcome): Outcome = try {
        block()
    } catch (e: IOException) {
        Outcome.Failed(e.message ?: AppStrings.get(what))
    } catch (t: Throwable) {
        Outcome.Failed(t.message ?: AppStrings.get(what))
    }

    /** Moves bytes until the input ends, reporting how many, or null when it was cancelled. */
    private fun pump(input: InputStream, output: OutputStream, cancelled: () -> Boolean): Long? {
        val buffer = ByteArray(Chunk)
        var moved = 0L
        while (true) {
            if (cancelled()) return null
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            moved += read
        }
        // Flushed rather than left to close: a zip is finished by its own close, and a stream that
        // is still holding the tail is how the last chunk goes missing.
        output.flush()
        return moved
    }

    private fun isInside(into: String, target: String): Boolean {
        val parent = File(into).canonicalPathOrNull() ?: return false
        val child = File(target).canonicalPathOrNull() ?: return false
        return child == parent || child.startsWith("$parent/")
    }

    private fun File.canonicalPathOrNull(): String? = runCatching { canonicalPath }.getOrNull()

    private operator fun Scale.plus(other: Scale) = Scale(bytes + other.bytes, files + other.files)

    private fun name(path: String): String = path.substringAfterLast('/')

    private fun join(parent: String, child: String): String =
        if (parent.endsWith("/")) parent + child else "$parent/$child"
}
