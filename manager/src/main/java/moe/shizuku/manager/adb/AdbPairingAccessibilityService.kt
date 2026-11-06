package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.start.StartFailureKind
import moe.shizuku.manager.start.StartStatusReporter
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.EnvironmentUtils
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pairs wireless debugging without any taps or typing, by reading the code straight out of
 * the system's "Pair with device" dialog.
 *
 * On phones the app opens Developer options with the Wireless debugging row highlighted;
 * this service takes the two taps from there (the service, then "Pair device with pairing
 * code"), reads the code and port out of the dialog, pairs, and starts Shizuku. That removes
 * the taps, the typing, and the race against the pairing dialog's short lifetime, which is
 * what makes the notification flow fail when the code is entered too late.
 *
 * On TV the user walks to the dialog with the remote and the service only reads it. When the
 * service was switched on from the accessibility list, it brings the app back up so the user
 * can carry on from the pairing dialog; when the app switched it on and opened Developer options
 * itself, it stays out of the way, because bringing the app up then covered the very screen the
 * user was about to use (which is what made it look broken on the Google TV Streamer).
 *
 * What it may look at is the settings app that hosts that dialog and nothing else: declared in
 * the service configuration, and applied again to the windows this walks, because an
 * accessibility service that reads whatever is on screen is the shape of the thing scanners are
 * right to warn about.
 */
class AdbPairingAccessibilityService : AccessibilityService() {

    /**
     * What the pairing dialog says. The address is optional because Android 17's dialog does
     * not print one: it shows the device's mDNS hostname, with the addresses folded away behind
     * "Additional device addresses", so the code is all that can be read out of it.
     */
    private data class PairingDialog(val code: String, val host: String?, val port: Int?)

    private val handler = Handler(Looper.getMainLooper())

    private var pairing = false

    /** True once a pair succeeded: the dialog can stay open, but it must not be read again. */
    private var paired = false

    /** Last code we attempted, so dialog refreshes don't retry the code we failed on. */
    private var lastAttempt: String? = null

    /** How many reads are left in the burst that follows a touch on Settings. */
    private var readsLeft = 0

    /** How many times the two Settings rows are looked for before leaving it to the user. */
    private var driveAttempts = 0

    /** True once the pairing row was clicked, so the service does not keep tapping Settings. */
    private var driveDone = false

    private var lastDriveTarget: String? = null
    private var lastDriveAt = 0L

    /** Only TV needs driving; elsewhere the user drives the dialog themselves. */
    private val isTelevision
        get() = EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported()

    override fun onServiceConnected() {
        super.onServiceConnected()

        paired = false
        driveDone = false
        driveAttempts = 0
        lastDriveTarget = null

        Diag.info(TAG, "Connected, television: $isTelevision")

        if (isTelevision) {
            // The app opens Developer options before the service can connect. Consume the
            // mark on this connection so a slow connect still leaves that screen in front;
            // once consumed, a later manual re-enable gets the usual MainActivity launch.
            val settingsOpened = settingsOpenedByApp.getAndSet(false)
            if (!settingsOpened) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(AppConstants.EXTRA_SHOW_PAIRING_DIALOG, true)
                }
                runCatching { startActivity(intent) }
                    .onFailure { Diag.warn(TAG, "Could not bring Shizuku up", it) }
            }

            // Getting to the dialog with a remote takes longer than a tap or two on a phone.
            handler.postDelayed({
                toast(getString(R.string.toast_pairing_timeout))
                disableSelf()
            }, TV_TIMEOUT_MS)
            return
        }

        // On a phone the app has just opened Developer options with the Wireless debugging
        // row highlighted. Take the two taps from there; the reader below handles the dialog.
        handler.postDelayed(driveAgain, DRIVE_INITIAL_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pairing || paired || event == null) return

        // The pairing dialog is always hosted by the settings app. Ignore every other
        // window rather than reading content we have no reason to look at.
        if (!hostsPairingDialog(event.packageName?.toString())) return

        // One read per event was enough while the dialog announced itself, but the tap that
        // opens it is the last event Android 17's dialog sends: the code is in the window a
        // moment later, with nothing left to say so, which is why the flow only worked after
        // something else was touched. So a touch on Settings starts a short burst of reads
        // instead, and it stops as soon as there is a code to act on.
        // A screen change is also the cue to take the next tap of the automatic flow.
        if (!isTelevision && !driveDone) {
            handler.removeCallbacks(driveAgain)
            handler.postDelayed(driveAgain, DRIVE_EVENT_DELAY_MS)
        }

        handler.removeCallbacks(readAgain)
        readsLeft = READ_ATTEMPTS
        readAgain.run()
    }

    private val readAgain = object : Runnable {
        override fun run() {
            if (pairing || paired) return

            val dialog = readPairingDialog()
            if (dialog != null) {
                val signature = listOfNotNull(dialog.code, dialog.host, dialog.port?.toString())
                    .joinToString(":")
                if (signature != lastAttempt) {
                    lastAttempt = signature
                    pair(dialog)
                }
                return
            }

            if (readsLeft-- > 0) handler.postDelayed(this, READ_INTERVAL_MS)
        }
    }

    /**
     * Walks the two taps between Developer options and the pairing dialog.
     *
     * The user has already chosen automatic pairing; these are the taps that flow used to
     * ask for. It only clicks a row whose text is one of the two it knows, and stops after
     * the pairing row: from there the dialog reader above takes over.
     */
    private val driveAgain = object : Runnable {
        override fun run() {
            if (isTelevision || pairing || paired || driveDone) return

            // If the dialog is already up, there is nothing to open.
            if (readPairingDialog() != null) {
                driveDone = true
                return
            }

            val root = settingsRoot()
            if (root != null) {
                val pairRow = findText(root, PAIR_CODE_TEXT)
                if (pairRow != null) {
                    if (clickRow(pairRow, PAIR_CODE_TARGET)) {
                        driveDone = true
                        return
                    }
                } else {
                    // Developer options. The title's clickable parent is the left side of
                    // the row; the switch beside it is a sibling, so this opens the screen
                    // rather than toggling wireless debugging.
                    findText(root, WIRELESS_TEXT)?.let { clickRow(it, WIRELESS_TARGET) }
                }
            }

            if (++driveAttempts < DRIVE_ATTEMPTS) {
                handler.postDelayed(this, DRIVE_INTERVAL_MS)
            }
        }
    }

    private fun settingsRoot(): AccessibilityNodeInfo? {
        val openWindows = runCatching { windows }.getOrNull().orEmpty()
        for (window in openWindows) {
            val root = window?.root ?: continue
            if (hostsPairingDialog(root.packageName?.toString())) return root
        }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findText(child, text)?.let { return it }
        }
        return null
    }

    private fun clickRow(node: AccessibilityNodeInfo, target: String): Boolean {
        val now = System.currentTimeMillis()
        if (target == lastDriveTarget && now - lastDriveAt < CLICK_DEBOUNCE_MS) return false

        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        val clicked = runCatching {
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }.getOrDefault(false)
        if (clicked) {
            lastDriveTarget = target
            lastDriveAt = now
            return true
        }
        return false
    }

    /**
     * Looks for the pairing dialog in the visible windows and pulls the code and the
     * `host:port` out of it. Reading the whole window (rather than the changed node's
     * own text) is what makes this work on phones, where the code and the port sit in
     * sibling text views.
     */
    private fun readPairingDialog(): PairingDialog? {
        val windows = runCatching { windows }.getOrNull().orEmpty()

        for (window in windows) {
            val root = window?.root ?: continue

            // The same test as the events, now applied to what is about to be read: the walk is
            // bounded by the app it is willing to look at, and not only by what the text in the
            // window happens to say. An app open beside the dialog is not read at all.
            if (!hostsPairingDialog(root.packageName?.toString())) continue

            val texts = mutableListOf<String>()
            collectText(root, texts)

            // Match the dialog itself, not the wireless-debugging page behind it,
            // which also mentions pairing but shows a different (connect) port.
            if (texts.none {
                    it.contains("pairing code", ignoreCase = true) ||
                        it.contains("pair with device", ignoreCase = true)
                }
            ) continue

            val code = texts.firstOrNull { CODE_REGEX.matches(it) } ?: continue

            // Optional, and not a reason to walk away: see [PairingDialog].
            val hostPort = texts.asSequence()
                .mapNotNull { HOST_PORT_REGEX.find(it) }
                .firstOrNull()

            return PairingDialog(
                code = code,
                host = hostPort?.groupValues?.get(1),
                port = hostPort?.groupValues?.get(2)?.toIntOrNull()
            )
        }

        return null
    }

    /**
     * The pairing port as the device advertises it, for a dialog that does not print one.
     *
     * Returns null if nothing answers within the timeout, which is the same situation as a
     * dialog whose address cannot be read: the attempt is reported as a failed connection
     * rather than left hanging.
     */
    private suspend fun discoverPairingEndpoint(
        timeoutMs: Long = PAIRING_DISCOVERY_TIMEOUT_MS
    ): Pair<String, Int>? {
        // Wireless pairing arrived with Android 11 (API 30), and so did the mDNS class that
        // locates the pairing port below: on anything older the class does not exist at all,
        // and there is no pairing service to discover either, so "no endpoint" is the honest
        // answer rather than a NoClassDefFoundError.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                lateinit var mdns: AdbMdns
                mdns = AdbMdns(this@AdbPairingAccessibilityService, AdbMdns.TLS_PAIRING) { (host, port) ->
                    if (port > 0 && continuation.isActive) {
                        mdns.stop()
                        continuation.resume(host to port)
                    }
                }
                continuation.invokeOnCancellation { mdns.stop() }
                mdns.start()
            }
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectText(it, out) }
        }
    }

    private fun pair(dialog: PairingDialog) {
        // Wireless pairing and the client below both arrived with Android 11 (API 30), and the
        // client's class does not exist before it. A pairing dialog cannot appear on an older
        // platform, so this is only making that explicit rather than reaching for a class that
        // is not there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finish(getString(R.string.cannot_connect_port))
            return
        }
        pairing = true

        GlobalScope.launch(Dispatchers.IO) {
            // The dialog's own address when it has one, and the port the device advertises when
            // it does not: the pairing service is on `_adb-tls-pairing._tcp` either way, which
            // is how the notification flow finds it. On Android 17 this is the only way to
            // pair without the user unfolding the dialog's address list first.
            val endpoint = if (dialog.host != null && dialog.port != null) {
                dialog.host to dialog.port
            } else {
                Log.i(TAG, "Dialog had no address, asking the device where pairing is listening")
                discoverPairingEndpoint()
            }
            if (endpoint == null) {
                Log.w(TAG, "No pairing endpoint found")
                finish(getString(R.string.cannot_connect_port))
                return@launch
            }
            val (host, port) = endpoint

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load adb key", e)
                finish(getString(R.string.adb_error_key_store))
                return@launch
            }

            AdbPairingClient(host, port, dialog.code, key)
                .runCatching { start() }
                .onFailure { e ->
                    Log.w(TAG, "Pair failed", e)
                    finish(
                        when (e) {
                            is ConnectException -> getString(R.string.cannot_connect_port)
                            is AdbInvalidPairingCodeException -> getString(R.string.paring_code_is_wrong)
                            is AdbKeyException -> getString(R.string.adb_error_key_store)
                            else -> e.localizedMessage ?: e.javaClass.simpleName
                        }
                    )
                }
                .onSuccess { success ->
                    if (!success) {
                        finish(getString(R.string.notification_adb_pairing_failed_title))
                        return@onSuccess
                    }

                    Log.i(TAG, "Paired from the pairing dialog, starting Shizuku")
                    onPaired()
                }
        }
    }

    private fun onPaired() {
        // The notification-driven search has nothing left to find.
        runCatching { startService(AdbPairingService.stopIntent(this)) }

        // Pairing was the only thing standing between the user and a running Shizuku,
        // so start it instead of sending them back to tap Start again.
        StartStatusReporter.clear()
        toast(getString(R.string.notification_adb_pairing_succeed_text))
        // Not userInitiated: nobody tapped for this one, it is the pairing finishing by itself.
        // Wi-Fi is necessarily up - pairing runs over it - so this changes nothing today; it just
        // keeps the flag meaning what its name says.
        ShizukuReceiverStarter.start(this, reason = "pairing-accessibility")

        paired = true
        pairing = false
        // Pairing is done. Staying enabled would keep reading and clicking Settings after
        // the dialog has served its purpose; the app switches the service back on when a
        // new pairing is asked for.
        disableSelf()
    }

    private fun finish(message: String) {
        pairing = false
        StartStatusReporter.failed(message, StartFailureKind.PAIRING)
        toast(message)
    }

    private fun toast(message: String) {
        GlobalScope.launch(Dispatchers.Main) {
            Toast.makeText(this@AdbPairingAccessibilityService, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        // Logged so the log can tell a service that was switched off (this line) from one whose
        // process died (no line, and a crash entry instead).
        Diag.info(TAG, "Unbound, paired: $paired")
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "AdbPairingAccessibility"

        /** How long a TV user has to reach the pairing dialog before the service gives up. */
        private const val TV_TIMEOUT_MS = 3 * 60_000L

        /**
         * True when the app opened Settings and the next connection belongs to that launch.
         *
         * Consumed on connect, so it can neither expire while the system is still binding the
         * service nor swallow a later manual re-enable.
         */
        private val settingsOpenedByApp = AtomicBoolean(false)

        /**
         * Called by the app just before it opens Developer options for automatic pairing, so a
         * service that connects at the same moment leaves that screen in front.
         */
        @JvmStatic
        fun noteSettingsOpened() {
            settingsOpenedByApp.set(true)
        }

        /** How many times a touch on Settings is followed up, and how far apart. */
        private const val READ_ATTEMPTS = 15
        private const val READ_INTERVAL_MS = 400L

        /** The two rows between Developer options and the dialog, and how often to look. */
        private const val WIRELESS_TEXT = "Wireless debugging"
        private const val PAIR_CODE_TEXT = "Pair device with pairing code"
        private const val WIRELESS_TARGET = "wireless"
        private const val PAIR_CODE_TARGET = "pairing-code"
        private const val DRIVE_INITIAL_DELAY_MS = 800L
        private const val DRIVE_INTERVAL_MS = 700L
        private const val DRIVE_EVENT_DELAY_MS = 350L
        private const val DRIVE_ATTEMPTS = 20
        private const val CLICK_DEBOUNCE_MS = 1_500L

        /** Long enough for discovery to answer, short enough to report a failure. */
        private const val PAIRING_DISCOVERY_TIMEOUT_MS = 8_000L

        /**
         * The name a settings app carries, which is the whole of what this service reads.
         *
         * Matched by that word rather than against a list of exact packages: every Android puts
         * the pairing dialog in `com.android.settings`, TVs in `com.android.tv.settings`, and an
         * OEM that calls its own something else still says "settings" in the name. The service
         * configuration declares the two known ones, and has to be kept in step - a package it
         * does not name never reaches this method at all.
         */
        private const val SETTINGS = "settings"

        private fun hostsPairingDialog(packageName: String?): Boolean =
            packageName?.contains(SETTINGS, ignoreCase = true) == true

        private val CODE_REGEX = Regex("""\d{6}""")
        private val HOST_PORT_REGEX = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    }
}
