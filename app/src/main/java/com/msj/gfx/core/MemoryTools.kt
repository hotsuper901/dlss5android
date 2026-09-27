package com.msj.gfx.core

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock

/**
 * What we can honestly do about memory, and what every "booster" app lies about.
 *
 * A normal app has zero authority over another app's process, so anything that
 * claims to kill WhatsApp for you is using an Accessibility hack or a
 * device-admin prompt. What is real, and what this does:
 *   - drop our own caches and request a collection
 *   - ask the platform to reclaim, via the trim hint from a foreground service
 *   - detect the foreground app so we boost at the right moment
 *   - surface actual device-level free RAM, which is the number that matters
 */
object MemoryTools {

    data class TrimResult(
        val heapFreedMb: Int,
        val deviceFreeBeforeMb: Int,
        val deviceFreeAfterMb: Int,
        val reclaimedMb: Int
    ) {
        val deviceDeltaMb: Int get() = (deviceFreeAfterMb - deviceFreeBeforeMb)
        val note: String
            get() = when {
                heapFreedMb == 0 && deviceDeltaMb <= 4 ->
                    "Heap was already clean - ${deviceFreeAfterMb} MB free device-wide"
                deviceDeltaMb <= 4 ->
                    "Freed $heapFreedMb MB of our cache - $deviceFreeAfterMb MB free device-wide"
                else ->
                    "Reclaimed ~$deviceDeltaMb MB - $deviceFreeAfterMb MB free device-wide"
            }
    }

    /**
     * Reclaim our own heap and nudge the platform.
     *
     * Note what is NOT here: Process.sendSignal(myPid(), SIGUSR1). SIGUSR1's
     * default disposition is terminate and ART installs no handler for it, so
     * that call reliably killed the app the moment the user pressed the button.
     * There is no supported way to make another app release its memory, and
     * asking Android nicely is the whole of what actually works.
     */
    fun trim(): TrimResult {
        val beforeFree = freeRamMb()

        // Drop anything we are holding onto before asking for a collection.
        Runtime.getRuntime().gc()
        System.runFinalization()

        // TRIM_MEMORY_RUNNING_CRITICAL tells the platform we are under pressure.
        // Only an Application implements ComponentCallbacks2 - casting a plain
        // Context to it always returns null, which is why this used to do
        // nothing at all.
        runCatching {
            (Ctx.get() as? Application)?.onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
        }

        // Give the collector a moment, then measure what actually changed.
        SystemClock.sleep(120)
        val afterFree = freeRamMb()

        val rt = Runtime.getRuntime()
        val heapNow = ((rt.totalMemory() - rt.freeMemory()) / 1_048_576L).toInt()
        val heapMax = (rt.maxMemory() / 1_048_576L).toInt()

        return TrimResult(
            heapFreedMb = if (heapMax > 0) (heapMax - heapNow).coerceAtLeast(0) else 0,
            deviceFreeBeforeMb = beforeFree,
            deviceFreeAfterMb = afterFree,
            reclaimedMb = (afterFree - beforeFree).coerceAtLeast(0)
        )
    }

    /** Device-wide available RAM. This is the number the games actually care about. */
    fun freeRamMb(): Int = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.availMem / 1_048_576L).toInt()
    }.getOrDefault(0)

    fun totalRamMb(): Int = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (mi.totalMem / 1_048_576L).toInt()
    }.getOrDefault(0)

    /**
     * Since Android 5.1 this returns only our own process for a normal app, so
     * it is a diagnostic, never a detector.
     */
    fun ownVisibleProcesses(): List<String> = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        (am.runningAppProcesses ?: emptyList()).mapNotNull { it.processName }
    }.getOrDefault(emptyList())

    fun isLowMemory(): Boolean = runCatching {
        val am = Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.lowMemory
    }.getOrDefault(false)

    fun ramPressurePct(): Int {
        val total = totalRamMb()
        if (total <= 0) return 0
        return (((total - freeRamMb()).toFloat() / total) * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * Foreground app, via UsageStats.
     *
     * This is the only mechanism that actually works post-5.1. It needs the
     * user to grant Usage access once in Settings, which the app asks for.
     */
    fun foregroundPackage(): String? {
        if (!hasUsageAccess()) return null
        return runCatching {
            val usm = Ctx.get().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5_000L, now)
            val event = UsageEvents.Event()
            var best: Pair<String, Long>? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                // MOVE_TO_FOREGROUND is the signal we want; a foreground service
                // of our own would otherwise win the race.
                val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.WINDOW_IN_FOCUS
                if (isForeground && pkg != Ctx.get().packageName) {
                    val ts = event.timeStamp
                    val prev = best
                    if (prev == null || ts > prev.second) best = pkg to ts
                }
            }
            best?.first
        }.getOrNull()
    }

    /** The game currently on screen, or null. Null is normal and not an error. */
    fun foregroundGame(): GameCatalog.Game? {
        val pkg = foregroundPackage() ?: return null
        return GameCatalog.match(pkg)
    }

    /**
     * Whether a supported game is running, foreground or not. Falls back to
     * reading our own /proc entry only to confirm we are alive - it cannot see
     * other apps, so it is never the deciding signal.
     */
    fun isAnyGameRunning(): Boolean {
        // Usage access not granted means we genuinely cannot know. Returning
        // false here rather than faking a result is deliberate: the caller
        // surfaces UNKNOWN_NO_PERMISSION instead of silently reporting idle.
        return foregroundGame() != null
    }

    fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return runCatching {
            val usm = Ctx.get().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000L, now)
            val event = UsageEvents.Event()
            var found = false
            while (!found && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == Ctx.get().packageName) found = true
            }
            found
        }.getOrDefault(false)
    }

    /** Perf hint for the game's own in-engine quality slider. */
    fun recommendQuality(cpuLoad: Int, ramPct: Int, tempC: Float?): String = when {
        tempC != null && tempC >= 44f -> "SMOOTH - phone is throttling, quality will not help"
        ramPct >= 85 -> "SMOOTH - not enough free RAM to hold high-res textures"
        cpuLoad >= 70 -> "SMOOTH - CPU already saturated"
        cpuLoad >= 45 -> "BALANCED"
        else -> "HD / ULTRA - you have headroom"
    }

    suspend fun cooldown(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) kotlinx.coroutines.delay(120)
    }

    private const val TRIM_MEMORY_RUNNING_CRITICAL = 15
}
