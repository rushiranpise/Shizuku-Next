package moe.shizuku.manager.manage

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.runShellCommand
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the device is and what its battery is doing, assembled from the places that will answer.
 *
 * The obvious source is `/sys/class/power_supply/battery`, which is where the battery apps read
 * `charge_full`, `charge_full_design` and `cycle_count` from - and on this device it answers
 * "Permission denied" even to the shell, so it is not a source at all: those apps would show
 * nothing here either. What does answer is the platform's own dump, which the shell can read and
 * an app cannot, and the battery service, which an app can read itself:
 *
 *     dumpsys battery        every live figure, including current and the charge counter
 *     dumpsys batterystats   the learned capacity, and a timestamped history to draw
 *     BatteryManager         the same live figures without a shell, as a fallback
 *
 * Everything here degrades rather than lies: a figure that cannot be read comes back absent, and
 * the screen shows the dash instead of a zero.
 */
object DeviceInfo {

    /** The battery as it is now. Anything null could not be read on this device. */
    data class Battery(
        val level: Int?,
        val status: Int?,
        val health: Int?,
        val plugged: Int?,
        val temperatureC: Double?,
        val voltageMv: Int?,
        val currentUa: Int?,
        /**
         * The average the battery service keeps, which is the figure worth showing: the instant
         * one moves by milliamps between reads and reads as zero whenever the device is idle.
         */
        val currentAverageUa: Int?,
        val chargeCounterUah: Long?,
        val technology: String?,
        val maxChargingCurrentMa: Int?,
        val maxChargingVoltageMv: Int?
    ) {
        val charging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * What the battery holds, in mAh, as the platform has learned it.
     *
     * [estimated] is what the fuel gauge believes now; the learned three are the range it has
     * settled on over time. The design capacity is deliberately absent: the platform does not
     * report it, the model's figure has to come from outside, and a health percentage invented
     * from a wrong design capacity is worse than no percentage.
     */
    data class Capacity(
        val estimated: Int?,
        val last: Int?,
        val min: Int?,
        val max: Int?,
        /** What the model was rated at, and what it was designed for - from the same dump. */
        val rated: Int? = null,
        val typical: Int? = null
    )

    /** One point of the platform's own battery history. */
    data class Sample(
        val at: Long,
        val level: Int?,
        val temperatureC: Double?,
        val voltageMv: Int?,
        val currentUa: Int?
    )

    /**
     * Samsung's own figures, kept in the battery service's backup and printed by `dumpsys battery`.
     *
     * This is where health and cycle count actually live on a Samsung device: the fuel gauge
     * nodes are denied to every uid but root, and the platform's health flag is a verdict rather
     * than a number. `mSavedBatteryBsoh` is the figure a battery-health app exists to show, and
     * `mSavedBatteryUsage` is hundredths of a cycle, which is why 67150 is 671.
     */
    data class Samsung(
        val asocPercent: Int?,
        val bsohPercent: Int?,
        val cycles: Double?,
        val firstUse: String?,
        val maxTemperatureC: Double?,
        val maxCurrentMa: Int?
    ) {
        /** Whether anything was found at all, which is false on any non-Samsung device. */
        val present: Boolean
            get() = asocPercent != null || bsohPercent != null || cycles != null
    }

    fun samsung(dumpText: String? = null): Samsung {
        val dump = dumpText ?: runShellCommand("dumpsys battery")
            ?: return Samsung(null, null, null, null, null, null)

        fun first(pattern: Regex): String? = pattern.find(dump)?.groupValues?.get(1)

        val firstUse = first(Regex("""battery FirstUseDate:\s*\[(\d{8})]"""))

        return Samsung(
            asocPercent = first(Regex("""mSavedBatteryAsoc:\s*\[(\d+)]"""))?.toIntOrNull(),
            bsohPercent = first(Regex("""mSavedBatteryBsoh:\s*([0-9.]+)"""))
                ?.toDoubleOrNull()?.roundToInt(),
            cycles = first(Regex("""mSavedBatteryUsage:\s*\[(\d+)]"""))
                ?.toDoubleOrNull()?.div(100.0),
            firstUse = firstUse?.let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6)}" },
            maxTemperatureC = first(Regex("""mSavedBatteryMaxTemp:\s*(\d+)"""))
                ?.toDoubleOrNull()?.div(10.0),
            maxCurrentMa = first(Regex("""mSavedBatteryMaxCurrent:\s*(-?\d+)"""))
                ?.toIntOrNull()?.div(1000)
        )
    }

    /**
     * The platform's own time counters: screen on, on battery, screen off, doze.
     *
     * They reset every time the battery is charged to full, so a phone charged an hour ago reads
     * seconds here and one that has been off the charger all day reads hours. That is worth
     * showing rather than hiding, because the number is otherwise read as wrong.
     */
    data class Usage(
        val screenOn: String?,
        val onBattery: String?,
        val screenOff: String?,
        val doze: String?
    ) {
        val present: Boolean get() = screenOn != null || onBattery != null
    }

    fun usage(dumpText: String? = null): Usage {
        val dump = dumpText ?: runShellCommand("dumpsys batterystats") ?: return Usage(null, null, null, null)

        // The figures sit before the parenthesised percentage: "8s 609ms (100.0%) 0x, ...".
        //
        // The colon is load-bearing: "Screen on" also begins the discharge line "Screen on
        // discharge: 0 mAh", and matching that first read the counter as an empty charge figure.
        fun span(label: String): String? = dump.lineSequence()
            .firstOrNull { it.startsWith("  $label:") }
            ?.substringAfter(':')?.trim()
            ?.substringBefore('(')?.trim()
            ?.takeIf { it.isNotEmpty() }

        return Usage(
            screenOn = span("Screen on"),
            onBattery = span("Time on battery"),
            screenOff = span("Time on battery screen off"),
            doze = span("Time on battery screen doze")
        )
    }

    /**
     * What used the battery, by component, in mAh, biggest first.
     *
     * Not per app, and that is the platform's doing rather than a shortcut: the dump this build
     * prints attributes power to components - screen, cpu, radio, video - and keeps per-app
     * figures only as wake locks and times. A list headed "what is draining the battery" with
     * invented per-app numbers under it would be the one thing this screen must not do.
     */
    fun drain(dumpText: String? = null): List<Pair<String, Double>> {
        val dump = dumpText ?: runShellCommand("dumpsys batterystats --charged")
            ?: return emptyList()
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.contains("Estimated power use") }
        if (start < 0) return emptyList()

        val values = mutableListOf<Pair<String, Double>>()
        for (line in lines.drop(start + 1)) {
            // The Global block is over when the states with a qualifier begin: they repeat the
            // same names with smaller numbers beside them, and adding those would double count.
            if (line.contains("(")) break
            val match = DRAIN.find(line) ?: continue
            val name = match.groupValues[1]
            val amount = match.groupValues[2].toDoubleOrNull() ?: continue
            if (amount > 0.0) values += name to amount
        }
        return values.sortedByDescending { it.second }
    }

    private val DRAIN = Regex("""^\s{4}([a-z_]+):\s*([0-9.]+)""")

    /** The component names the dump uses, as words: it writes "scrn" and "blue". */
    fun drainLabel(name: String): String = when (name) {
        "scrn", "screen" -> "Screen"
        "cpu" -> "CPU"
        "blue", "bluetooth" -> "Bluetooth"
        "mobile_radio", "radio" -> "Mobile radio"
        "gnss" -> "Location"
        "wifi" -> "Wi-Fi"
        "sensors" -> "Sensors"
        "audio" -> "Audio"
        "video" -> "Video"
        "camera" -> "Camera"
        "flashlight" -> "Flashlight"
        "wakelock" -> "Wake locks"
        else -> name.replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    // ---- the battery, live --------------------------------------------------------

    /**
     * Everything on the screen, from the three dumps it needs.
     *
     * One call rather than one per section, because each of these is a shell round trip of half a
     * second: asking five times for three dumps is the difference between a screen that opens and
     * one that is seen to be loading.
     */
    data class Snapshot(
        val battery: Battery,
        val samsung: Samsung,
        val capacity: Capacity,
        val history: List<Sample>,
        val drain: List<Pair<String, Double>>,
        val usage: Usage,
        val system: SystemFacts,
        val thermal: Thermal,
        val cpu: Cpu,
        val shizuku: ShizukuState
    )

    fun snapshot(): Snapshot {
        val batteryDump = runShellCommand("dumpsys battery")
        val statsDump = runShellCommand("dumpsys batterystats")
        val chargedDump = runShellCommand("dumpsys batterystats --charged")
        val cpuDump = runShellCommand("dumpsys cpuinfo")
        val thermalDump = runShellCommand(THERMAL_COMMAND)

        return Snapshot(
            battery = battery(batteryDump),
            samsung = samsung(batteryDump),
            capacity = capacity(statsDump),
            history = history(statsDump),
            drain = drain(chargedDump),
            usage = usage(statsDump),
            system = system(),
            thermal = thermal(thermalDump),
            cpu = cpu(cpuDump),
            shizuku = shizuku()
        )
    }

    /**
     * The live figures.
     *
     * The shell first, because it is the only route to the current and the charge counter on
     * devices that hide them from apps, and the battery service second so that the screen still
     * has something to show with Shizuku stopped.
     */
    fun battery(dumpText: String? = null): Battery {
        val fromShell = dumpText ?: runShellCommand("dumpsys battery")
        return if (fromShell != null) parseBattery(fromShell) else batteryFromService()
    }

    private fun parseBattery(dump: String): Battery {
        fun value(name: String): String? = dump.lineSequence()
            .firstOrNull { it.trim().startsWith("$name:") }
            ?.substringAfter(':')
            ?.trim()

        fun int(name: String): Int? = value(name)?.toIntOrNull()

        return Battery(
            level = int("level"),
            status = int("status"),
            health = int("health"),
            plugged = when (value("USB powered")) {
                "true" -> BatteryManager.BATTERY_PLUGGED_USB
                else -> when (value("AC powered")) {
                    "true" -> BatteryManager.BATTERY_PLUGGED_AC
                    else -> when (value("Wireless powered")) {
                        "true" -> BatteryManager.BATTERY_PLUGGED_WIRELESS
                        else -> 0
                    }
                }
            },
            // Tenths of a degree, which is what the platform reports.
            temperatureC = int("temperature")?.let { it / 10.0 },
            voltageMv = int("voltage"),
            // Microamps, and negative while discharging: the sign is the fact, not noise.
            currentUa = int("current now") ?: int("current_now"),
            currentAverageUa = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = value("charge counter")?.toLongOrNull()
                ?: value("Charge counter")?.toLongOrNull(),
            technology = value("technology"),
            maxChargingCurrentMa = int("Max charging current"),
            maxChargingVoltageMv = int("Max charging voltage")
        )
    }

    /**
     * One battery property, read from the service.
     *
     * Any app may ask for these, so this is the half of the screen that works with Shizuku
     * stopped - and the average current in particular is only available this way.
     */
    private fun batteryProperty(property: Int): Int? = runCatching {
        ShizukuApplication.application.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(property)
    }.getOrNull()

    /** The same question asked of the battery service, for when there is no shell. */
    private fun batteryFromService(): Battery {
        val context = ShizukuApplication.application
        val sticky = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val manager = context.getSystemService(BatteryManager::class.java)

        fun extra(name: String): Int? = sticky?.getIntExtra(name, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }

        val level = extra(BatteryManager.EXTRA_LEVEL)
        val scale = extra(BatteryManager.EXTRA_SCALE)?.takeIf { it > 0 }
        val temperature = extra(BatteryManager.EXTRA_TEMPERATURE)

        return Battery(
            level = if (level != null && scale != null) level * 100 / scale else level,
            status = extra(BatteryManager.EXTRA_STATUS),
            health = extra(BatteryManager.EXTRA_HEALTH),
            plugged = extra(BatteryManager.EXTRA_PLUGGED),
            temperatureC = temperature?.let { it / 10.0 },
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE),
            currentUa = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverageUa = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = manager
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.toLong(),
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            maxChargingCurrentMa = null,
            maxChargingVoltageMv = null
        )
    }

    // ---- capacity, and the history to draw ----------------------------------------

    /**
     * The learned capacity. Needs the shell: it exists only in the battery statistics dump.
     */
    fun capacity(dumpText: String? = null): Capacity {
        val dump = dumpText ?: runShellCommand("dumpsys batterystats")
            ?: return Capacity(null, null, null, null)
        fun capacityOf(label: String): Int? = dump.lineSequence()
            .firstOrNull { it.contains(label, ignoreCase = true) }
            ?.let { Regex("""(\d+)\s*mAh""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // "Capacity: 5000, Rated: 4855, Typical: 5000" on one line, and the rated figure is the
        // closest thing to a design capacity the platform ever prints.
        val ratedLine = dump.lineSequence().firstOrNull { it.contains("Rated:") }
        fun rated(name: String): Int? = ratedLine?.let { line ->
            Regex("""$name:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }

        return Capacity(
            estimated = capacityOf("Estimated battery capacity"),
            last = capacityOf("Last learned battery capacity"),
            min = capacityOf("Min learned battery capacity"),
            max = capacityOf("Max learned battery capacity"),
            rated = rated("Rated"),
            typical = rated("Typical")
        )
    }

    /**
     * The platform's own history, oldest first.
     *
     * Two kinds of line carry a point: one where the battery state changed, which has the
     * temperature, voltage and current written out, and the ordinary lines that carry only the
     * level. A point is kept whenever there is something new to say, so the series is the shape of
     * the discharge rather than a fixed interval padded with repeats.
     */
    fun history(dumpText: String? = null): List<Sample> {
        val dump = dumpText ?: runShellCommand("dumpsys batterystats") ?: return emptyList()

        val samples = mutableListOf<Sample>()
        var lastLevel: Int? = null
        var lastTemp: Double? = null
        var lastVolt: Int? = null
        var lastCurrent: Int? = null
        var lastAt = 0L

        dump.lineSequence().forEach { line ->
            val stamp = HISTORY_STAMP.find(line) ?: return@forEach
            val at = parseStamp(stamp.groupValues[1]) ?: return@forEach
            val level = stamp.groupValues[2].toIntOrNull()

            val temperature = FIELD_TEMP.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { it / 10.0 }
            val voltage = FIELD_VOLT.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val current = FIELD_CURRENT.find(line)?.groupValues?.get(1)?.toIntOrNull()

            val changed = temperature != null || voltage != null || current != null
            val levelMoved = level != null && level != lastLevel

            if (!changed && !levelMoved) return@forEach

            // A state line repeats its values on every following line; keeping only the first
            // stops a flat stretch from becoming a thousand identical points.
            if (changed && temperature == lastTemp && voltage == lastVolt && current == lastCurrent) {
                return@forEach
            }

            samples += Sample(
                at = at,
                level = level ?: lastLevel,
                temperatureC = temperature ?: lastTemp,
                voltageMv = voltage ?: lastVolt,
                currentUa = current ?: lastCurrent
            )
            lastLevel = level ?: lastLevel
            lastTemp = temperature ?: lastTemp
            lastVolt = voltage ?: lastVolt
            lastCurrent = current ?: lastCurrent
            lastAt = at
        }

        return samples.takeLast(400)
    }

    private val HISTORY_STAMP = Regex("""^\s*(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)""")

    /*
     * Word boundaries, and not decoration: this device writes its own temperatures on the same
     * line as the battery's - `ap_temp=34 pa_temp=34 skin_temp=32` beside `temp=305` - and a bare
     * `temp=` matches those too. The first version of this read them and drew a chart whose range
     * was 3-32 degrees, because 34 tenths of a degree is 3.4.
     */
    private val FIELD_TEMP = Regex("""\btemp=(\d+)""")
    private val FIELD_VOLT = Regex("""\bvolt=(\d+)""")
    private val FIELD_CURRENT = Regex("""\bcurrent=(-?\d+)""")

    private fun parseStamp(text: String): Long? = runCatching {
        // The bare month and day, with the year taken from now: this is a rolling window of the
        // last few days, so the year is never in question.
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .parse("$year-$text")
            ?.time
    }.getOrNull()

    /** Formatters the screen reuses, so a timestamp reads the same everywhere. */
    fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    fun day(at: Long): String = SimpleDateFormat("MMM d", Locale.US).format(Date(at))

    // ---- the rest of the device ---------------------------------------------------

    data class SystemFacts(
        val model: String,
        val device: String,
        val brand: String,
        val android: String,
        val sdk: Int,
        val kernel: String,
        val abi: String,
        val uptimeMillis: Long,
        val memoryTotalMb: Int?,
        val memoryUsedMb: Int?,
        val storageTotalMb: Int?,
        val storageFreeMb: Int?,
        val cpuCores: Int
    )

    fun system(): SystemFacts {
        val activity = ShizukuApplication.application.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val storage = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()

        val totalMb = (memory.totalMem / 1024 / 1024).toInt()
        val availMb = (memory.availMem / 1024 / 1024).toInt()

        return SystemFacts(
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            device = Build.DEVICE,
            brand = Build.BRAND,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            // `os.version` first, because it is the only route an app has: /proc/version is
            // denied to app domains on Android 11 and up, which is what made this read "unknown".
            // The shell can still read the file, so it is the second try rather than a guess.
            kernel = kernelRelease(
                System.getProperty("os.version").orEmpty().ifBlank {
                    runShellCommand("cat /proc/version")
                        ?.trim()
                        ?.substringAfter("version ")
                        ?.substringBefore(" #")
                        ?: "—"
                }
            ),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            uptimeMillis = SystemClock.elapsedRealtime(),
            memoryTotalMb = totalMb,
            memoryUsedMb = (totalMb - availMb).coerceAtLeast(0),
            storageTotalMb = storage?.let { (it.totalBytes / 1024 / 1024).toInt() },
            storageFreeMb = storage?.let { (it.availableBytes / 1024 / 1024).toInt() },
            cpuCores = Runtime.getRuntime().availableProcessors()
        )
    }

    /**
     * The release out of a kernel string: `6.6.98-android15-8-pd6ff1cd-abogkiS938USQSCCZF9-4k` is
     * a build fingerprint, and `6.6.98` is the part somebody reads. Anything that does not start
     * with a number is handed back whole rather than mangled into an empty string.
     */
    private fun kernelRelease(build: String): String =
        Regex("""^\d+(\.\d+)+""").find(build)?.value ?: build

    // ---- how warm, and how busy ---------------------------------------------------

    /** One thermal sensor: what it watches over, and how hot it reads. */
    data class Zone(val name: String, val temperatureC: Double)

    /**
     * The kernel's thermal sensors, hottest first.
     *
     * The kernel puts every one of them in `/sys/class/thermal/thermal_zoneN`: the sensor's own
     * name in `type` and its reading in `temp`, in thousandths of a degree. Not every entry is a
     * temperature, though - a radio sensor with nothing attached reports the -273 sentinel, and
     * the power-supply `bcl` entries are trip levels, which read 0 - so only readings above zero
     * are kept. [total] is how many zones the kernel listed, kept so the screen can say how many
     * were left off.
     */
    data class Thermal(val zones: List<Zone>, val total: Int) {
        val present: Boolean get() = zones.isNotEmpty()
    }

    /**
     * Read the zones with two `cat`s rather than a loop, and pair them up by glob order.
     *
     * One `cat` prints every sensor name, one a line, in the same order the second prints every
     * reading, so the two lists line up. Shelling a `cat` per zone instead is a process per file -
     * a hundred and thirty of them on this device - for a single screen.
     */
    private const val THERMAL_COMMAND =
        "cat /sys/class/thermal/thermal_zone*/type; echo ---; cat /sys/class/thermal/thermal_zone*/temp"

    fun thermal(dumpText: String? = null): Thermal {
        val out = dumpText ?: runShellCommand(THERMAL_COMMAND)
            ?: return Thermal(emptyList(), 0)

        val lines = out.lines()
        val split = lines.indexOf("---")
        if (split < 0) return Thermal(emptyList(), 0)

        val names = lines.subList(0, split)
        val temps = lines.subList(split + 1, lines.size)
        val count = minOf(names.size, temps.size)

        val zones = (0 until count)
            .mapNotNull { i ->
                val celsius = temps[i].trim().toIntOrNull()?.div(1000.0) ?: return@mapNotNull null
                if (celsius <= 0.0) null else Zone(names[i].trim(), celsius)
            }
            .sortedByDescending { it.temperatureC }

        return Thermal(zones, count)
    }

    /** One process's share of the CPU over the dump's window. */
    data class Process(val name: String, val percent: Double)

    /**
     * The kernel's accounting: the load average, and which processes were running.
     *
     * `dumpsys cpuinfo` is the shell's view of it - the app cannot read it itself - and it is a
     * snapshot rather than an instantaneous one: each figure is the process's share over the
     * interval named at the top of the dump. The names are the kernel's, so some of what is busiest
     * is a kernel worker rather than an app, and the list says so rather than pretending otherwise.
     */
    data class Cpu(val load: List<Double>, val processes: List<Process>) {
        val present: Boolean get() = processes.isNotEmpty() || load.isNotEmpty()
    }

    fun cpu(dumpText: String? = null): Cpu {
        val dump = dumpText ?: runShellCommand("dumpsys cpuinfo") ?: return Cpu(emptyList(), emptyList())

        val load = dump.lineSequence()
            .firstOrNull { it.startsWith("Load:") }
            ?.substringAfter(':')
            ?.split('/')
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?: emptyList()

        val processes = mutableListOf<Process>()
        for (line in dump.lineSequence()) {
            // The window ends at the total, and the lines past it belong to the next one.
            if (line.contains("TOTAL")) break
            val match = CPU_PROCESS.find(line) ?: continue
            val percent = match.groupValues[1].toDoubleOrNull() ?: continue
            if (percent <= 0.0) continue
            processes += Process(match.groupValues[2], percent)
        }

        // Ordered here rather than trusted from the dump: the kernel sorts its own list, but
        // the list is the point of the section, and a screen that reads 10%, 3%, 0.1%, 2% is
        // read as broken however the dump wrote it.
        return Cpu(load, processes.sortedByDescending { it.percent })
    }

    // "  19% 4762/com.android.systemui: 15% user + 3.5% kernel / ...", and the same line with a
    // leading + when a process is new since the last dump. The name is lazy so that a name with a
    // colon in it - crtc_commit:203 - is taken whole rather than cut at the first one.
    private val CPU_PROCESS = Regex("""^\s*\+?([0-9.]+)%\s+\d+/(.+?):\s""")

    /** Load averages as "1.51 / 0.51 / 0.10", the one, five and fifteen minute figures. */
    fun loadAverage(load: List<Double>): String =
        if (load.size < 3) "—" else load.take(3).joinToString(" / ") {
            String.format(Locale.US, "%.2f", it)
        }

    /** Uptime as "3 d 4 h 12 m", which is how somebody reads it. */
    fun uptime(millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / (60 * 24)
        val hours = minutes % (60 * 24) / 60
        val mins = minutes % 60
        return buildString {
            if (days > 0) append("$days d ")
            if (days > 0 || hours > 0) append("$hours h ")
            append("$mins m")
        }
    }

    /** Megabytes as gigabytes, for the figures that read better that way. */
    fun gigabytes(mb: Int?): String =
        if (mb == null) "—" else String.format(Locale.US, "%.1f GB", mb / 1024.0)

    /** How full something is, as a percentage, or null when the whole is unknown. */
    fun percent(part: Int?, whole: Int?): Int? {
        if (part == null || whole == null || whole <= 0) return null
        return ((part.toLong() * 100) / whole).toInt().coerceIn(0, 100)
    }

    /** Volts from millivolts. */
    fun volts(mv: Int?): String =
        if (mv == null) "—" else String.format(Locale.US, "%.3f V", mv / 1000.0)

    /**
     * Milliamps from microamps, with the sign kept: negative is discharging.
     *
     * One decimal, because the readings that matter are small ones - an idle phone on the charger
     * draws a milliamp or three, and rounding those to whole numbers turned them into zero.
     */
    fun amps(ua: Int?): String =
        if (ua == null) "—" else String.format(Locale.US, "%.1f mA", ua / 1000.0)

    /** Degrees, one decimal. */
    fun celsius(t: Double?): String =
        if (t == null) "—" else String.format(Locale.US, "%.1f °C", t)

    /**
     * A thermal zone's name as a label: the kernel writes them lowercase and hyphenated, and
     * `gpuss-0` reads better as `GPUSS-0`.
     */
    fun thermalLabel(name: String): String = name.uppercase(Locale.US)

    /** A process's share of the CPU, one decimal. */
    fun percentText(percent: Double): String =
        String.format(Locale.US, "%.1f%%", percent)

    /** The battery's status, as words. */
    fun statusText(status: Int?): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "—"
    }

    /** The platform's health flag, which is a verdict rather than a number. */
    fun healthText(health: Int?): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> "—"
    }

    /** Where the charge is coming from, or none. */
    fun plugText(plugged: Int?): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        else -> "Not plugged in"
    }

    // ---- and Shizuku itself -------------------------------------------------------

    /**
     * The app's own state, which is the part no battery app has: what the manager is doing, how
     * its server is running and whether the hiding lists are holding anything.
     */
    data class ShizukuState(
        val running: Boolean,
        val uid: Int?,
        val version: Int?,
        val startMethod: String,
        val transport: String,
        val watchdog: Boolean,
        val hiddenFor: String?,
        val heldSignals: List<String>
    )

    fun shizuku(): ShizukuState {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val version = runCatching { Shizuku.getVersion() }.getOrNull()

        return ShizukuState(
            running = running,
            uid = if (running) runCatching { Shizuku.getUid() }.getOrNull() else null,
            version = version,
            startMethod = when (ShizukuSettings.getRunningStartMethod()) {
                ShizukuSettings.StartMethod.WIRELESS -> "Wireless debugging"
                // A method of its own and not a variant of the one above: it brings up a
                // local-only hotspot rather than using a network there is one of.
                ShizukuSettings.StartMethod.WIRELESS_NO_NETWORK ->
                    "Wireless debugging, no network"

                ShizukuSettings.StartMethod.USB -> "USB debugging"
                ShizukuSettings.StartMethod.SYSTEM -> "System"
                ShizukuSettings.StartMethod.ROOT -> "Root"
                else -> "Not started by this app"
            },
            transport = when (ShizukuSettings.getLastAdbTransport()) {
                ShizukuSettings.ADB_TRANSPORT_TCP -> "ADB port"
                ShizukuSettings.ADB_TRANSPORT_TLS -> "Wireless debugging"
                else -> "—"
            },
            watchdog = ShizukuSettings.getWatchdog(),
            hiddenFor = Hiding.foregroundPackage(),
            heldSignals = Signal.entries
                .filter { Hiding.isSignalEnabled(it) && Hiding.appsFor(it).isNotEmpty() }
                .map { it.name }
        )
    }
}
