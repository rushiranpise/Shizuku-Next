package moe.shizuku.manager.manage

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.utils.Diag
import kotlin.concurrent.thread

/**
 * Hides, then opens: the second half of [Hiding.restartForLaunch].
 *
 * Started by the shell right after it has stopped the app, and never shown: it is translucent,
 * has no layout and finishes as soon as the app is on its way. It exists because of who may open
 * an app. The watch runs in a service, and the platform does not let a service open an activity;
 * the shell may, but the shell dies the moment the transport it rides is hidden. An activity of
 * this app that is already in front may open another app, and it writes the settings with this
 * app's own WRITE_SECURE_SETTINGS - so it needs nothing that hiding can take away.
 *
 * Exported because the shell starts it, and guarded by DUMP, which the shell holds and an
 * ordinary app cannot: nothing else can make this app hide settings and open something. It also
 * acts only for an app that is on a hiding list.
 */
class HidingLaunchActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "moe.shizuku.manager.extra.HIDING_PACKAGE"
        private const val TAG = AppConstants.TAG
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent?.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null || !Hiding.isListed(packageName)) {
            finish()
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            finish()
            return
        }

        // The writes are binder calls and the VPN signal may stop a client, so they are kept off
        // the main thread; the opening comes back to it, while this activity is still in front.
        thread(name = "hiding-launch") {
            runCatching { Hiding.hideAllFor(packageName) }
                .onFailure { Diag.warn(TAG, "hiding before reopening $packageName failed", it) }
            if (Hiding.isAccessibilityHiddenFor(packageName)) waitForAccessibilityToSettle()
            runOnUiThread {
                runCatching { startActivity(launch) }
                    .onFailure { Diag.warn(TAG, "could not reopen $packageName", it) }
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    /**
     * Waits for the accessibility services to really be gone, up to [SETTLE_LIMIT_MS].
     *
     * Emptying the setting is not the same moment as the services being unbound: the platform
     * reads the setting and tears the services down after, and an app asks the accessibility
     * manager, which answers from what is bound. Reopened straight after the write, a target app
     * still finds the services there - measured: it complained on the reopen and not on the next
     * open a few seconds later. So the reopen waits until the manager agrees.
     */
    private fun waitForAccessibilityToSettle() {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return
        val deadline = SystemClock.elapsedRealtime() + SETTLE_LIMIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val settled = runCatching {
                !manager.isEnabled && manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                ).isEmpty()
            }.getOrDefault(true)
            if (settled) return
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        Diag.warn(TAG, "accessibility services still bound after ${SETTLE_LIMIT_MS}ms; reopening")
    }

    private val SETTLE_LIMIT_MS = 3_000L
    private val SETTLE_POLL_MS = 100L
}
