package moe.shizuku.manager.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import moe.shizuku.manager.ShizukuSettings
import rikka.material.app.LocaleDelegate

/**
 * The language the app is shown in, and the two different places that has to be said.
 *
 * The choice used to be written to a preference and then read by nothing: `LocaleDelegate` was
 * handed the value at startup, but the only activities that consult it are the ones that come
 * from `MaterialActivity`, and the whole Compose UI - which is where the setting is picked - is
 * `MainActivity`, a plain `ComponentActivity` that never wrapped its context. So choosing a
 * language changed a stored string and nothing else.
 *
 * On Android 13 the platform grows its own answer: a per-app locale, which the system settings
 * screen can change too, and which `MainActivity` already inherits because it is applied to the
 * app and not to a context. That is the one to hand the choice to where it exists - it is the
 * only route that keeps our own picker and the system's in agreement. Below 13 there is nothing
 * to hand it to, so the value is applied to the context of every activity as it attaches; see
 * [wrap].
 */
object AppLocale {

    /** The stored choice for "whatever the device is set to", and the first entry of the list. */
    const val SYSTEM = "SYSTEM"

    /** What the user chose, as a BCP 47 tag, or [SYSTEM]. */
    fun current(): String = ShizukuSettings.getLanguageTag()

    /** The locale a tag stands for; [SYSTEM] means the device's own. */
    fun localeOf(tag: String): Locale =
        if (tag == SYSTEM) Locale.getDefault() else Locale.forLanguageTag(tag)

    /**
     * Records [tag] and applies it.
     *
     * On Android 13+ the framework per-app locale is set, which also rebuilds the activities. On
     * older releases only the preference is written: the caller has to recreate the activity for
     * [wrap] to be seen, which it can do because nothing else will.
     *
     * `LocaleDelegate` is set either way, because the activities that come from `MaterialActivity`
     * ask *it*, not the framework, and would otherwise keep showing the language the process
     * started with.
     */
    fun select(context: Context, tag: String) {
        ShizukuSettings.setLanguageTag(tag)

        val locale = localeOf(tag)
        LocaleDelegate.defaultLocale = locale

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (tag == SYSTEM) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(tag)
            }
        }
    }

    /**
     * The context an activity should attach with, so the chosen language is the one it renders in.
     *
     * Android 13+ is left alone on purpose: the framework has already put the per-app locale into
     * the configuration, and wrapping it again would override that with our stored preference,
     * which is the *stale* copy whenever the language was changed from the system settings.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base

        val tag = current()
        if (tag == SYSTEM) return base

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(localeOf(tag))
        return base.createConfigurationContext(configuration)
    }

    /**
     * Points `LocaleDelegate` at the language in force, at startup.
     *
     * Called once per process, so a change made while the app is alive is not seen here - which is
     * what [select] and [reconcile] are for.
     */
    fun initialize(context: Context) {
        reconcile(context)
        LocaleDelegate.defaultLocale = localeOf(current())
    }

    /**
     * The language the platform says this app is in, or null on a release with no per-app locale
     * and when none is set.
     */
    fun appliedTag(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        return runCatching {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        }.getOrNull()
    }

    /**
     * Brings the stored choice back in line with the platform's.
     *
     * From Android 13 the per-app locale is not only ours to set: the system settings screen
     * changes it too, and the framework does not restart the process when it does - only the
     * activities. So a change made outside the app leaves the stored copy behind, and the stored
     * copy is what the picker ticks and what the row reports. Reconciling is done whenever the
     * app comes back to the foreground, which is when such a change is about to be looked at.
     *
     * Returns whether the stored value had to change. Older releases cannot be out of step,
     * since there the preference is the only thing that exists.
     */
    fun reconcile(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val applied = appliedTag(context) ?: SYSTEM
        if (applied == ShizukuSettings.getLanguageTag()) return false

        ShizukuSettings.setLanguageTag(applied)
        LocaleDelegate.defaultLocale = localeOf(applied)
        return true
    }

    /**
     * The name of a language, in that language - "Русский" and not "Russian" - which is what makes
     * the list usable to somebody who cannot read the language it is written in now.
     */
    fun label(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(locale)
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * The activity a Compose context is standing in for.
     *
     * `LocalContext` is not always the activity itself; it can be a wrapper around it, and
     * `recreate()` needs the real one.
     */
    fun activityOf(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
