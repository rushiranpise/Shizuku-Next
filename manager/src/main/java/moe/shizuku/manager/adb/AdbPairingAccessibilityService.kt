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
import moe.shizuku.manager.utils.EnvironmentUtils
import java.net.ConnectException

/**
 * Pairs wireless debugging without any typing, by reading the code straight out of
 * the system's "Pair with device" dialog.
 *
 * On phones the user still opens the dialog (Developer options → Wireless debugging
 * → "Pair device with pairing code"); this service picks up the code and port from
 * it, pairs, and starts Shizuku. That removes both the typing and the race against
 * the pairing dialog's short lifetime, which is what makes the notification flow
 * fail when the code is entered too late.
 *
 * On TV the dialog cannot be driven by hand, so the service brings the app up and
 * drives the whole flow itself, then switches off again.
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

    /** Last code we attempted, so dialog refreshes don't retry the code we failed on. */
    private var lastAttempt: String? = null

    /** How many reads are left in the burst that follows a touch on Settings. */
    private var readsLeft = 0

    /** Only TV needs driving; elsewhere the user drives the dialog themselves. */
    private val isTelevision
        get() = EnvironmentUtils.isTelevision() && EnvironmentUtils.isTlsSupported()

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (!isTelevision) return

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(AppConstants.EXTRA_SHOW_PAIRING_DIALOG, true)
        }
        runCatching { startActivity(intent) }

        handler.postDelayed({
            toast(getString(R.string.toast_pairing_timeout))
            disableSelf()
        }, 60_000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pairing || event == null) return

        // The pairing dialog is always hosted by the settings app. Ignore every other
        // window rather than reading content we have no reason to look at.
        val packageName = event.packageName?.toString().orEmpty()
        if (!packageName.contains("settings", ignoreCase = true)) return

        // One read per event was enough while the dialog announced itself, but the tap that
        // opens it is the last event Android 17's dialog sends: the code is in the window a
        // moment later, with nothing left to say so, which is why the flow only worked after
        // something else was touched. So a touch on Settings starts a short burst of reads
        // instead, and it stops as soon as there is a code to act on.
        handler.removeCallbacks(readAgain)
        readsLeft = READ_ATTEMPTS
        readAgain.run()
    }

    private val readAgain = object : Runnable {
        override fun run() {
            if (pairing) return

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
     * Looks for the pairing dialog in the visible windows and pulls the code and the
     * `host:port` out of it. Reading the whole window (rather than the changed node's
     * own text) is what makes this work on phones, where the code and the port sit in
     * sibling text views.
     */
    private fun readPairingDialog(): PairingDialog? {
        val windows = runCatching { windows }.getOrNull().orEmpty()

        for (window in windows) {
            val root = window?.root ?: continue

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
        ShizukuReceiverStarter.start(this)

        pairing = false
        if (isTelevision) disableSelf()
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
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "AdbPairingAccessibility"

        /** How many times a touch on Settings is followed up, and how far apart. */
        private const val READ_ATTEMPTS = 15
        private const val READ_INTERVAL_MS = 400L

        /** Long enough for discovery to answer, short enough to report a failure. */
        private const val PAIRING_DISCOVERY_TIMEOUT_MS = 8_000L

        private val CODE_REGEX = Regex("""\d{6}""")
        private val HOST_PORT_REGEX = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    }
}
