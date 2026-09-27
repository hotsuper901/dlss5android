package com.msj.gfx.core

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What we measure, and what we deliberately do not.
 *
 * CPU load used to be read here by differencing /proc/stat. It was removed and
 * it is not coming back: three callers sampled at three different rates
 * (1Hz dashboard, 500ms overlay, 4s service) all advancing one shared
 * differential baseline, so every caller consumed the delta and the others read
 * zero. Even done properly, a sandboxed app cannot get a trustworthy
 * device-wide CPU figure, and shipping a number that reads 0 while the phone is
 * clearly at 100% is worse than shipping nothing.
 *
 * What is left is the two signals that actually predict stutter in a match:
 * thermal state and available memory.
 */
data class PerfSnapshot(
    val cpuTempC: Float? = null,      // thermal_zone average, null if the ROM hides it
    val batteryTempC: Float? = null,  // battery NTC, more accurate when present
    val freeRamMb: Int = 0,           // device-wide available
    val totalRamMb: Int = 0,
    val deviceRamPct: Int = 0,        // device-wide usage
    val lowMemory: Boolean = false,   // ActivityManager's own lowMemory flag
    val charging: Boolean = false,
    val throttling: Boolean = false,  // 42C+ == the 40fps killer
    val soc: String = "UNKNOWN"
) {
    /** The number to show as the headline figure. */
    val hottestC: Float? get() = maxOf(batteryTempC ?: 0f, cpuTempC ?: 0f)
        .takeIf { (batteryTempC ?: cpuTempC) != null }
}

object PerfMonitor {

    private var lastBatteryTemp: Float? = null

    suspend fun sample(): PerfSnapshot = withContext(Dispatchers.Default) {
        val batt = battery()
        val zoned = cpuTempZone()
        val temp = batt.first ?: zoned

        PerfSnapshot(
            cpuTempC = zoned,
            batteryTempC = batt.first,
            freeRamMb = MemoryTools.freeRamMb(),
            totalRamMb = MemoryTools.totalRamMb(),
            deviceRamPct = MemoryTools.ramPressurePct(),
            lowMemory = MemoryTools.isLowMemory(),
            charging = batt.second,
            throttling = temp != null && temp >= THROTTLE_C,
            soc = socName()
        )
    }

    /**
     * Non-suspend variant. Callers MUST be off the main thread: this touches
     * /sys and issues a binder call, and doing it on the UI thread is an ANR.
     */
    fun sampleBlocking(): PerfSnapshot {
        val batt = battery()
        val zoned = cpuTempZone()
        val temp = batt.first ?: zoned
        return PerfSnapshot(
            cpuTempC = zoned,
            batteryTempC = batt.first,
            freeRamMb = MemoryTools.freeRamMb(),
            totalRamMb = MemoryTools.totalRamMb(),
            deviceRamPct = MemoryTools.ramPressurePct(),
            lowMemory = MemoryTools.isLowMemory(),
            charging = batt.second,
            throttling = temp != null && temp >= THROTTLE_C,
            soc = socName()
        )
    }

    /** Average of every CPU/SOC thermal zone. Null on ROMs that hide them. */
    fun cpuTempZone(): Float? = runCatching {
        val zones = File("/sys/class/thermal").listFiles() ?: return@runCatching null
        zones.asSequence()
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { z ->
                val type = runCatching { File(z, "type").readText() }.getOrDefault("")
                val temp = runCatching {
                    File(z, "temp").readText().trim().toFloat() / 1000f
                }.getOrNull()
                if (temp == null) null else temp to type
            }
            .filter { (_, type) ->
                type.contains("cpu", true) || type.contains("soc", true) ||
                    type.contains("ap", true) || type.contains("big", true)
            }
            .map { it.first }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
    }.getOrNull()

    private fun battery(): Pair<Float?, Boolean> = runCatching {
        // Sticky broadcast rather than BatteryManager.getIntProperty - identical
        // on every API level we support, and no version gate.
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

    private fun socName(): String = runCatching {
        // Build.SOC_MANUFACTURER only exists from API 31; touching it below that
        // is a NoSuchFieldError, so gate it and fall back to HARDWARE.
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.ifEmpty { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        if (raw.isBlank()) "UNKNOWN"
        else raw.uppercase().take(9)
    }.getOrDefault("UNKNOWN")

    const val THROTTLE_C = 42f
}
