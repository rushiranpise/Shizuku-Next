package moe.shizuku.manager.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.accessibility.AccessibilityKeepAlive
import moe.shizuku.manager.accessibility.AccessibilityKeepAliveService
import moe.shizuku.manager.manage.AutoStartGate
import moe.shizuku.manager.manage.PackageTools
import moe.shizuku.manager.service.HidingWatchService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A file the user can keep of everything they configured, and put back after a reinstall.
 *
 * The two preference files are written by name rather than through the app's own getters, because
 * this has to include the Labs lists and the accessibility locks and not the shell's history or
 * bookmarks. Runtime bookkeeping - the auth token, the ADB key, the last port, the last boot, the
 * hiding originals - is left out on purpose: those are facts about this install, not settings.
 */
object SettingsBackup {

    private const val FORMAT = "shizuku-next-settings"
    private const val VERSION = 1

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_ACCESSIBILITY = "accessibility"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_TYPE = "type"
    private const val KEY_VALUE = "value"

    /**
     * Keys that describe what the app is doing right now, or that must not travel in a plain file.
     *
     * The ADB private key and the intent auth token are per-install credentials. The rest are
     * observations - boot count, the last port, whether a hiding operation is in progress - and
     * restoring an old observation would make the app act on something that is no longer true.
     */
    private val EXCLUDED_KEYS = setOf(
        "adbkey",
        "auth_token",
        "boot_count",
        "force_dark_applied",
        "force_dark_saved_night_mode",
        "hiding_paused",
        "hiding_since",
        "hiding_vpn_client",
        "lastPromptedVersion",
        "last_adb_port",
        "last_adb_port_boot",
        "last_adb_transport",
        "manually_stopped",
        "mode",
        "running_start_method",
        "server_ran_since_boot",
        "setup_done"
    )

    /** The values remembered to put a hidden setting back, which are not settings themselves. */
    private val EXCLUDED_PREFIXES = listOf("hiding_originals_")

    data class Backup(
        val settings: Map<String, Value>,
        val accessibility: Map<String, Value>,
        val exportedAt: String?,
        /** Null means it was not read because Shizuku was not running, so do not touch it. */
        val autostart: Set<String>? = null
    ) {
        val count: Int
            get() = settings.size + accessibility.size + if (autostart != null) 1 else 0
    }

    sealed interface Value {
        fun write(editor: SharedPreferences.Editor, key: String)

        data class Bool(val value: Boolean) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putBoolean(key, value)
            }
        }

        data class Text(val value: String) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putString(key, value)
            }
        }

        data class IntValue(val value: Int) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putInt(key, value)
            }
        }

        data class LongValue(val value: Long) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putLong(key, value)
            }
        }

        data class FloatValue(val value: Float) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putFloat(key, value)
            }
        }

        data class StringSetValue(val value: Set<String>) : Value {
            override fun write(editor: SharedPreferences.Editor, key: String) {
                editor.putStringSet(key, value)
            }
        }
    }

    /** A name that says what the file is and when it was made. */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "shizuku-next-settings-$stamp.json"
    }

    /** Writes the current settings to [uri], returning how many were written, or null on failure. */
    fun export(context: Context, uri: Uri): Int? {
        return runCatching {
            val settings = encode(ShizukuSettings.getPreferences())
            val accessibility = encode(accessibilityPreferences(context))
            val autostart = if (shellAvailable()) {
                PackageTools.readOpDeniedInstalled(AutoStartGate.OP)
            } else {
                null
            }
            val root = JSONObject()
                .put(KEY_FORMAT, FORMAT)
                .put(KEY_VERSION, VERSION)
                .put(KEY_EXPORTED_AT, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                .put(KEY_APP_VERSION, BuildConfig.VERSION_NAME)
                .put(KEY_SETTINGS, settings)
                .put(KEY_ACCESSIBILITY, accessibility)
            autostart?.let { root.put(KEY_AUTOSTART, JSONArray(it.sorted())) }

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return@runCatching null

            settings.length() + accessibility.length() + if (autostart != null) 1 else 0
        }.getOrNull()
    }

    /** Reads a backup chosen by the user, or null when the file is not one of ours. */
    fun read(context: Context, uri: Uri): Backup? {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: return@runCatching null

            val root = JSONObject(text)
            if (root.optString(KEY_FORMAT) != FORMAT) return@runCatching null
            if (root.optInt(KEY_VERSION, -1) != VERSION) return@runCatching null

            val settings = decode(root.getJSONObject(KEY_SETTINGS)) ?: return@runCatching null
            val accessibility = root.optJSONObject(KEY_ACCESSIBILITY)?.let(::decode) ?: emptyMap()
            val exportedAt = root.optString(KEY_EXPORTED_AT, "").takeIf { it.isNotEmpty() }
            val autostart = root.optJSONArray(KEY_AUTOSTART)?.let { array ->
                decodePackages(array) ?: return@runCatching null
            }
            Backup(settings, accessibility, exportedAt, autostart)
        }.getOrNull()
    }

    /**
     * Replaces the current settings with [backup].
     *
     * Existing user-facing keys are removed first, not the whole preference file: clearing it
     * would also take the auth token and the ADB key, which are deliberately not in the backup.
     */
    fun apply(context: Context, backup: Backup): Int? {
        val oldAutostart = if (backup.autostart != null && shellAvailable()) {
            PackageTools.readOpDeniedInstalled(AutoStartGate.OP)
        } else {
            null
        }

        val preferences = ShizukuSettings.getPreferences()
        val editor = preferences.edit()
        preferences.all.keys
            .filter { isEligible(it) }
            .forEach { editor.remove(it) }

        var applied = 0
        backup.settings.forEach { (key, value) ->
            if (!isEligible(key)) return@forEach
            value.write(editor, key)
            applied++
        }
        if (!editor.commit()) return null

        val accessibility = accessibilityPreferences(context)
        val accessibilityEditor = accessibility.edit().clear()
        backup.accessibility.forEach { (key, value) ->
            value.write(accessibilityEditor, key)
            applied++
        }
        if (!accessibilityEditor.commit()) return null

        // Put the services that are driven by these preferences back in step with them.
        ShizukuSettings.updateBootReceiver(context)
        ShizukuSettings.setWatchdog(context, ShizukuSettings.getWatchdog())
        AccessibilityKeepAliveService.refresh(context)
        HidingWatchService.refresh(context)

        if (backup.autostart != null && oldAutostart != null) {
            (oldAutostart - backup.autostart).forEach { packageName ->
                PackageTools.setOpBlocked(context, packageName, AutoStartGate.OP, false)
            }
            (backup.autostart - oldAutostart).forEach { packageName ->
                PackageTools.setOpBlocked(context, packageName, AutoStartGate.OP, true)
            }
        }

        // The firewall list is this app's own record; the platform has to be asked to apply it.
        PackageTools.reapplyNetworkBlocked(context)

        return applied
    }

    private fun accessibilityPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            AccessibilityKeepAlive.FILE,
            Context.MODE_PRIVATE
        )

    private fun isEligible(key: String): Boolean =
        key !in EXCLUDED_KEYS && EXCLUDED_PREFIXES.none { key.startsWith(it) }

    private fun shellAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun decodePackages(array: JSONArray): Set<String>? {
        val packages = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val packageName = array.opt(index) as? String ?: return null
            if (packageName.isNotBlank()) packages.add(packageName)
        }
        return packages
    }

    private fun encode(preferences: SharedPreferences): JSONObject {
        val result = JSONObject()
        preferences.all.forEach { (key, value) ->
            if (!isEligible(key)) return@forEach
            encodeValue(value)?.let { result.put(key, it) }
        }
        return result
    }

    private fun decode(json: JSONObject): Map<String, Value>? {
        val result = mutableMapOf<String, Value>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optJSONObject(key)?.let(::decodeValue) ?: return null
            result[key] = value
        }
        return result
    }

    private fun encodeValue(value: Any?): JSONObject? {
        return when (value) {
            is Boolean -> JSONObject().put(KEY_TYPE, "boolean").put(KEY_VALUE, value)
            is String -> JSONObject().put(KEY_TYPE, "string").put(KEY_VALUE, value)
            is Int -> JSONObject().put(KEY_TYPE, "int").put(KEY_VALUE, value)
            is Long -> JSONObject().put(KEY_TYPE, "long").put(KEY_VALUE, value)
            is Float -> value.takeIf { it.isFinite() }?.let { finite ->
                JSONObject().put(KEY_TYPE, "float").put(KEY_VALUE, finite)
            }

            is Set<*> -> {
                val strings = value.mapNotNull { it as? String }
                if (strings.size != value.size) return null
                JSONObject()
                    .put(KEY_TYPE, "string_set")
                    .put(KEY_VALUE, JSONArray(strings.sorted()))
            }

            else -> null
        }
    }

    private fun decodeValue(json: JSONObject): Value? {
        if (!json.has(KEY_VALUE)) return null

        return when (json.optString(KEY_TYPE)) {
            "boolean" -> Value.Bool(json.optBoolean(KEY_VALUE))
            "string" -> Value.Text(json.optString(KEY_VALUE))
            "int" -> (json.opt(KEY_VALUE) as? Number)?.let { Value.IntValue(it.toInt()) }
            "long" -> (json.opt(KEY_VALUE) as? Number)?.let { Value.LongValue(it.toLong()) }
            "float" -> (json.opt(KEY_VALUE) as? Number)
                ?.let { it.toFloat().takeIf { value -> value.isFinite() } }
                ?.let { Value.FloatValue(it) }

            "string_set" -> {
                val array = json.optJSONArray(KEY_VALUE) ?: return null
                val values = mutableSetOf<String>()
                for (index in 0 until array.length()) {
                    val item = array.opt(index) as? String ?: return null
                    values.add(item)
                }
                Value.StringSetValue(values)
            }

            else -> null
        }
    }
}
