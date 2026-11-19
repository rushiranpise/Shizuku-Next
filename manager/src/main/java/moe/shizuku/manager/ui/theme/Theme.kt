package moe.shizuku.manager.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils

/** Shizuku brand indigo, matching the XML theme's primaryColor. */
private val BrandColor = Color(0xFF3F51B5)

/** The indigo seed used until the user chooses another accent. */
const val DEFAULT_ACCENT_COLOR = "#3F51B5"

/**
 * True while the pure black (AMOLED) scheme is in use. Cards and the page are the
 * same colour in that scheme, so surfaces need something else to stay visible see
 * [moe.shizuku.manager.ui.component.SegmentedColumn].
 */
val LocalAmoledTheme = staticCompositionLocalOf { false }

/**
 * True on a television. A TV is driven by a remote's D-pad rather than a finger, so the few
 * places that lay themselves out for a thumb - the floating tab bar above all - read this and
 * do something a remote can reach instead. Everything else is the phone layout as it is.
 */
val LocalTelevision = staticCompositionLocalOf { false }

/**
 * True on a watch. The app is a phone layout, and a watch is roughly 226dp square, so this is
 * also what tells the theme to scale the whole Compose tree down instead of letting the fixed
 * bars and five tabs take more width than the display has. Screens can read it for the few
 * places that need a smaller title or action row.
 */
val LocalWatch = staticCompositionLocalOf { false }

/** The logical width a phone layout is written for. A watch is below it, so it is scaled. */
private const val WatchTargetWidthDp = 350f

/** Do not shrink so far that text stops being readable on the smallest watch. */
private const val WatchMinScale = 0.65f

/**
 * The state layers on a TV. With a remote, focus is the only cursor there is, and the stock
 * focused layer - a tenth of the content colour - is close to invisible from a sofa: on a
 * black page the focused button was a shade of grey away from the rest. Pressed and dragged
 * stay as they are; only focus is raised to something that can be seen across a room.
 */
private val TelevisionRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.32f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.10f
    )
)

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
    val configuration = LocalConfiguration.current
    val inheritedDensity = LocalDensity.current
    val alreadyWatch = LocalWatch.current
    val watch = remember { EnvironmentUtils.isWatch() }
    val densityScale = if (watch && !alreadyWatch && configuration.screenWidthDp > 0) {
        (configuration.screenWidthDp / WatchTargetWidthDp).coerceIn(WatchMinScale, 1f)
    } else {
        1f
    }
    // ShizukuTheme is wrapped around the setup screen and again around the main app. Scaling the
    // second one too would make the watch UI 0.56x instead of 0.75x, so LocalWatch doubles as the
    // marker that this tree has already been scaled.
    val density = if (densityScale == 1f) {
        inheritedDensity
    } else {
        Density(inheritedDensity.density * densityScale, inheritedDensity.fontScale)
    }
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
    // The switch above can either black every surface or only the page behind the cards. The
    // second is what the appearance request asked for: a black page, with dialogs and menus
    // still reading as raised surfaces instead of disappearing into it.
    val amoledBackgroundOnly = amoled &&
        prefs.getBoolean(ShizukuSettings.Keys.KEY_AMOLED_BACKGROUND_ONLY, false)
    val highContrast = prefs.getBoolean(ShizukuSettings.Keys.KEY_HIGH_CONTRAST, false)
    val accentHex = prefs.getString(
        ShizukuSettings.Keys.KEY_ACCENT_COLOR,
        DEFAULT_ACCENT_COLOR
    ) ?: DEFAULT_ACCENT_COLOR
    val accentColor = remember(accentHex) { parseAccentColor(accentHex) }
    val paletteStyleName = prefs.getString(
        ShizukuSettings.Keys.KEY_PALETTE_STYLE,
        PaletteStyle.TonalSpot.name
    ) ?: PaletteStyle.TonalSpot.name
    val paletteStyle = remember(paletteStyleName) {
        runCatching { PaletteStyle.valueOf(paletteStyleName) }
            .getOrDefault(PaletteStyle.TonalSpot)
    }

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
            seedColor = accentColor,
            isDark = darkTheme,
            // material-kolor's AMOLED option blacks every surface. Background-only asked for
            // the page black and the containers left alone, so only the full mode gets it.
            isAmoled = amoled && !amoledBackgroundOnly,
            style = paletteStyle,
            contrastLevel = if (highContrast) Contrast.High.value else Contrast.Default.value,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    }

    // The AMOLED switch only repaints the base surface roles, while the container
    // roles that the cards and the navigation bar actually use kept the standard
    // dark greys a black page with grey cards. Spread the black across every
    // surface role; row dividers keep the list readable without the card shape.
    val colorScheme = when {
        amoledBackgroundOnly -> baseScheme.copy(background = Color.Black)
        amoled -> baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
        )
        else -> baseScheme
    }.let { if (highContrast) it.withHighContrast(darkTheme) else it }

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

    val television = remember { EnvironmentUtils.isTelevision() }

    CompositionLocalProvider(
        LocalDensity provides density,
        LocalWatch provides (alreadyWatch || watch),
        LocalAmoledTheme provides amoled,
        LocalTelevision provides television,
        LocalRippleConfiguration provides
            if (television) TelevisionRipple else LocalRippleConfiguration.current
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}

/** The strongest text and outline roles, for the high contrast switch. */
private fun ColorScheme.withHighContrast(dark: Boolean): ColorScheme {
    val foreground = if (dark) Color.White else Color.Black
    return copy(
        onBackground = foreground,
        onSurface = foreground,
        onSurfaceVariant = if (dark) Color(0xFFE6E6E6) else Color(0xFF1A1A1A),
        outline = if (dark) Color(0xFFBDBDBD) else Color(0xFF3A3A3A),
        outlineVariant = if (dark) Color(0xFF8C8C8C) else Color(0xFF6B6B6B),
    )
}

/** The stored #RRGGBB accent, or the brand colour when it cannot be read. */
private fun parseAccentColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(BrandColor)

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}
