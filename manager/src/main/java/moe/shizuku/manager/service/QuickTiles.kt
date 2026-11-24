package moe.shizuku.manager.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService

/**
 * The quick settings tiles this app declares, and whether each one is offered to the system.
 *
 * A tile is a component, not a setting: the panel lists the services that answer
 * [TileService.ACTION_QS_TILE] and are enabled. Disabling the component is therefore the whole
 * record of the choice, with nothing else to keep in step with it, and a tile added by a later
 * version starts enabled like every tile this app has ever declared.
 *
 * The list is read from the package manager rather than written out here, so it cannot drift from
 * the manifest: a tile that is declared is a tile this screen can switch, and one that is removed
 * stops being a row.
 */
object QuickTiles {

    /** The permission a tile service must hold to be listed by the system, as the manifest says. */
    private const val BIND_TILE = "android.permission.BIND_QUICK_SETTINGS_TILE"

    /** One declared tile: what the picker calls it, its icon, and whether it is offered. */
    data class Tile(
        val component: ComponentName,
        val label: String,
        val iconRes: Int,
        val enabled: Boolean
    )

    /**
     * Every tile this app declares, enabled or not.
     *
     * [PackageManager.MATCH_DISABLED_COMPONENTS] is what makes the off rows visible: without it a
     * tile that was switched off would disappear from the list that is supposed to switch it back
     * on.
     */
    fun all(context: Context): List<Tile> {
        val pm = context.packageManager
        val intent = Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName)
        return pm.queryIntentServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapNotNull { it.serviceInfo }
            .filter { it.permission == BIND_TILE }
            .map { info ->
                val component = ComponentName(info.packageName, info.name)
                Tile(
                    component = component,
                    label = info.loadLabel(pm).toString(),
                    iconRes = info.icon,
                    enabled = isEnabled(pm, component)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Offers [tile] to the system, or takes it away.
     *
     * [PackageManager.DONT_KILL_APP] is what keeps switching one tile from restarting the app and
     * every other tile with it.
     */
    fun setEnabled(context: Context, tile: Tile, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            tile.component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }

    /** Whether the component is in one of the disabled states, rather than at its default. */
    private fun isEnabled(pm: PackageManager, component: ComponentName): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }
}
