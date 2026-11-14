package moe.shizuku.manager.utils

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read side of the three debugging switches.
 *
 * Android 17 redacts all three settings to 0, so a raw 0 there is not off. Wireless debugging
 * stayed readable through the first Android 17 release but QPR3 hides it too.
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
        assertTrue(EnvironmentUtils.platformDebugSwitchOn("adb_wifi_enabled", false, 37))
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
        assertFalse(EnvironmentUtils.platformDebugSwitchOn("adb_wifi_enabled", false, 36))
        assertTrue(EnvironmentUtils.platformDebugSwitchOn("adb_wifi_enabled", true, 37))
    }

    @Test
    fun `android 17 redacted switches are unknown rather than off`() {
        assertEquals(
            DebugSwitchState.UNKNOWN,
            EnvironmentUtils.platformDebugSwitchState(Settings.Global.ADB_ENABLED, false, 37)
        )
        assertEquals(
            DebugSwitchState.UNKNOWN,
            EnvironmentUtils.platformDebugSwitchState(
                "development_settings_enabled",
                false,
                37
            )
        )
        assertEquals(
            DebugSwitchState.UNKNOWN,
            EnvironmentUtils.platformDebugSwitchState("adb_wifi_enabled", false, 37)
        )
        assertEquals(
            DebugSwitchState.OFF,
            EnvironmentUtils.platformDebugSwitchState("adb_wifi_enabled", false, 36)
        )
        assertEquals(
            DebugSwitchState.ON,
            EnvironmentUtils.platformDebugSwitchState("adb_wifi_enabled", true, 37)
        )
    }

    @Test
    fun `all three debug settings are treated as unreadable on android 17`() {
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
        assertTrue(
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
