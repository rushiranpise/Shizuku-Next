package moe.shizuku.manager.service

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.manage.LabSwitch
import moe.shizuku.manager.manage.SwitchIcon

/**
 * One whole-feature switch as a tile in the shade.
 *
 * The subclasses at the bottom of this file are nothing but which switch they are: everything a
 * tile does - reading the state when the shade is drawn, flipping it off the main thread, telling
 * the matching widget, and opening the app when the state cannot be read at all - is here once.
 *
 * The state is asked of whatever owns it rather than remembered, so a change made in the app, by
 * the widget, by another app writing the same setting, or by the platform is what the tile says
 * the next time it is looked at. Unavailable is an answer of its own: a tile that guessed would
 * be wrong in one direction or the other.
 */
abstract class LabTileService(private val switch: LabSwitch) : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()

        // A locked phone that can still reach the shade must not be able to switch anything.
        // The check stays here rather than in LabSwitch because the app's own screens and the
        // home screen widgets are not quick tiles and are already behind the lock.
        if (ShizukuSettings.getSecureQuickTiles() && keyguardLocked()) {
            Toast.makeText(this, R.string.lab_switch_locked, Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            val current = readState()
            if (current == null) {
                // Nothing can be switched: whatever owns this state cannot be asked, which for
                // most of these means the server is not there. Opening the app is where that is
                // fixed; the tile already says Unavailable, and a tap that did nothing would be
                // read as a broken tile.
                withContext(Dispatchers.Main) { openApp() }
                return@launch
            }

            switch.apply(this@LabTileService, !current)
            LabSwitch.notifyChanged(this@LabTileService, switch)

            val state = readState()
            show(state)
            switch.announce(this@LabTileService, state)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        scope.launch { show(readState()) }
    }

    /** Whether the keyguard is up, which is when a secured tile may not change anything. */
    private fun keyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    /**
     * The state, waited for a moment when it cannot be read yet.
     *
     * A process that has just been started - after an update, or after the system reclaimed it -
     * has no binder in it for the first moment, and the server hands one over as soon as it sees
     * the process. Saying Unavailable during that window would be true for a second and wrong for
     * the rest of the day.
     */
    private fun readState(): Boolean? {
        repeat(3) { attempt ->
            switch.isOn()?.let { return it }
            if (attempt < 2) Thread.sleep(500)
        }
        return null
    }

    /** Draws the tile for [on], on the main thread, which is the only one allowed to. */
    private suspend fun show(on: Boolean?) {
        withContext(Dispatchers.Main) {
            val tile = qsTile ?: return@withContext
            val look = LabSwitch.Look.of(on)

            tile.state = when (look) {
                LabSwitch.Look.ON -> Tile.STATE_ACTIVE
                LabSwitch.Look.OFF -> Tile.STATE_INACTIVE
                LabSwitch.Look.UNKNOWN -> Tile.STATE_UNAVAILABLE
            }
            tile.label = getString(switch.label)
            // The very icon the Labs screen draws, rasterized at the size a tile wants.
            val size = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            tile.icon = Icon.createWithBitmap(SwitchIcon.bitmap(switch.image, size))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(look.label)
            }
            tile.updateTile()
        }
    }

    /** Opens the manager, collapsing the shade the way a tile click is allowed to. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

// One class per tile because a Quick Settings tile *is* a component: the system binds the one the
// user put in the shade, and there is no way to hand it a parameter instead.

class ServiceTileService : LabTileService(LabSwitch.SERVICE)
class FirewallTileService : LabTileService(LabSwitch.FIREWALL)
class AutoStartTileService : LabTileService(LabSwitch.AUTOSTART)
class HidingTileService : LabTileService(LabSwitch.HIDING)
class DeveloperOptionsTileService : LabTileService(LabSwitch.DEVELOPER_OPTIONS)
class UsbDebuggingTileService : LabTileService(LabSwitch.USB_DEBUGGING)
class WirelessDebuggingTileService : LabTileService(LabSwitch.WIRELESS_DEBUGGING)
class HideDeveloperOptionsTileService : LabTileService(LabSwitch.HIDE_DEVELOPER_OPTIONS)
class HideUsbTileService : LabTileService(LabSwitch.HIDE_USB_DEBUGGING)
class HideWirelessTileService : LabTileService(LabSwitch.HIDE_WIRELESS_DEBUGGING)
class HideAccessibilityTileService : LabTileService(LabSwitch.HIDE_ACCESSIBILITY)
class HidePrivateDnsTileService : LabTileService(LabSwitch.HIDE_PRIVATE_DNS)
class HideVpnTileService : LabTileService(LabSwitch.HIDE_VPN)
class ForceDarkTileService : LabTileService(LabSwitch.FORCE_DARK)
class WatchdogTileService : LabTileService(LabSwitch.WATCHDOG)
