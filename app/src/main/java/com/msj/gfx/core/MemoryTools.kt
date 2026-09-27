package com.msj.gfx.core

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * What we can honestly do about memory, and what every "booster" app lies about.
 *
 * A normal app has zero authority over another app's process, so anything that
 * claims to kill WhatsApp for you is using an Accessibility hack or a
 * device-admin prompt. What is real, and what this does:
 *   - drop our own caches and ask for a collection
 *   - ask the platform to reclaim via the trim hint
 *   - detect the foreground app so we boost at the right moment
 *   - surface device-wide free RAM, the number that actually matters
 *
 * EVERY public function here touches the binder or the filesystem. Every caller
 * must be off the main thread. Calling these from the UI thread is an ANR.
 */
object MemoryTools {

    data class TrimResult(
        val deviceFreeBeforeMb: Int,
        val deviceFreeAfterMb: Int
    ) {
        val reclaimedMb: Int get() = (deviceFreeAfterMb - deviceFreeBeforeMb)
        val note: String
            get() = when {
                reclaimedMb > 64 ->
                    "Reclaimed ~$reclaimedMb MB - $deviceFreeAfterMb MB free device-wide"
                reclaimedMb > 4 ->
                    "Freed $reclaimedMb MB - $deviceFreeAfterMb MB free device-wide"
                else ->
                    "Nothing to reclaim - $deviceFreeAfterMb MB already free"
            }
    }

    /**
     * Reclaim our own heap and nudge the platform. Must not run on the UI thread.
     *
     * Note what is NOT here, and why:
     *
     * Process.sendSignal(myPid(), SIGUSR1) - SIGUSR1's default disposition is
     * terminate and ART installs no handler for it, so that call killed the
     * app the moment anyone pressed BOOST. There is no supported way to make
     * another app release its memory.
     *
     * System.runFinalization() - blocks until the finalizer queue drains, which
     * on a loaded phone is long enough to trip an ANR, and buys essentially
     * nothing once gc() has run. Removed for that reason alone.
     */
    fun trim(): TrimResult {
        val before = freeRamMb()

        Runtime.getRuntime().gc()

        // Only an Application implements ComponentCallbacks2. Casting a plain
        // Context to it always yields null, which is why this used to be a
        // no-op that silently did nothing.
        runCatching {
            (Ctx.get() as? Application)?.onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
        }

        val after = freeRamMb()
        return TrimResult(deviceFreeBeforeMb = before, deviceFreeAfterMb = after)
    }

    /** Device-wide available RAM. What the games actually allocate from. */
    fun freeRamMb(): Int = runCatching {
        memoryInfo().availMem / 1_048_576L
    }.getOrDefault(0).toInt()

    fun totalRamMb(): Int = runCatching {
        memoryInfo().totalMem / 1_048_576L
    }.getOrDefault(0).toInt()

    fun isLowMemory(): Boolean = runCatching { memoryInfo().lowMemory }.getOrDefault(false)

    fun ramPressurePct(): Int {
        val total = totalRamMb()
        if (total <= 0) return 0
        return (((total - freeRamMb()).toFloat() / total) * 100f).toInt().coerceIn(0, 100)
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also {
            (Ctx.get().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(it)
        }

    /**
     * Foreground app via UsageStats.
     *
     * This is the only mechanism that works on Android 5.1+. Since then a normal
     * app's getRunningAppProcesses() returns exactly one entry - our own - so
     * anything built on it could never have seen Free Fire.
     */
    fun foregroundPackage(): String? {
        if (!hasUsageAccess()) return null
        return runCatching {
            val usm = Ctx.get().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 4_000L, now)
            val event = UsageEvents.Event()
            var best: Pair<String, Long>? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                // Only the public constants. WINDOW_IN_FOCUS is @hide and does
                // not resolve against the public SDK, which breaks the build.
                val isForeground =
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                // Skip our own package: the booster is a foreground service and
                // would otherwise win the race against the game.
                if (isForeground && pkg != Ctx.get().packageName) {
                    val prev = best
                    if (prev == null || event.timeStamp > prev.second) best = pkg to event.timeStamp
                }
            }
            best?.first
        }.getOrNull()
    }

    /** The supported game on screen, or null. Null is normal, not an error. */
    fun foregroundGame(): GameCatalog.Game? {
        val pkg = foregroundPackage() ?: return null
        return GameCatalog.match(pkg)
    }

    fun isAnyGameRunning(): Boolean = foregroundGame() != null

    /**
     * Whether Usage access has been granted.
     *
     * Probed by looking for one of our own events in the last 60s. This is a
     * binder call plus a cursor walk - do not call it from the UI thread.
     */
    fun hasUsageAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return runCatching {
            val usm = Ctx.get().getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 120_000L, now)
            val event = UsageEvents.Event()
            var found = false
            while (!found && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == Ctx.get().packageName) found = true
            }
            found
        }.getOrDefault(false)
    }

    /**
     * Quality recommendation from the two signals we can actually trust.
     * No CPU input - a sandboxed app cannot measure device-wide CPU reliably,
     * and a number that reads 0 while the phone is at 100% is worse than none.
     */
    /**
     * Suspends the caller. A suspending cooldown, not SystemClock.sleep, so it
     * never blocks a thread - and never blocks the UI thread, which is what
     * made the old BOOST path trip an ANR.
     */
    suspend fun cooldown(ms: Long) = kotlinx.coroutines.delay(ms)

    fun recommendQuality(ramPct: Int, tempC: Float?, charging: Boolean): String = when {
        tempC != null && tempC >= 44f ->
            "SMOOTH - the SoC is throttling, quality will not help"
        ramPct >= 90 ->
            "SMOOTH - no free RAM to hold high-res textures"
        tempC != null && tempC >= 39f && !charging ->
            "BALANCED - running hot and not on power"
        ramPct >= 75 ->
            "BALANCED - memory is getting tight"
        else ->
            "HD / ULTRA - you have thermal and memory headroom"
    }

    private const val TRIM_MEMORY_RUNNING_CRITICAL = 15
}
