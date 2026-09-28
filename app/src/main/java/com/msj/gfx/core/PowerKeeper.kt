package com.msj.gfx.core

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The keep-alive layer: wakelocks, the battery-optimisation exemption, and the
 * manufacturer screens that sit in front of both.
 *
 * Two different mechanisms get confused with each other, so the split matters:
 *
 *   - [setCpuAwake] and [setScreenAwake] are *session* locks. They are held
 *     only while a tracked game is actually in the foreground and released the
 *     moment it leaves. Holding a wakelock around the clock is how these apps
 *     earn a battery complaint.
 *   - [isBatteryExempt] / [requestExemption] are the *standing* grant. Without
 *     it Android's Doze ignores app wakelocks entirely and the cached-app
 *     freezer can park the booster between matches, which is exactly the
 *     "it keeps stopping" report. So the exemption is what makes the session
 *     locks meaningful, and the session locks are what make the exemption
 *     worth having.
 *
 * [openOemAutostart] exists because on Xiaomi, Huawei, Oppo, Vivo, OnePlus and
 * Samsung the AOSP exemption is only half the story - each ships a second,
 * manufacturer-owned battery screen that can still kill the app. This opens
 * that screen directly on the builds that expose one.
 */
object PowerKeeper {

    private const val CPU_TAG = "msj_gfx_in_game"
    private const val SCREEN_TAG = "msj_gfx_screen"

    /** Guards acquire/release against the watcher thread and the service thread. */
    private val gate = Any()

    @Volatile private var cpuLock: PowerManager.WakeLock? = null
    @Volatile private var screenLock: PowerManager.WakeLock? = null

    /** Mirrors whether the in-session screen lock is held; cheap to read from any thread. */
    @Volatile private var screenAwake = false

    private fun power(): PowerManager? = runCatching {
        Ctx.get().getSystemService(Context.POWER_SERVICE) as PowerManager
    }.getOrNull()

    /**
     * Hold the CPU awake while a game session is on screen.
     *
     * This is the lock that keeps the booster's own process schedulable: the
     * UsageStats poll, the trim timers and the HUD tick all stop when the
     * cached-app freezer parks us. It cannot and does not touch the game's
     * process - a wakelock is a request to the power manager, not to another
     * app.
     *
     * Deliberately no timeout. The session bracket here is explicit and
     * symmetrical - acquired in applyEnhancePolicy, released in
     * releaseEnhancePolicy, in onDestroy, and on process death by the kernel -
     * and a timeout would silently drop protection in the middle of a long
     * match, which is the failure the feature exists to prevent.
     */
    @SuppressLint("WakelockTimeout")
    fun setCpuAwake(on: Boolean) {
        synchronized(gate) {
            if (on) {
                cpuLock = acquireLocked(cpuLock, PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG)
            } else {
                releaseLocked(cpuLock)
            }
        }
    }

    /**
     * Hold the display lit while a game session is on screen.
     *
     * The platform-preferred mechanism is FLAG_KEEP_SCREEN_ON on a visible
     * window, and the HUD window does carry it when the HUD is enabled - see
     * GameOverlayService. This lock is the path that works when no window of
     * ours is visible at all, which is the normal case for a user who never
     * turned the HUD on.
     *
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated in favour of the window flag, but
     * the flag needs a window; the lock is the only unrooted way to keep a
     * display on without one, and it is still honoured by the power manager.
     * Acquired and released with the same session bracket as the CPU lock.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    fun setScreenAwake(on: Boolean) {
        synchronized(gate) {
            if (on) {
                screenLock = acquireLocked(screenLock, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, SCREEN_TAG)
                // Reported from what was actually acquired: if the lock could
                // not be built there is no session to mirror into the HUD.
                screenAwake = screenLock != null
            } else {
                releaseLocked(screenLock)
                screenAwake = false
            }
        }
    }

    /**
     * True while the in-game screen session is active.
     *
     * Exposed so the HUD window can carry FLAG_KEEP_SCREEN_ON only during a
     * real match. A plain toggle check would hold the screen on for as long as
     * the HUD is up - including on the launcher, hours after the game closed -
     * which is the kind of battery drain the session bracket exists to avoid.
     */
    fun isScreenSessionActive(): Boolean = screenAwake

    /** Drop both session locks. Called on game exit, service teardown, and permission loss. */
    fun releaseAll() {
        setCpuAwake(false)
        setScreenAwake(false)
    }

    /**
     * True once the user has exempted this app from battery optimisation.
     *
     * On API 23+ this is the real, queryable state - no guessing and no
     * pretending. On a few OEM builds it can report false even when the app is
     * whitelisted elsewhere, which the UI phrasing is written to tolerate.
     */
    fun isBatteryExempt(): Boolean = runCatching {
        power()?.isIgnoringBatteryOptimizations(Ctx.get().packageName) ?: false
    }.getOrDefault(false)

    /**
     * Ask the user to exempt this app, through the system's own dialog.
     *
     * Falls back to the optimisation list when the direct dialog has been
     * stripped, which is common on Chinese OEM builds. Nothing is granted
     * without the user tapping through a system screen either way - there is
     * no silent path to this grant, and there should not be.
     */
    fun requestExemption() {
        val ctx = Ctx.get()
        val direct = runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (direct) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Open the OEM's own "protect the app" screen where this build exposes one.
     *
     * The component names below are the well-known ones from the community
     * "don't kill my app" effort. Every lookup is best-effort: a component that
     * is absent or not exported simply throws ActivityNotFoundException and the
     * next candidate is tried. Returns false when none resolve, so the UI can
     * say so instead of opening a blank page.
     */
    fun openOemAutostart(): Boolean {
        val ctx = Ctx.get()
        for (candidate in OEM_AUTOSTART) {
            val opened = runCatching {
                ctx.startActivity(
                    Intent().setComponent(candidate)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (opened) return true
        }
        return false
    }

    /**
     * One wakelock allocation per process per level, reused across sessions.
     *
     * setReferenceCounted(false) is what makes acquire/release idempotent: a
     * second acquire on a held lock is a no-op and a single release always
     * clears it, so a duplicated session bracket cannot leave the lock stuck on.
     */
    private fun acquireLocked(
        existing: PowerManager.WakeLock?,
        level: Int,
        tag: String
    ): PowerManager.WakeLock? {
        if (existing?.isHeld == true) return existing
        val pm = power() ?: return existing
        val wl = existing
            ?: runCatching { pm.newWakeLock(level, tag) }.getOrNull()
                ?.also { it.setReferenceCounted(false) }
            ?: return null
        runCatching { wl.acquire() }
        return wl
    }

    private fun releaseLocked(existing: PowerManager.WakeLock?) {
        if (existing?.isHeld == true) runCatching { existing.release() }
    }

    /**
     * Manufacturer autostart / protected-apps screens, most specific first.
     * Ordering matters where a vendor renamed the activity between OS versions.
     */
    private val OEM_AUTOSTART: List<ComponentName> = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")
    )
}
