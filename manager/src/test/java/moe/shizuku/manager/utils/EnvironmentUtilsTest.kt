package moe.shizuku.manager.utils

import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read side of the three debugging switches.
 *
 * Android 17 redacts two of the settings to 0, so a raw 0 there is not off. The wireless setting
 * stays readable and must keep its raw answer.
 */
class EnvironmentUtilsTest {

    @Test
    fun `android 17 redacted debug switches read as on`() {
        assertFalse(EnvironmentUtils.platformDebugSwitchOn(Settings.Global.ADB_ENABLED, false, 36))
        assertTrue(EnvironmentUtils.platformDebugSwitchOn(Settings.Global.ADB_ENABLED, false, 37))
        assertTrue(
            EnvironmentUtils.platformDebugSwitchOn(
                "development_settings_enabled",
                false,
                37
            )
        )
    }

    @Test
    fun `a readable debug switch keeps its raw value`() {
        assertTrue(EnvironmentUtils.platformDebugSwitchOn(Settings.Global.ADB_ENABLED, true, 36))
        assertFalse(
            EnvironmentUtils.platformDebugSwitchOn(
                "development_settings_enabled",
                false,
                36
            )
        )
        assertFalse(EnvironmentUtils.platformDebugSwitchOn("adb_wifi_enabled", false, 37))
        assertTrue(EnvironmentUtils.platformDebugSwitchOn("adb_wifi_enabled", true, 37))
    }

    @Test
    fun `only the two redacted settings are treated as unreadable`() {
        assertTrue(
            EnvironmentUtils.isDebugSwitchReadRedacted(
                Settings.Global.ADB_ENABLED,
                37
            )
        )
        assertTrue(
            EnvironmentUtils.isDebugSwitchReadRedacted(
                "development_settings_enabled",
                37
            )
        )
        assertFalse(
            EnvironmentUtils.isDebugSwitchReadRedacted(
                "adb_wifi_enabled",
                37
            )
        )
        assertFalse(
            EnvironmentUtils.isDebugSwitchReadRedacted(
                Settings.Global.ADB_ENABLED,
                36
            )
        )
    }
}
