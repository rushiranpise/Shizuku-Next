package moe.shizuku.manager.manage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.newShellProcess
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Reading the device's own log, which an app cannot do for itself.
 *
 * Since Android 4.1 `READ_LOGS` is signature|privileged, so no app can read another app's log and
 * no user can grant it. Every log reader on Android borrows the privilege from somewhere - root,
 * or a shell - and this app is already holding a shell, which is why this belongs here rather
 * than in a log reader of its own.
 *
 * Two things are read at once and kept apart on purpose. The **screen** gets a ring of recent
 * lines, because a list that grew without bound would be a memory leak with a scrollbar. The
 * **file** gets everything, because a capture exists to be read afterwards and the line that
 * mattered is usually the one that has just scrolled off.
 *
 * One capture at a time, and it says so: a second start while one is running is refused rather
 * than quietly leaving a second `logcat` behind with nothing reading it.
 */
object Logcat {

    private const val TAG = "Logcat"

    /** How many lines the screen holds. The file holds every line there is. */
    private const val MAX_LINES = 3000

    /** How often the screen is handed the ring, rather than once per line. */
    private const val PUBLISH_MILLIS = 250L

    /** What a capture will not grow past, whichever comes first. */
    private const val MAX_MILLIS = 60 * 60 * 1000L
    private const val MAX_BYTES = 24L * 1024 * 1024

    /** Under the app's own directory on shared storage, where a file manager can reach it. */
    private const val DIRECTORY = "logcat"

    /** How much of the logcat buffer is picked up before the stream is followed. */
    private const val BACKFILL = 40

    /**
     * One line, as logcat's `threadtime` format says it.
     *
     * [id] exists for the list that draws these and for nothing else. A log is full of lines that
     * are identical in every field - an app logging an empty message from three threads inside one
     * millisecond - so "when, which process, which tag, what it said" does not identify a line, and
     * Compose refuses a list whose keys repeat. It did: the whole-device capture crashed the app on
     * the first frame it drew, on DMABUFHEAPS lines that say nothing at all.
     */
    data class Line(
        val at: Long,
        val level: Char,
        val pid: Int,
        val tag: String,
        val message: String,
        /** A line that says something went wrong rather than that something happened. */
        val crash: Boolean = false,
        val id: Long = 0,
        /**
         * The line as logcat wrote it, which is what goes in the file.
         *
         * Kept and written rather than the line this app built out of it: logcat's own stamp
         * carries the date, and one rebuilt from [at] carries only a time - so a capture written
         * the other way reads back with every line at the instant the capture started, and the
         * day it happened is gone.
         */
        val raw: String = ""
    ) {
        /** The shape a reader expects, for copying a line out of the list. */
        val text: String get() = "${Diag.stamp(at)} $level/$tag(${pid}): $message"
    }

    /** What a capture is of. */
    sealed interface Scope {
        /** The name a person would use for it, in a file name and on a row. */
        val label: String

        data class App(
            val packageName: String,
            override val label: String,
            val uid: Int
        ) : Scope

        data object Device : Scope {
            override val label = "device"
        }
    }

    /** A capture that is on disk, running or finished. */
    data class Session(
        val file: File,
        val scope: String,
        val packageName: String,
        val startedAt: Long,
        val bytes: Long
    ) {
        val name: String get() = file.name
    }

    /** Everything the screen draws, in one value so it cannot be read half-updated. */
    data class State(
        val running: Boolean = false,
        val scopeLabel: String = "",
        val lines: List<Line> = emptyList(),
        val crashes: Int = 0,
        val bytes: Long = 0L,
        val file: File? = null,
        /** Set when a capture stopped for a reason nobody asked for. */
        val stoppedBy: StopReason? = null
    )

    enum class StopReason { SIZE, TIME, ENDED }

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: Process? = null

    /** Hands every line an identity, so two identical ones are still two lines. */
    private val ids = AtomicLong()

    internal fun nextId(): Long = ids.incrementAndGet()

    private val ring = ArrayDeque<Line>()
    private var startedAt = 0L
    private var written = 0L
    private var crashes = 0
    private var lastPublish = 0L
    private var outFile: File? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun isRunning(): Boolean = job != null

    /**
     * Starts reading [scope] into a new file, or says no.
     *
     * False means nothing was started - one is already running, or there is no shell to run it
     * with - which is a thing the screen has to be able to say rather than show an empty list
     * that looks like a quiet device.
     */
    fun start(context: Context, scope: Scope): Boolean {
        if (job != null) return false

        val app = context.applicationContext
        val process = newShellProcess(command(scope)) ?: return false

        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, DIRECTORY)
        runCatching { dir.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$stamp-${scope.label}.log")
        val started = System.currentTimeMillis()

        if (!runCatching { file.writeText(header(scope, started)) }.isSuccess) {
            runCatching { process.destroy() }
            return false
        }

        this.process = process
        this.outFile = file
        startedAt = started
        written = 0
        crashes = 0
        lastPublish = 0
        ring.clear()
        _state.value = State(running = true, scopeLabel = scope.label, file = file)

        Diag.info(TAG, "started a capture of ${scope.label}")

        job = work.launch {
            var writer: Writer? = null
            try {
                writer = file.bufferedWriter()
                process.inputStream.bufferedReader().forEachLine { raw ->
                    val line = parse(raw, System.currentTimeMillis()).copy(id = nextId())
                    append(line, writer)

                    if (written >= MAX_BYTES) {
                        stop(StopReason.SIZE)
                    } else if (System.currentTimeMillis() - startedAt >= MAX_MILLIS) {
                        stop(StopReason.TIME)
                    }
                }
                // The stream ended on its own, which happens when the shell goes away under it.
                if (job != null) stop(StopReason.ENDED)
            } catch (tr: Throwable) {
                Diag.warn(TAG, "the capture stopped reading", tr)
                if (job != null) stop(StopReason.ENDED)
            } finally {
                runCatching { writer?.close() }
            }
        }

        return true
    }

    /** Ends the capture, and says why if it was not the user. */
    fun stop(reason: StopReason? = null) {
        val stopped = job ?: return
        job = null

        runCatching { process?.destroy() }
        process = null

        _state.value = _state.value.copy(
            running = false,
            bytes = written,
            stoppedBy = reason
        )
        Diag.info(
            TAG,
            "stopped the capture after ${written / 1024} kB" + (reason?.let { " ($it)" } ?: "")
        )

        // Nothing else uses the job; it ends when the process dies, which is what destroying it
        // above does. Cancelled as well so a reader parked on a stream that never ends is not
        // left holding a coroutine.
        stopped.cancel()
    }

    /** Forgets whatever the last capture had to say about stopping. */
    fun clearStopReason() {
        _state.value = _state.value.copy(stoppedBy = null)
    }

    // ---- reading one line --------------------------------------------------------

    /**
     * `MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: message`.
     *
     * Anything that is not that shape is kept as a line of its own rather than dropped: logcat
     * prints separators between its buffers and crash handlers print lines of their own, and a
     * reader that silently loses those is worse than one that shows them unparsed. The tag is
     * taken up to the first ": " because that is what separates it from the message, and tags are
     * padded with spaces rather than truncated.
     */
    private val LINE = Regex(
        "^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+" +
            "([VDIWEFAS])\\s+(.*?):\\s?(.*)$"
    )

    fun parse(raw: String, at: Long): Line {
        val match = LINE.matchEntire(raw)
            ?: return Line(at, 'I', 0, "", raw, crash = isCrash(raw), raw = raw)

        return Line(
            at = at,
            level = match.groupValues[5].firstOrNull() ?: 'I',
            pid = match.groupValues[3].toIntOrNull() ?: 0,
            // Trimmed because logcat pads a tag out to a fixed width before the colon, so
            // "Tag" arrives as "Tag     " - and a tag with trailing spaces is a tag that will
            // not match the filter somebody typed.
            tag = match.groupValues[6].trim(),
            message = match.groupValues[7],
            crash = isCrash(match.groupValues[7]),
            raw = raw
        )
    }

    /**
     * The time a saved line was written, from the stamp logcat gave it.
     *
     * That stamp is a month and a day rather than a year - `10-01 12:27:31.811` - so the year is
     * taken from the capture it is in. Falls back to the capture's own start when the line has no
     * stamp, which is the best that can be said about it.
     */
    private fun stampOf(raw: String, fallback: Long): Long {
        val parsed = runCatching { STAMP.parse(raw.take(18)) }.getOrNull() ?: return fallback
        return Calendar.getInstance().apply {
            timeInMillis = parsed.time
            set(Calendar.YEAR, Calendar.getInstance().apply { timeInMillis = fallback }.get(Calendar.YEAR))
        }.timeInMillis
    }

    /**
     * The lines that say something went wrong.
     *
     * A crash is not one line: `FATAL EXCEPTION` is followed by the stack, and a native one is a
     * `Fatal signal` line with the frames after it. Flagging the line that announces it is what
     * makes "jump to the next crash" possible without understanding the frames.
     */
    private val CRASH_MARKERS = listOf(
        "FATAL EXCEPTION",
        "Fatal signal",
        "ANR in ",
        "beginning of crash",
        "Process: ",
        "signal 6",
        "signal 11"
    )

    fun isCrash(message: String): Boolean =
        CRASH_MARKERS.any { message.contains(it, ignoreCase = false) }

    // ---- the capture itself ------------------------------------------------------

    /**
     * The command for [scope].
     *
     * `--uid` rather than `--pid`: an app's log is its processes', it usually has more than one,
     * and its pid changes every time it restarts - which, while somebody is reproducing a crash,
     * is often exactly what it just did.
     *
     * `-T` picks up the last few lines and then follows, so a capture starts with the context it
     * needs rather than at a blank line.
     */
    private fun command(scope: Scope): String = when (scope) {
        is Scope.App -> "logcat -v threadtime --uid=${scope.uid} -T $BACKFILL"
        Scope.Device -> "logcat -v threadtime -T $BACKFILL"
    }

    /** The first line of the file, so a capture read back says what it was of. */
    private fun header(scope: Scope, startedAt: Long): String {
        val packageName = (scope as? Scope.App)?.packageName.orEmpty()
        return "$MARK scope=${scope.label} package=$packageName started=$startedAt\n"
    }

    private const val MARK = "#shizuku-logcat"

    /** The shape logcat stamps a line with, without the year - which comes from the capture. */
    private val STAMP = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun append(line: Line, writer: Writer?) {
        ring.addLast(line)
        while (ring.size > MAX_LINES) ring.removeFirst()
        if (line.crash) crashes++

        // The file gets the line as logcat wrote it, not as this app parsed it: whoever reads it
        // afterwards may have a better parser, and its own stamp carries the date, which the one
        // this app builds out of it does not.
        val text = (line.raw.ifEmpty { line.text }) + "\n"
        written += text.length
        runCatching {
            writer?.write(text)
            writer?.flush()
        }

        val now = System.currentTimeMillis()
        if (now - lastPublish >= PUBLISH_MILLIS) publish(now)
    }

    private fun publish(now: Long) {
        lastPublish = now
        _state.value = _state.value.copy(
            lines = ring.toList(),
            crashes = crashes,
            bytes = written
        )
    }

    // ---- what is on disk ---------------------------------------------------------

    fun directory(context: Context): File {
        val app = context.applicationContext
        return File(app.getExternalFilesDir(null) ?: app.filesDir, DIRECTORY)
    }

    /** Every capture there is, newest first. */
    fun sessions(context: Context): List<Session> {
        val dir = directory(context)
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()

        return files
            .filter { it.isFile && it.name.endsWith(".log") }
            .sortedByDescending { it.name }
            .map { file ->
                val head = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()
                val scope = head?.substringAfter("scope=", "")?.substringBefore(' ') ?: ""
                val packageName = head?.substringAfter("package=", "")?.substringBefore(' ') ?: ""
                val started = head?.substringAfter("started=", "")?.substringBefore(' ')?.toLongOrNull()
                    ?: file.lastModified()
                Session(
                    file = file,
                    scope = scope.ifEmpty { "device" },
                    packageName = packageName,
                    startedAt = started,
                    bytes = file.length()
                )
            }
    }

    fun delete(session: Session): Boolean {
        if (isRunning() && session.file == outFile) return false
        return runCatching { session.file.delete() }.getOrDefault(false)
    }

    /**
     * A saved capture, read back as lines.
     *
     * The last [limit] of them, because the interesting end of a log is the end: a capture that
     * ran for an hour is a hundred thousand lines and the screen can hold a few thousand.
     */
    fun read(session: Session, limit: Int = MAX_LINES): List<Line> {
        val lines = mutableListOf<Line>()
        val at = session.startedAt
        runCatching {
            session.file.bufferedReader().use { reader ->
                var first = true
                var id = 0L
                reader.forEachLine { raw ->
                    if (first) {
                        // The header, which is about the capture rather than in it.
                        first = false
                        if (raw.startsWith(MARK)) return@forEachLine
                    }
                    lines.add(parse(raw, stampOf(raw, at)).copy(id = ++id))
                    if (lines.size > limit) lines.removeAt(0)
                }
            }
        }
        return lines
    }
}
