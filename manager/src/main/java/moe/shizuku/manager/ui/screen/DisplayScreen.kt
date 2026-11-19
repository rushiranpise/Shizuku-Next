package moe.shizuku.manager.ui.screen

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.component.ScrollToTopButton
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/** The value a row shows when the platform has no override for it. */
private const val DEFAULT = "default"

/**
 * What the shell says the screen is, split into the value in force and whether it is an override.
 *
 * `wm size` and `wm density` print the physical value and, when one was set, an override beside
 * it. The override is what the screen is actually using, and its presence is also the only way
 * to know whether a reset has anything to undo.
 */
private data class DisplayValue(val current: String?, val overridden: Boolean)

private fun parseWm(output: String?, overridePattern: String, physicalPattern: String): DisplayValue {
    val text = output ?: return DisplayValue(null, false)
    val override = Regex(overridePattern).find(text)?.groupValues?.get(1)
    val physical = Regex(physicalPattern).find(text)?.groupValues?.get(1)
    return DisplayValue(override ?: physical, override != null)
}

/** `settings get` answers the string "null" for a key that has never been set. */
private fun parseSetting(output: String?): String? =
    output?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * Resolution, density and refresh rate, through the shell.
 *
 * All three are settings the platform owns, changed with the same commands a computer would use:
 * `wm size`, `wm density`, and the two refresh-rate keys. Every change is read back before the
 * screen says it worked, because the point of an override is that the platform can refuse it.
 *
 * A wrong resolution or density can leave a phone whose screen is hard to use, which is why the
 * reset is on every row rather than behind a menu, and why values are bounded before they are
 * sent: a typo of one digit is the difference between 420 dpi and 42 dpi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(bottomPadding: Dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(ShizukuStateMachine.isRunning()) }
    var size by remember { mutableStateOf<DisplayValue?>(null) }
    var density by remember { mutableStateOf<DisplayValue?>(null) }
    var minHz by remember { mutableStateOf<String?>(null) }
    var maxHz by remember { mutableStateOf<String?>(null) }
    var modes by remember { mutableStateOf<List<Int>>(emptyList()) }

    var sizeField by remember { mutableStateOf("") }
    var densityField by remember { mutableStateOf("") }
    var minField by remember { mutableStateOf("") }
    var maxField by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                if (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) {
                    return@withContext null
                }
                Readings(
                    parseWm(
                        runShellCommand("wm size"),
                        "Override size: (\\d+x\\d+)",
                        "Physical size: (\\d+x\\d+)"
                    ),
                    parseWm(
                        runShellCommand("wm density"),
                        "Override density: (\\d+)",
                        "Physical density: (\\d+)"
                    ),
                    parseSetting(runShellCommand("settings get system min_refresh_rate")),
                    parseSetting(runShellCommand("settings get system peak_refresh_rate")),
                    parseRefreshRates(runShellCommand("dumpsys display"))
                )
            }
            running = read != null
            if (read != null) {
                size = read.size
                density = read.density
                minHz = read.minHz
                maxHz = read.maxHz
                modes = read.modes
                // Fields start on what is in force, so an apply changes one number rather
                // than asking for all of it again.
                if (sizeField.isBlank()) sizeField = read.size.current.orEmpty()
                if (densityField.isBlank()) densityField = read.density.current.orEmpty()
                if (minField.isBlank()) minField = read.minHz.orEmpty()
                if (maxField.isBlank()) maxField = read.maxHz.orEmpty()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    /**
     * Sends one command and answers whether the shell's exit status was zero.
     *
     * `; echo EXIT:$?` because most of these print nothing at all: without it an empty answer
     * and a refusal look the same from here.
     */
    suspend fun send(command: String): Boolean = withContext(Dispatchers.IO) {
        if (ShizukuStateMachine.update() != ShizukuStateMachine.State.RUNNING) return@withContext false
        val output = runShellCommand("$command; echo EXIT:\$?") ?: return@withContext false
        output.lineSequence().any { it.trim() == "EXIT:0" }
    }

    fun run(@StringRes what: Int, command: String) {
        if (!ShizukuStateMachine.isRunning()) {
            Toast.makeText(context, R.string.display_needs_shizuku, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val ok = send(command)
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.display_applied else R.string.display_failed, context.getString(what)),
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    fun invalid() {
        Toast.makeText(context, R.string.display_invalid, Toast.LENGTH_SHORT).show()
    }

    fun applySize() {
        val value = sizeField.trim()
        val match = Regex("(\\d{3,4})x(\\d{3,4})").find(value)
        if (match == null) return invalid()
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        if (width !in 320..5000 || height !in 320..5000) return invalid()
        run(R.string.display_resolution, "wm size ${width}x$height")
    }

    fun applyNumber(@StringRes what: Int, value: String, range: IntRange, command: (Int) -> String) {
        val number = value.trim().toIntOrNull() ?: return invalid()
        if (number !in range) return invalid()
        run(what, command(number))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_display)) },
            windowInsets = watchTopAppBarInsets(),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 4.dp,
                    end = 16.dp,
                    bottom = bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(
                            if (running) R.string.display_description else R.string.display_needs_shizuku
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (running) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                item {
                    DisplayControl(
                        title = stringResource(R.string.display_resolution),
                        summary = stringResource(R.string.display_resolution_summary),
                        current = size?.current,
                        field = sizeField,
                        onFieldChange = { sizeField = it },
                        keyboardType = KeyboardType.Text,
                        applyEnabled = running,
                        onApply = { applySize() },
                        onReset = {
                            run(R.string.display_resolution, "wm size reset")
                        }
                    )
                }

                item {
                    DisplayControl(
                        title = stringResource(R.string.display_density),
                        summary = stringResource(R.string.display_density_summary),
                        current = density?.current,
                        field = densityField,
                        onFieldChange = { densityField = it },
                        keyboardType = KeyboardType.Number,
                        applyEnabled = running,
                        onApply = {
                            applyNumber(
                                R.string.display_density,
                                densityField,
                                100..1000
                            ) { "wm density $it" }
                        },
                        onReset = {
                            run(R.string.display_density, "wm density reset")
                        }
                    )
                }

                item {
                    RefreshControl(
                        title = stringResource(R.string.display_min_refresh),
                        summary = stringResource(R.string.display_min_refresh_summary),
                        current = minHz,
                        rates = modes,
                        field = minField,
                        onFieldChange = { minField = it },
                        enabled = running,
                        onPick = { rate ->
                            run(
                                R.string.display_min_refresh,
                                "settings put system min_refresh_rate $rate"
                            )
                        },
                        onApplyManual = {
                            applyNumber(
                                R.string.display_min_refresh,
                                minField,
                                24..240
                            ) { "settings put system min_refresh_rate $it" }
                        },
                        onReset = {
                            run(
                                R.string.display_min_refresh,
                                "settings delete system min_refresh_rate"
                            )
                        }
                    )
                }

                item {
                    RefreshControl(
                        title = stringResource(R.string.display_max_refresh),
                        summary = stringResource(R.string.display_max_refresh_summary),
                        current = maxHz,
                        rates = modes,
                        field = maxField,
                        onFieldChange = { maxField = it },
                        enabled = running,
                        onPick = { rate ->
                            run(
                                R.string.display_max_refresh,
                                "settings put system peak_refresh_rate $rate"
                            )
                        },
                        onApplyManual = {
                            applyNumber(
                                R.string.display_max_refresh,
                                maxField,
                                24..240
                            ) { "settings put system peak_refresh_rate $it" }
                        },
                        onReset = {
                            run(
                                R.string.display_max_refresh,
                                "settings delete system peak_refresh_rate"
                            )
                        }
                    )
                }
            }

            ScrollToTopButton(
                listState = listState,
                bottomPadding = bottomPadding,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

/** The readings taken together, so one binder miss cannot leave half the screen stale. */
private data class Readings(
    val size: DisplayValue,
    val density: DisplayValue,
    val minHz: String?,
    val maxHz: String?,
    val modes: List<Int>
)

/**
 * The rates the panel says it can do, from `dumpsys display`.
 *
 * Every supported mode is listed with its own `fps=`, so the set is read from the device rather
 * than guessed: a panel that switches between 60 and 120 is offered those two, and one that only
 * does 60 is not offered 120. Rounded, because the platform keeps rates as floats and prints
 * 59.999996 for 60.
 *
 * An empty list is a real answer - a ROM that prints modes in another shape, or refuses the
 * dump - and the screen falls back to a typed value rather than inventing a list.
 */
private fun parseRefreshRates(output: String?): List<Int> =
    output
        ?.let { text -> Regex("fps=([0-9.]+)").findAll(text) }
        ?.mapNotNull { it.groupValues[1].toFloatOrNull()?.roundToInt() }
        ?.filter { it in 1..240 }
        ?.distinct()
        ?.sorted()
        ?.toList()
        ?: emptyList()

/**
 * One of the two refresh-rate keys, as the rates the panel reported.
 *
 * The numbers come from the display itself, so a preset here is a mode the panel can actually
 * run; the field is the fallback for a ROM whose dump could not be read, and it is only shown
 * when there are no rates to offer.
 */
@Composable
private fun RefreshControl(
    title: String,
    summary: String,
    current: String?,
    rates: List<Int>,
    field: String,
    onFieldChange: (String) -> Unit,
    enabled: Boolean,
    onPick: (Int) -> Unit,
    onApplyManual: () -> Unit,
    onReset: () -> Unit
) {
    val currentRate = current?.toFloatOrNull()?.roundToInt()

    SegmentedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.display_current, current ?: DEFAULT),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )

            if (rates.isEmpty()) {
                Text(
                    text = stringResource(R.string.display_modes_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = onFieldChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rates.forEach { rate ->
                        FilterChip(
                            selected = currentRate == rate,
                            onClick = { onPick(rate) },
                            enabled = enabled,
                            label = { Text(stringResource(R.string.display_hz, rate)) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (rates.isEmpty()) {
                    Button(onClick = onApplyManual, enabled = enabled) {
                        Text(stringResource(R.string.display_apply))
                    }
                }
                TextButton(onClick = onReset, enabled = enabled) {
                    Text(stringResource(R.string.display_reset))
                }
            }
        }
    }
}

@Composable
private fun DisplayControl(
    title: String,
    summary: String,
    current: String?,
    field: String,
    onFieldChange: (String) -> Unit,
    keyboardType: KeyboardType,
    applyEnabled: Boolean,
    onApply: () -> Unit,
    onReset: () -> Unit
) {
    SegmentedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // "default" rather than an empty line: a key nobody set is a state, and the
                // reset is what goes back to it.
                text = stringResource(R.string.display_current, current ?: DEFAULT),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            OutlinedTextField(
                value = field,
                onValueChange = onFieldChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = applyEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onApply, enabled = applyEnabled) {
                    Text(stringResource(R.string.display_apply))
                }
                TextButton(onClick = onReset, enabled = applyEnabled) {
                    Text(stringResource(R.string.display_reset))
                }
            }
        }
    }
}
