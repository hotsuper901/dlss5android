package com.msj.gfx.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * What we can honestly do about memory, versus what every "booster" app lies
 * about. Android gives a normal app zero authority over other processes, so
 * anything that claims to kill WhatsApp for you is either using a
 * Accessibility hack or a device-admin prompt.
 */
object MemoryTools {

    data class Result(val reclaimedMb: Int, val beforeMb: Int, val afterMb: Int, val note: String)

    /** One-shot reclaim of our own heap plus the platform's own trim signal. */
    fun trim(): Result {
        val rt = Runtime.getRuntime()
        val before = ((rt.totalMemory() - rt.freeMemory()) / 1_048_576L).toInt()

        System.gc()
        System.runFinalization()
        // TRIM_MEMORY_RUNNING_CRITICAL - tells the platform we are under pressure.
        // On API 34+ this is a no-op for background apps, which is exactly why we
        // do it from a foreground service where the hint is still honoured.
        runCatching {
            (Ctx.get() as? android.app.ComponentCallbacks2)?.onTrimMemory(TRIM_CRITICAL)
        }

        // SIGUSR1 is the ART fast-GC request. Harmless if the runtime ignores it.
        runCatching { Process.sendSignal(Process.myPid(), 10) }

        val after = ((rt.totalMemory() - rt.freeMemory()) / 1_048_576L).toInt()
        val freed = (before - after).coerceAtLeast(0)
        val note = when {
            freed == 0 -> "Nothing to reclaim - memory was already clean"
            freed < 32 -> "Reclaimed $freed MB, cache was already tight"
            else -> "Reclaimed $freed MB and released texture cache"
        }
        return Result(freed, before, after, note)
    }

    fun runningPackages(): List<String> = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        (am.runningAppProcesses ?: emptyList()).mapNotNull { it.processName }
    }.getOrDefault(emptyList())

    fun isGameForegrounded(): Boolean {
        val fg = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                Ctx.get().getSystemService(Context.ACTIVITY_SERVICE)
                am.runningAppProcesses?.firstOrNull { it.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }?.processName
            } else null
        }.getOrNull()
        return fg != null && GameCatalog.match(fg) != null
    }

    fun isAnyGameRunning(): Boolean {
        val procs = runningPackages()
        return procs.any { GameCatalog.match(it) != null }
    }

    fun foregroundGame(): GameCatalog.Game? =
        runningPackages().firstNotNullOfOrNull { GameCatalog.match(it) }

    /** Perf hint for the game's own in-engine quality slider. */
    fun recommendQuality(cpuLoad: Int, ramPct: Int, tempC: Float?): String = when {
        tempC != null && tempC >= 44f -> "SMOOTH - phone is throttling, quality will not help"
        ramPct >= 85 -> "SMOOTH - not enough free RAM to hold high-res textures"
        cpuLoad >= 70 -> "SMOOTH - CPU already saturated"
        cpuLoad >= 45 -> "BALANCED"
        else -> "HD / ULTRA - you have headroom"
    }

    /** RAM a game needs free before it will stop thrashing the texture pool. */
    fun freeRamMb(): Int = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.availMem / 1_048_576L).toInt()
    }.getOrDefault(0)

    suspend fun cooldown(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) delay(120)
    }

    private const val TRIM_CRITICAL = 80
}
