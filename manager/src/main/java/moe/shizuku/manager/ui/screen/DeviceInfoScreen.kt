package moe.shizuku.manager.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.manage.DeviceInfo
import moe.shizuku.manager.ui.component.AppFilterChip
import moe.shizuku.manager.ui.component.CenteredMessage
import moe.shizuku.manager.ui.component.SegmentedCard
import moe.shizuku.manager.ui.component.SegmentedColumn
import moe.shizuku.manager.ui.component.SegmentedListItem
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * One screen for what the device is and what it is doing.
 *
 * The battery figures are the reason it exists: they are spread across the battery service, the
 * statistics dump and the fuel gauge, and the apps that show them either ask for permissions an
 * app cannot have or read a sysfs node this device denies even to the shell. This app has the
 * shell, so it can read the one dump that has everything, and it already knows the things a
 * battery app cannot: how Shizuku is running and whether the hiding lists are holding anything.
 *
 * Its own state is a section rather than a separate screen, because the questions come together:
 * a battery that is draining faster than it should is often a server restarting in a loop, and the
 * Shizuku section says so without leaving the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(bottomPadding: Dp, onBack: () -> Unit) {
    var battery by remember { mutableStateOf<DeviceInfo.Battery?>(null) }
    var capacity by remember { mutableStateOf<DeviceInfo.Capacity?>(null) }
    var history by remember { mutableStateOf<List<DeviceInfo.Sample>>(emptyList()) }
    var samsung by remember { mutableStateOf<DeviceInfo.Samsung?>(null) }
    var drain by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var usage by remember { mutableStateOf<DeviceInfo.Usage?>(null) }
    var system by remember { mutableStateOf<DeviceInfo.SystemFacts?>(null) }
    var thermal by remember { mutableStateOf<DeviceInfo.Thermal?>(null) }
    var cpu by remember { mutableStateOf<DeviceInfo.Cpu?>(null) }
    var shizuku by remember { mutableStateOf<DeviceInfo.ShizukuState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        loading = true
        withContext(Dispatchers.IO) {
            // One pass: the three dumps this screen reads are fetched once and parsed by
            // everything that wants them. Reading them per section turned a second of shell into
            // five.
            val all = DeviceInfo.snapshot()
            battery = all.battery
            samsung = all.samsung
            capacity = all.capacity
            history = all.history
            drain = all.drain
            usage = all.usage
            system = all.system
            thermal = all.thermal
            cpu = all.cpu
            shizuku = all.shizuku
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_device_info)) },
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                IconButton(onClick = { version++ }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.device_refresh))
                }
            }
        )

        if (loading) {
            CenteredMessage { LoadingIndicator() }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            battery?.let { now ->
                item { SectionTitle(stringResource(R.string.device_battery)) }
                item { BatteryHeadline(now) }
                item {
                    SegmentedCard {
                        SegmentedColumn {
                            item {
                                Fact(
                                    stringResource(R.string.device_status),
                                    DeviceInfo.statusText(now.status) +
                                        " · " + DeviceInfo.plugText(now.plugged)
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_temperature),
                                    DeviceInfo.celsius(now.temperatureC)
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_voltage),
                                    DeviceInfo.volts(now.voltageMv)
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_current),
                                    DeviceInfo.amps(now.currentUa)
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_current_average),
                                    DeviceInfo.amps(now.currentAverageUa)
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_charge_counter),
                                    now.chargeCounterUah
                                        ?.let { "${it / 1000} mAh" } ?: "—"
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_technology),
                                    now.technology ?: "—"
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_health),
                                    DeviceInfo.healthText(now.health)
                                )
                            }
                            // Only while there is a number to show. On this device both fields sit
                            // at zero until a charge session has been measured, and a row reading
                            // "0 mV" is an empty field pretending to be a reading.
                            now.maxChargingCurrentMa?.takeIf { it > 0 }?.let { value ->
                                item {
                                    Fact(
                                        stringResource(R.string.device_max_charging_current),
                                        "$value mA"
                                    )
                                }
                            }
                            now.maxChargingVoltageMv?.takeIf { it > 0 }?.let { value ->
                                item {
                                    Fact(
                                        stringResource(R.string.device_max_charging_voltage),
                                        "$value mV"
                                    )
                                }
                            }
                        }
                    }
                }

                // Samsung's own figures, which are the only route to a health percentage and a
                // cycle count on this hardware. Shown only when the device keeps them.
                samsung?.let { health ->
                    if (health.present) {
                        item { SectionTitle(stringResource(R.string.device_health_section)) }
                        item {
                            SegmentedCard {
                                SegmentedColumn {
                                    item {
                                        Fact(
                                            stringResource(R.string.device_bsoh),
                                            health.bsohPercent?.let { "$it%" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_cycles),
                                            health.cycles?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_asoc),
                                            health.asocPercent?.let { "$it%" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_first_use),
                                            health.firstUse ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_max_temp),
                                            DeviceInfo.celsius(health.maxTemperatureC)
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_max_current),
                                            health.maxCurrentMa?.let { "$it mA" } ?: "—"
                                        )
                                    }
                                }
                            }
                        }
                        item { Note(stringResource(R.string.device_health_source)) }
                    }
                }

                usage?.let { spent ->
                    if (spent.present) {
                        item { SectionTitle(stringResource(R.string.device_usage)) }
                        item {
                            SegmentedCard {
                                SegmentedColumn {
                                    item {
                                        Fact(
                                            stringResource(R.string.device_screen_on),
                                            spent.screenOn ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_time_on_battery),
                                            spent.onBattery ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_screen_off),
                                            spent.screenOff ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_doze),
                                            spent.doze ?: "—"
                                        )
                                    }
                                }
                            }
                        }
                        item { Note(stringResource(R.string.device_usage_note)) }
                    }
                }

                if (drain.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.device_drain)) }
                    item {
                        SegmentedCard {
                            SegmentedColumn {
                                drain.forEach { (name, mah) ->
                                    item {
                                        Fact(
                                            DeviceInfo.drainLabel(name),
                                            String.format(java.util.Locale.US, "%.1f mAh", mah)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Note(stringResource(R.string.device_drain_note)) }
                }

                capacity?.let { held ->
                    if (held.estimated != null || held.last != null) {
                        item { SectionTitle(stringResource(R.string.device_capacity)) }
                        item {
                            SegmentedCard {
                                SegmentedColumn {
                                    item {
                                        Fact(
                                            stringResource(R.string.device_capacity_estimated),
                                            held.estimated?.let { "$it mAh" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_capacity_learned),
                                            held.last?.let { "$it mAh" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_capacity_rated),
                                            held.rated?.let { "$it mAh" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_capacity_typical),
                                            held.typical?.let { "$it mAh" } ?: "—"
                                        )
                                    }
                                    item {
                                        Fact(
                                            stringResource(R.string.device_capacity_range),
                                            if (held.min != null && held.max != null) {
                                                "${held.min} – ${held.max} mAh"
                                            } else {
                                                "—"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Note(stringResource(R.string.device_capacity_note))
                        }
                    }
                }

                val levels = history.mapNotNull { it.level?.toFloat() }
                val temps = history.mapNotNull { it.temperatureC?.toFloat() }
                if (levels.size > 1) {
                    item { SectionTitle(stringResource(R.string.device_history)) }
                    item {
                        Chart(
                            values = levels,
                            colour = MaterialTheme.colorScheme.primary,
                            caption = stringResource(R.string.device_history_level),
                            detail = "${levels.first().toInt()}% → ${levels.last().toInt()}%"
                        )
                    }
                }
                if (temps.size > 1) {
                    item {
                        Chart(
                            values = temps,
                            colour = MaterialTheme.colorScheme.tertiary,
                            caption = stringResource(R.string.device_history_temperature),
                            detail = String.format(
                                java.util.Locale.US,
                                "%.0f–%.0f °C",
                                temps.min(),
                                temps.max()
                            )
                        )
                    }
                }
            }

            system?.let { facts ->
                item { SectionTitle(stringResource(R.string.device_system)) }
                item {
                    SegmentedCard {
                        SegmentedColumn {
                            item { Fact(stringResource(R.string.device_model), facts.model) }
                            item { Fact(stringResource(R.string.device_android), "${facts.android} (SDK ${facts.sdk})") }
                            item { Fact(stringResource(R.string.device_kernel), facts.kernel) }
                            item { Fact(stringResource(R.string.device_abi), facts.abi) }
                            item { Fact(stringResource(R.string.device_cores), facts.cpuCores.toString()) }
                            item { Fact(stringResource(R.string.device_uptime), DeviceInfo.uptime(facts.uptimeMillis)) }
                        }
                    }
                }
                item {
                    Measure(
                        label = stringResource(R.string.device_memory),
                        value = DeviceInfo.percent(facts.memoryUsedMb, facts.memoryTotalMb),
                        detail = "${DeviceInfo.gigabytes(facts.memoryUsedMb)} / " +
                            DeviceInfo.gigabytes(facts.memoryTotalMb)
                    )
                }
                item {
                    Measure(
                        label = stringResource(R.string.device_storage),
                        value = facts.storageTotalMb?.let { total ->
                            facts.storageFreeMb?.let { free ->
                                DeviceInfo.percent(total - free, total)
                            }
                        },
                        detail = "${DeviceInfo.gigabytes(facts.storageFreeMb)} free of " +
                            DeviceInfo.gigabytes(facts.storageTotalMb)
                    )
                }
            }

            thermal?.let { heat ->
                if (heat.present) {
                    item { SectionTitle(stringResource(R.string.device_thermal)) }
                    item {
                        SegmentedCard {
                            SegmentedColumn {
                                heat.zones.take(8).forEach { zone ->
                                    item {
                                        Fact(
                                            DeviceInfo.thermalLabel(zone.name),
                                            DeviceInfo.celsius(zone.temperatureC)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Note(stringResource(R.string.device_thermal_note, heat.total)) }
                }
            }

            cpu?.let { busy ->
                if (busy.present) {
                    item { SectionTitle(stringResource(R.string.device_cpu)) }
                    item {
                        SegmentedCard {
                            SegmentedColumn {
                                item {
                                    Fact(
                                        stringResource(R.string.device_load_average),
                                        DeviceInfo.loadAverage(busy.load)
                                    )
                                }
                                busy.processes.take(8).forEach { process ->
                                    item {
                                        Fact(
                                            process.name,
                                            DeviceInfo.percentText(process.percent)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Note(stringResource(R.string.device_cpu_note)) }
                }
            }

            shizuku?.let { state ->
                item { SectionTitle(stringResource(R.string.device_shizuku)) }
                item {
                    SegmentedCard {
                        SegmentedColumn {
                            item {
                                Fact(
                                    stringResource(R.string.device_server),
                                    if (state.running) {
                                        "Running" + (state.uid?.let { " as uid $it" } ?: "")
                                    } else {
                                        "Not running"
                                    }
                                )
                            }
                            item { Fact(stringResource(R.string.device_start_method), state.startMethod) }
                            item { Fact(stringResource(R.string.device_transport), state.transport) }
                            item {
                                Fact(
                                    stringResource(R.string.device_watchdog),
                                    if (state.watchdog) "On" else "Off"
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_app_in_front),
                                    state.hiddenFor ?: "—"
                                )
                            }
                            item {
                                Fact(
                                    stringResource(R.string.device_hiding_held),
                                    if (state.heldSignals.isEmpty()) {
                                        "Nothing"
                                    } else {
                                        state.heldSignals.joinToString(", ")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The level, as a number somebody reads first, and a bar under it.
 *
 * A ring would be prettier and would say less: the number and the bar answer "how much is left"
 * and "is it charging" in the order people ask them.
 */
@Composable
private fun BatteryHeadline(battery: DeviceInfo.Battery) {
    val level = battery.level
    SegmentedCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = level?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        battery.charging && level == 100 -> stringResource(R.string.device_charged)
                        battery.charging -> stringResource(R.string.device_charging)
                        else -> DeviceInfo.statusText(battery.status)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            if (level != null) {
                LinearProgressIndicator(
                    progress = { level / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(8.dp),
                    color = if (battery.charging) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

/**
 * A line of the platform's history.
 *
 * Drawn rather than listed, because a shape is the only way to see whether the last few hours were
 * a steady slope or a cliff, and the numbers that go with it are the two ends and the range.
 */
@Composable
private fun Chart(values: List<Float>, colour: Color, caption: String, detail: String) {
    SegmentedCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(caption, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(top = 12.dp)
            ) {
                if (values.size < 2) return@Canvas

                val low = values.min()
                val high = values.max()
                // A flat line still has to be drawn somewhere, and the middle is where it reads
                // as flat rather than as a floor or a ceiling.
                val span = (high - low).takeIf { it > 0.5f } ?: 1f
                val stepX = size.width / (values.size - 1)

                fun point(index: Int): Offset {
                    val value = values[index]
                    val y = size.height - ((value - low) / span) * size.height
                    return Offset(index * stepX, y.coerceIn(0f, size.height))
                }

                val line = Path().apply {
                    moveTo(point(0).x, point(0).y)
                    for (i in 1 until values.size) lineTo(point(i).x, point(i).y)
                }
                val area = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(point(0).x, point(0).y)
                    for (i in 1 until values.size) lineTo(point(i).x, point(i).y)
                    lineTo(size.width, size.height)
                    close()
                }

                drawPath(area, colour.copy(alpha = 0.18f))
                drawPath(line, colour, style = Stroke(width = 2.dp.toPx()))

                // The ends, so the two numbers in the caption have somewhere to point at.
                drawCircle(colour, radius = 3.dp.toPx(), center = point(0))
                drawCircle(colour, radius = 3.dp.toPx(), center = point(values.size - 1))
            }
        }
    }
}

/** One figure: what it is called on the left, what it says on the right. */
@Composable
private fun Fact(label: String, value: String) {
    SegmentedListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        centerSlots = true
    )
}

/** A proportion, with the bar that makes it readable at a glance. */
@Composable
private fun Measure(label: String, value: Int?, detail: String) {
    SegmentedCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (value != null) {
                LinearProgressIndicator(
                    progress = { value / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(8.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
