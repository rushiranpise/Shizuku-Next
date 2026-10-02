package moe.shizuku.manager.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import moe.shizuku.manager.ShizukuSettings

/** Shizuku brand indigo, matching the XML theme's primaryColor. */
private val BrandColor = Color(0xFF3F51B5)

/**
 * True while the pure black (AMOLED) scheme is in use. Cards and the page are the
 * same colour in that scheme, so surfaces need something else to stay visible see
 * [moe.shizuku.manager.ui.component.SegmentedColumn].
 */
val LocalAmoledTheme = staticCompositionLocalOf { false }

/** Bumped when a theme preference changes so the theme re-reads the prefs. */
object ThemeState {
    var version by mutableIntStateOf(0)
        private set

    fun refresh() {
        version++
    }
}

@Composable
fun ShizukuTheme(content: @Composable () -> Unit) {
    // Read the counter so theme changes recompose the tree.
    ThemeState.version

    val context = LocalContext.current
    val prefs = ShizukuSettings.getPreferences()

    val darkTheme = when (ShizukuSettings.getNightMode()) {
        AppCompatDelegate.MODE_NIGHT_NO -> false
        AppCompatDelegate.MODE_NIGHT_YES -> true
        else -> isSystemInDarkTheme()
    }

    // On by default: the system palette is the one another app can replace, and replacing it is
    // the whole point of ColorBlendr and the apps like it - they push an overlay that rewrites
    // the system's own colour roles, and a screen that reads those roles follows.
    val useSystemColor = prefs.getBoolean(ShizukuSettings.Keys.KEY_USE_SYSTEM_COLOR, true) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val amoled = darkTheme && prefs.getBoolean(ShizukuSettings.Keys.KEY_BLACK_NIGHT_THEME, false)

    // The system's scheme taken whole, not re-derived from one of its colours.
    //
    // This was `seed = dynamicScheme(context).primary` fed back into material-kolor, which is a
    // Material 3 palette but not the one the system is showing: it regenerates from a single
    // seed and throws away the secondary, the tertiary, the neutral tones and every per-shade
    // override - exactly the parts a ColorBlendr theme sets. Reading the roles themselves is
    // what makes the app match the colours the user picked rather than merely resemble them.
    val baseScheme = if (useSystemColor) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // The brand seed, for when the system palette is not wanted: a fixed seed avoids a
        // washed-out grey palette on a desaturated wallpaper.
        rememberDynamicColorScheme(
            seedColor = BrandColor,
            isDark = darkTheme,
            isAmoled = amoled,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    }

    // The AMOLED switch only repaints the base surface roles, while the container
    // roles that the cards and the navigation bar actually use kept the standard
    // dark greys a black page with grey cards. Spread the black across every
    // surface role; row dividers keep the list readable without the card shape.
    val colorScheme = if (amoled) baseScheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
    ) else baseScheme

    // Match the status/navigation bar icons to the app's theme, not the system's;
    // otherwise a white in-app theme gets light icons on a white bar (invisible).
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAmoledTheme provides amoled) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
