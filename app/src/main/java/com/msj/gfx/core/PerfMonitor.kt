package com.msj.gfx.core

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PerfSnapshot(
    val cpuLoad: Int = 0,             // 0..100 across all cores
    val cpuTempC: Float? = null,      // thermal_zone cpu average, null if hidden
    val batteryTempC: Float? = null,  // BatteryManager, most reliable on modern devices
    val ramUsedMb: Int = 0,
    val ramTotalMb: Int = 0,
    val ramPct: Int = 0,
    val fps: Int = 0,                 // only meaningful while a game is foregrounded
    val frameMs: Float = 0f,          // rolling frametime average
    val charging: Boolean = false,
    val throttling: Boolean = false,  // 42C+ and climbing == the 40fps killer
    val soc: String = "UNKNOWN",
    val foregroundPkg: String? = null
)

object PerfMonitor {

    private var lastCpuTotal = 0L
    private var lastIdleTotal = 0L
    private var primed = false

    /** Smoothing so the bar doesn't strobe between 20 and 90. */
    private var smoothedCpu = 0.0
    private var lastBatteryTemp: Float? = null

    suspend fun sample(): PerfSnapshot = withContext(Dispatchers.Default) {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val total = (rt.maxMemory() / 1_048_576L)
        val pct = if (total > 0) ((used * 100) / total).toInt().coerceIn(0, 100) else 0

        val load = sampleCpuLoad()
        val batt = battery()
        val battTemp = batt.first
        val charging = batt.second
        val zoned = cpuTempZone()

        // Battery NTC is more accurate when present; the zone is a decent fallback.
        val temp = battTemp ?: zoned
        val throttling = temp != null && temp >= 42f

        PerfSnapshot(
            cpuLoad = load,
            cpuTempC = zoned,
            batteryTempC = battTemp,
            ramUsedMb = used.toInt(),
            ramTotalMb = total.toInt(),
            ramPct = pct,
            charging = charging,
            throttling = throttling,
            soc = socName()
        )
    }

    /**
     * Differential read of /proc/stat. This is the correct way - the naive
     * "read jiffies, divide by elapsed" approach every tutorial shows returns
     * garbage because the counters are cumulative since boot.
     */
    private fun sampleCpuLoad(): Int {
        val txt = runCatching { File("/proc/stat").readText() }.getOrNull() ?: return 0
        val line = txt.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return 0
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return 0

        var total = 0L
        for (i in 1 until parts.size) total += parts[i].toLongOrNull() ?: 0L
        val idle = (parts[4].toLongOrNull() ?: 0L) + (parts[5].toLongOrNull() ?: 0L)

        if (!primed) {
            lastCpuTotal = total
            lastIdleTotal = idle
            primed = true
            return 0
        }

        val dTotal = total - lastCpuTotal
        val dIdle = idle - lastIdleTotal
        lastCpuTotal = total
        lastIdleTotal = idle

        if (dTotal <= 0L) return 0
        val raw = (((dTotal - dIdle).toDouble() / dTotal.toDouble()) * 100.0)
        smoothedCpu = smoothedCpu * 0.7 + raw * 0.3
        return smoothedCpu.roundToInt().coerceIn(0, 100)
    }

    fun cpuTempZone(): Float? = runCatching {
        val zones = File("/sys/class/thermal").listFiles() ?: return@runCatching null
        zones.asSequence()
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { z ->
                val type = runCatching { File(z, "type").readText() }.getOrDefault("")
                val temp = runCatching { File(z, "temp").readText().trim().toFloat() / 1000f }.getOrNull()
                if (temp == null) null else temp to type
            }
            .filter { (_, type) ->
                type.contains("cpu", true) || type.contains("soc", true) ||
                    type.contains("ap", true) || type.contains("bigcore", true)
            }
            .map { it.first }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
    }.getOrNull()

    private fun battery(): Pair<Float?, Boolean> = runCatching {
        // Sticky broadcast rather than BatteryManager.getIntProperty: the sticky
        // intent works identically from API 21 up and needs no version gate.
        val intent = Ctx.get().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it in -400..2500 }
            ?.div(10f)
        lastBatteryTemp = t ?: lastBatteryTemp
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        lastBatteryTemp to charging
    }.getOrDefault(lastBatteryTemp to false)

    /** Trims the marketing string down to something that fits in a tile. */
    private fun socName(): String = runCatching {
        // Build.SOC_MANUFACTURER only exists from API 31. Touching it on Android
        // 7-11 is a NoSuchFieldError at runtime, so gate it and fall back.
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.ifEmpty { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        if (raw.isBlank()) "UNKNOWN"
        else raw.uppercase()
            .replace("SM", "S")
            .replace("MSM", "M")
            .replace("QP", "Q")
            .take(9)
    }.getOrDefault("UNKNOWN")

    private fun Double.roundToInt() = kotlin.math.round(this).toInt()

    /**
     * Non-suspend variant for the overlay's Handler loop, which cannot call
     * runBlocking on the main thread without a frame hitch.
     */
    fun sampleBlocking(): PerfSnapshot {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val total = (rt.maxMemory() / 1_048_576L)
        val load = sampleCpuLoad()
        val batt = battery()
        val zoned = cpuTempZone()
        val temp = batt.first ?: zoned
        return PerfSnapshot(
            cpuLoad = load,
            cpuTempC = zoned,
            batteryTempC = batt.first,
            ramUsedMb = used.toInt(),
            ramTotalMb = total.toInt(),
            ramPct = if (total > 0) ((used * 100) / total).toInt() else 0,
            charging = batt.second,
            throttling = (temp ?: 0f) >= 42f,
            soc = socName()
        )
    }

    /** Exposed for the overlay's frametime ring. */
    fun primeCpu() { primed = false }
}
