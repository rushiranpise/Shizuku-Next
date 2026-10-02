package moe.shizuku.manager.starter

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbPairingHelper
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status

private class NotRootedException: Exception()

/**
 * The agent the system start abuses. It is not shipped with this app: whoever needs it
 * installs it, and whether it lands as the system uid is the difference between the
 * payload running as uid 1000 and the starter refusing it.
 */
private const val FOTA_AGENT_PACKAGE = "com.sdet.fotaagent"

/** How many times the agent is told to run the payload, and how far apart. */
private const val FOTA_ATTEMPTS = 6
private const val FOTA_ATTEMPT_INTERVAL_MS = 700L

/**
 * How the system start asks the payload to report how far it got.
 *
 * The starter leaves a log, but only from the moment it runs, and its absence cannot say
 * why: a payload that never executed, a process that could not write either of the two
 * directories, and one that wrote where the app cannot read all look identical from here.
 * The command handed to the agent is ours, so it reports to this app as it goes, one step
 * earlier and with no file involved at all: if the first of these never arrives, the agent
 * never ran the command, and nothing else about the attempt matters.
 */
/** Written by the starter at the top of every run it records. */
private const val STARTER_LOG_DIVIDER = "---- start ----"

private const val STAGE_ACTION = "moe.shizuku.privileged.api.action.SYSTEM_START_STAGE"
private const val STAGE_EXTRA = "stage"
private const val STAGE_AT_SHELL = "payload_reached_the_shell"
private const val STAGE_AFTER_STARTER = "the_starter_returned"

class StarterActivity : AppBarActivity() {

    private val viewModel: ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        val binding = StarterActivityBinding.inflate(layoutInflater, rootView, true)

        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            if (output.endsWith(Starter.serviceStartedMessage)) {
                window?.decorView?.postDelayed({
                    if (!isFinishing) finish()
                }, 3000)
            } else if (it.status == Status.ERROR) {
                var message = 0
                when (it.error) {
                    is AdbKeyException -> {
                        message = R.string.adb_error_key_store
                    }
                    is NotRootedException -> {
                        message = R.string.start_with_root_failed
                    }
                    is SocketTimeoutException -> {
                        message = R.string.cannot_connect_port
                    }
                    is ConnectException -> {
                        message = R.string.cannot_connect_port
                    }
                    // The service never appeared. Nothing in this activity can say why - the
                    // starter runs from the agent, so its output is the agent's - but its own
                    // log is on the device and this is where to look.
                    is TimeoutException -> {
                        message = R.string.start_failed_no_binder
                    }

                    is SSLProtocolException -> {
                        // Not paired yet: run the pairing flow automatically instead of
                        // failing and asking the user to pair manually.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            AdbPairingHelper.handlePairing(this@StarterActivity)
                            finish()
                            return@observe
                        } else {
                            message = R.string.adb_pair_required
                        }
                    }
                }

                if (message != 0) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            binding.text1.text = output
        }

        viewModel.agentStopped = { agentWasStopped }

        // The payload's own report, while this screen is the one waiting for it. Exported
        // because the sender is another app's process, and filtered to one action so nothing
        // else of this app's traffic is in scope. Registered the platform way rather than
        // through ContextCompat, whose flagged form needs a newer androidx than this app
        // builds against.
        val stageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stage = intent.getStringExtra(STAGE_EXTRA) ?: return
                viewModel.reportPayloadStage(stage)
            }
        }
        val stageFilter = IntentFilter(STAGE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stageReceiver, stageFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stageReceiver, stageFilter)
        }
        payloadStageReceiver = stageReceiver
    }

    /** The payload's report arrives only while a system start is waiting. */
    private var payloadStageReceiver: BroadcastReceiver? = null

    override fun onDestroy() {
        payloadStageReceiver?.let { runCatching { unregisterReceiver(it) } }
        payloadStageReceiver = null
        super.onDestroy()
    }

    private var hasStarted = false

    /**
     * Whether this activity came back to the front while a system start was waiting. The
     * payload ends by stopping the agent, and the agent's activity is what covered this one,
     * so coming back means the payload ran: the one thing that separates "the agent never
     * acted" from "the starter or the server failed", and it needs no logcat to see.
     */
    private var agentWasStopped = false

    override fun onResume() {
        super.onResume()
        if (viewModel.exploitSent) agentWasStopped = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasStarted) {
            hasStarted = true
            viewModel.start(
                intent.getBooleanExtra(EXTRA_IS_ROOT, false),
                intent.getBooleanExtra(EXTRA_IS_SYSTEM, false),
                intent.getIntExtra(EXTRA_PORT, 0),
                intent.getBooleanExtra(EXTRA_SYSTEM_CUSTOM, false)
            )
        }
    }

    companion object {

        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_IS_SYSTEM = "$EXTRA.IS_SYSTEM"
        const val EXTRA_SYSTEM_CUSTOM = "$EXTRA.SYSTEM_CUSTOM"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

class ViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = getApplication<Application>().applicationContext

    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<StringBuilder>>()

    val output = _output as LiveData<Resource<StringBuilder>>

    /**
     * Where a start's own account of itself can be written.
     *
     * Two places, because they fail for different reasons. The external files directory is
     * the natural one, but another app's `Android/data` is exactly what the storage layer
     * and SELinux deny, and a system start writes this from another app's process;
     * `Android/media/<package>` exists to be reachable from outside the app, so it is the
     * one that survives that. Both are this app's own directories, so it can read either.
     */
    private fun starterLogFiles(): List<File> = listOfNotNull(
        appContext.getExternalFilesDir(null),
        appContext.getExternalMediaDirs()?.firstOrNull()
    ).map { File(it, "starter.log") }

    /**
     * The starter's own account of the attempt, and the file it was read from. That account
     * is the only reason a failed system start can be explained without logcat, so when
     * there is none the caller says where it looked rather than nothing happening.
     */
    private fun starterLog(): Pair<File, String>? {
        for (file in starterLogFiles()) {
            val text = runCatching {
                if (file.exists()) file.readText().trim() else null
            }.getOrNull()
            if (text.isNullOrEmpty()) continue

            // The file keeps every run now, so show the one that just happened: the accounts
            // of earlier attempts are still there but they are not what this timeout is about.
            val last = text.substringAfterLast(STARTER_LOG_DIVIDER).trim()
            return file to last.ifEmpty { text }
        }
        return null
    }

    /** Set once the agent has been told to run the payload. */
    @Volatile
    var exploitSent = false
        private set

    /** Set by the activity: whether the agent vanished while a start was waiting. */
    var agentStopped: (() -> Boolean)? = null

    private val handler = CoroutineExceptionHandler { _, throwable ->
        ShizukuStateMachine.update()
        log(error = throwable)
    }

    private var started = false

    fun start(root: Boolean, isSystem: Boolean, port: Int, systemCustom: Boolean = false) {
        if (started) return
        started = true

        // Recorded here too: this activity is an entry point of its own (the root and
        // system rows start it directly), and the card reports the method that started
        // the running server.
        ShizukuSettings.setRunningStartMethod(
            when {
                root -> ShizukuSettings.StartMethod.ROOT
                isSystem -> ShizukuSettings.StartMethod.SYSTEM
                else -> ShizukuSettings.StartMethod.USB
            }
        )

        viewModelScope.launch(handler) {
            // Whether there is anything to wait for: a system start whose exploit target
            // this device does not ship has already said so, and waiting a minute for a
            // service nobody asked for only hides that.
            val waiting = when {
                root -> { startRoot(); true }
                isSystem && systemCustom -> { startSystemCustom(); true }
                isSystem -> startSys()
                else -> { AdbStarter.startAdb(appContext, port, { log(it) }); true }
            }
            try {
                if (waiting) Starter.waitForBinder({ log(it) })
            } catch (e: TimeoutException) {
                val starter = starterLog()
                if (starter != null) {
                    log(
                        "the starter left this behind, in ${starter.first.absolutePath}:\n" +
                            "${starter.second}\n"
                    )
                } else {
                    log(
                        "the starter left no log at all, so it never got as far as writing " +
                            "one: nothing in " +
                            starterLogFiles().joinToString(" or ") { it.absolutePath } +
                            "\n"
                    )
                }
                log(
                    if (agentStopped?.invoke() == true) {
                        "the agent was stopped while this start was waiting, which is the " +
                            "payload's last step: the payload ran, so what failed is the " +
                            "starter or the server it starts\n"
                    } else {
                        "the agent is still running, so it did not act on the command: the " +
                            "payload never ran\n"
                    }
                )
                throw e
            }
        }
    }

    /**
     * Launches the Shizuku server under the system UID (1000) by abusing a
     * device-specific privilege escalation. This only works on devices that ship
     * the vulnerable component, and is opt-in via the "System start method"
     * setting.
     */
    /**
     * What the device offers before anything is sent, in the activity's own log. On a device
     * where the agent runs the payload, the starter's output belongs to the agent and cannot
     * be read from here, so the few facts this side can see are worth stating: whether the
     * agent is there, which uid it has, and whether it is the one whose payload a system-uid
     * start depends on.
     */
    private fun agentReport(): String = try {
        val pm = appContext.packageManager
        val info = pm.getPackageInfo(FOTA_AGENT_PACKAGE, PackageManager.GET_ACTIVITIES)
        val uid = info.applicationInfo?.uid ?: -1
        val hasMain = info.activities?.any { it.name == "$FOTA_AGENT_PACKAGE.Main" } == true
        val verdict = when {
            uid == 1000 -> "system uid, the payload will run as the system uid"
            uid >= 10000 -> "an ordinary app uid, so the payload will run as one and the server refuses it"
            else -> "uid $uid, which the server may or may not accept"
        }
        "agent: installed, uid $uid ($verdict), Main activity ${if (hasMain) "present" else "missing"}"
    } catch (e: PackageManager.NameNotFoundException) {
        "agent: not installed"
    }

    private suspend fun startSys(): Boolean {
        log("Starting with system...\n")
        log(agentReport())

        return withContext(Dispatchers.IO) {
            try {
                appContext.startActivity(
                    Intent().apply {
                        setClassName(FOTA_AGENT_PACKAGE, "$FOTA_AGENT_PACKAGE.Main")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )

                // Both stages come before the starter that they bracket, and the force stop
                // stays last: it is the reason the later sends of this command find no
                // receiver, and it has to run after everything this attempt wants to say.
                fun stage(value: String) =
                    "am broadcast -a $STAGE_ACTION --es $STAGE_EXTRA $value"

                val exploit = Intent("$FOTA_AGENT_PACKAGE.intent.CP_FILE").apply {
                    putExtra("CP_FILE", "/data")
                    putExtra(
                        "CP_LOC",
                        "; ${stage(STAGE_AT_SHELL)}" +
                            "; " + appContext.applicationInfo.nativeLibraryDir + "/libshizuku.so" +
                            "; ${stage(STAGE_AFTER_STARTER)}" +
                            "; am force-stop com.sdet.fotaagent"
                    )
                }

                // Several times, and not only for luck. The agent registers the receiver
                // that acts on this when its activity starts, and a single send a second
                // later is a race against that: on a device where the activity is still
                // coming up, the one send lands nowhere and the start looks like a timeout
                // with nothing to show for it. Repeats cost nothing when the first lands
                // because the payload stops the agent as its last step, which leaves the
                // later sends without a receiver.
                for (attempt in 0 until FOTA_ATTEMPTS) {
                    if (attempt > 0) delay(FOTA_ATTEMPT_INTERVAL_MS)
                    appContext.sendBroadcast(exploit)
                    exploitSent = true
                    log("sent the agent command (attempt ${attempt + 1} of $FOTA_ATTEMPTS)\n")

                    // And stop as soon as the agent has acted. Every further send runs the
                    // payload again, and every run stops the server the previous one started,
                    // so a manager can be handed a binder and have it taken away again before
                    // it notices: the repeats are only there for the race against the agent's
                    // receiver being registered, and that race is over the moment it answers.
                    if (payloadSeen) {
                        log("the agent acted, so no further commands are sent\n")
                        break
                    }
                }
                true
            } catch (e: ActivityNotFoundException) {
                // The exploit only exists where the device ships the component it abuses,
                // and a phone that no longer does has nothing to start: say which component
                // is missing and which method does not need it, instead of reporting a
                // success that never happened and then waiting for it.
                val message = appContext.getString(R.string.start_failed_system_no_exploit)
                log(message)
                withContext(Dispatchers.Main) { StartStatusReporter.failed(message) }
                false
            } catch (e: Throwable) {
                log("Start system failed!", e)
                false
            }
        }
    }

    /**
     * The instruction path for the system start: this app has no privilege of its own, so
     * it says what to run and waits for the service to appear. Whatever can launch a
     * process as a privileged uid - the user's own exploit, an automation, a root shell -
     * runs the executable this app ships, and the wait is the same one the other methods
     * use.
     */
    private suspend fun startSystemCustom() {
        // Two forms of the same thing, because which one works depends on the shell the
        // command is run in: the run-time lookup needs permission to ask the package
        // manager, and the absolute path is the one that works without it. The lookup is
        // the one copied, since it survives an update that moves the install.
        val command = systemStarterCommand()
        val plain = plainStarterCommand()

        log(appContext.getString(R.string.start_system_custom_intro))
        log(appContext.getString(R.string.start_system_custom_lookup))
        log(command)
        log("")
        log(appContext.getString(R.string.start_system_custom_plain))
        log(plain)

        val copied = withContext(Dispatchers.Main) {
            val clipboard =
                appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext false
            clipboard.setPrimaryClip(ClipData.newPlainText("Shizuku", command))
            true
        }

        log(
            appContext.getString(
                if (copied) R.string.start_system_custom_copied
                else R.string.start_system_custom_copy_failed
            )
        )
    }

    /**
     * The command resolves the installed APK's own lib directory at run time, so it keeps
     * working after an update moves it, and it names this package rather than a fixed one,
     * which matters for stealth mode.
     */
    /**
     * The same executable, named outright.
     *
     * The lookup above asks the package manager where this app is, which a shell that may
     * not query packages is refused. This is the path as this app knows it, right now.
     */
    private fun plainStarterCommand(): String =
        appContext.applicationInfo.nativeLibraryDir + "/libshizuku.so"

    private fun systemStarterCommand(): String {
        val packageName = appContext.packageName

        // The ABI directory this install actually uses, not arm64 by name: the app ships
        // four, and a 32-bit device looks in lib/arm.
        val abi = appContext.applicationInfo.nativeLibraryDir.substringAfterLast('/')

        // grep base.apk first, because pm path prints one line per split: on an install
        // delivered as an App Bundle that is several lines, and sed would return every one
        // of those paths joined together, which the shell then reads as one nonsense path.
        return "STARTER=\$(pm path $packageName | grep base.apk" +
            " | sed -E 's|^package:(.*/)[^/]+\\.apk\$|\\1lib/$abi/libshizuku.so|')" +
            " && \$STARTER"
    }

    /** Set as soon as the payload's shell reports anything at all. */
    @Volatile
    var payloadSeen = false
        private set

    /**
     * A stage the payload's own shell reported. Which of these arrived, and which was last,
     * says how far the command we handed the agent got, without depending on a log file that
     * may be missing for reasons on either side of the process boundary.
     */
    fun reportPayloadStage(stage: String) {
        payloadSeen = true
        log("the payload's shell reached: $stage\n")
    }

    private fun log(line: String? = null, error: Throwable? = null) {
        line?.let { sb.appendLine(it) }
        error?.let { sb.appendLine().appendLine(Log.getStackTraceString(it)) }

        if (error == null) _output.postValue(Resource.success(sb))
        else _output.postValue(Resource.error(error, sb))
    }

    private suspend fun startRoot() {
        log("Starting with root...\n")

        return withContext(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) {
                // Try again just in case
                Shell.getCachedShell()?.close()

                if (!Shell.getShell().isRoot) {
                    Shell.getCachedShell()?.close()
                    throw NotRootedException()
                }
            }

            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            suspendCancellableCoroutine { cont ->
                Shell.cmd(Starter.internalCommand)
                    .to(object : CallbackList<String?>() {
                        override fun onAddElement(s: String?) { s?.let { log(it) } }
                    })
                    .submit {
                        if (it.isSuccess) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(Exception("Failed to start with root"))
                        }
                    }
            }
        }
    }
    
}
