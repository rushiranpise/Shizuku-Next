package moe.shizuku.manager.utils

import androidx.annotation.StringRes
import moe.shizuku.manager.ShizukuApplication

/**
 * Text for code that has no [android.content.Context] of its own: the module runner, the file
 * helpers, the starter. They hand a sentence to the screen, and the sentence has to be in the
 * language the person picked, which is why it goes through [AppLocale.wrap] and not through the
 * bare application context (that one only follows the device language below Android 13).
 */
object AppStrings {

    fun get(@StringRes id: Int, vararg args: Any): String {
        val context = AppLocale.wrap(ShizukuApplication.appContext)
        return if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}
