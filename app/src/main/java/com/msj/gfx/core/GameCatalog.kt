package com.msj.gfx.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Supported titles.
 *
 * Matched on the UsageStats package name, which is the launcher package, so
 * these are the real package names and not the mangled process names. Prefix
 * matching is kept as a fallback because some vendors ship regional builds
 * under a suffixed package.
 */
object GameCatalog {

    data class Game(
        val packageName: String,
        val label: String,
        val accent: Long,
        val minFreeRamMb: Int,
        val levers: List<String>
    )

    private fun g(pkg: String, label: String, accent: Long, minRam: Int, vararg levers: String) =
        Game(pkg, label, accent, minRam, levers.toList())

    val FREE_FIRE = g(
        "com.dts.freefireth", "Free Fire", 0xFFFF7A45, 1200,
        "Settings > Graphics > Smooth. Ultra caps at 30fps and costs you half the draw distance",
        "Camera > first-person scope set to 'Classic' to cut view-model overdraw",
        "Turn off Auto-shoot and the aim training popup so the frame budget goes to rendering",
        "Background apps are the real cause of stutter - 400MB of stale RAM is where it comes from"
    )

    val FREE_FIRE_MAX = g(
        "com.dts.freefiremax", "Free Fire MAX", 0xFFFFB020, 1600,
        "Graphics > Ultra, and set Anti-aliasing OFF - it costs 8-10fps on most Adreno and Mali",
        "Camera > 'Classic' scope",
        "Turn off bullet-helper visuals before a ranked match, they re-render every bullet",
        "MAX wants 1.5GB free just to hold texture residency, close everything else first"
    )

    val PUBG_MOBILE = g(
        "com.tencent.ig", "PUBG Mobile", 0xFF3DDC97, 1800,
        "Graphics > Smooth. On a mid-ranger Smooth beats Ultra every time",
        "Frame rate > 90fps only if the device holds it, otherwise it oscillates and stutters",
        "Anti-aliasing OFF and Shooting Mode OFF - it reprojects the frame buffer",
        "Raise the render scale in developer options BEFORE launching, not mid-match"
    )

    val MLBB = g(
        "com.moonton.mobilelegends", "Mobile Legends: Bang Bang", 0xFF3B82F6, 1200,
        "Graphics > High, and turn OFF 'High Frame Rate' unless your device holds 60",
        "Interface > set the HUD to minimal so less UI is composited every frame",
        "Turn off 'Show Battle Stats' and 'Show Rank' - extra text is extra draw calls",
        "OBB stays on internal storage. An OBB on a slow SD card is the single biggest stutter source"
    )

    val HONOR_OF_KINGS = g(
        "com.tencent.tmgp.sgame", "Honor of Kings", 0xFFF59E0B, 1400,
        "Graphics > High with 'Smooth' detail - the combination most devices actually sustain",
        "Turn off 'Auto Reconnect reminders' and the floating battle shortcuts",
        "Close the camera and mic permissions you do not use, they wake background services"
    )

    val COD_MOBILE = g(
        "com.activision.callofduty.shooter", "Call of Duty: Mobile", 0xFFEF4444, 1600,
        "Graphics > 'Ultra' with 'High' texture if your device is a 8-series, otherwise Medium",
        "Anti-aliasing OFF - on a mid GPU it trades more than it returns",
        "Shadow quality down one notch, it is the single most expensive setting in the game"
    )

    val CRITICAL_OPS = g(
        "ca.criticalops.thecriticalop", "Critical Ops", 0xFF14B8A6, 900,
        "Graphics > Ultra, this one is not demanding and holds 60 on midrange hardware",
        "Lower the FOV slider a touch - it is a direct multiplier on fragment cost"
    )

    val LIEN_QUAN = g(
        "com.garena.game.kgvn", "Garena Lien Quan Mobile", 0xFF10B981, 1200,
        "MOBA, so the frame budget goes to the lane you are pushing",
        "Lower the draw distance and particle effects - these are the usual stutter sources",
        "Close the background apps; a MOBA holds far less RAM than a battle royale"
    )

    val BGMI = g(
        "com.pubg.imobile", "BGMI", 0xFFF97316, 1800,
        "Graphics > Smooth. Ultra caps the frame rate on most devices anyway",
        "Set the camera to 'Classic' to cut view-model overdraw",
        "Turn off Gyroscope aim and auto-pickup if you do not use them"
    )

    val PUBG_LITE = g(
        "com.tencent.ig.lite", "PUBG Mobile Lite", 0xFF84CC16, 700,
        "This build already targets low-end devices, leave the defaults alone",
        "Lower the resolution scale before you lower quality - it costs less visually"
    )

    val NEW_STATE = g(
        "com.pubg.newstate", "New State Mobile", 0xFF0EA5E9, 1800,
        "Smooth plus reduced draw distance is the combination that holds frame rate",
        "Turn off motion blur in Settings > Graphics"
    )

    val ALL: List<Game> = listOf(
        FREE_FIRE, FREE_FIRE_MAX, PUBG_MOBILE, PUBG_LITE, BGMI, NEW_STATE,
        MLBB, HONOR_OF_KINGS, COD_MOBILE, CRITICAL_OPS, LIEN_QUAN
    )

    /** Regional and repackaged variants that report a different launcher package. */
    private val ALIASES: Map<String, Game> = buildMap {
        listOf(
            // Verified 2026-09 against the live Play Store listing. Free Fire is
            // com.dts.freefireth, NOT com.garena.game.kgvn - that Garena
            // package now belongs to Garena Lien Quan Mobile in Vietnam, which
            // is why detection silently failed for the actual game.
            "com.dts.freefireth" to FREE_FIRE,
            "com.dts.freefiremax" to FREE_FIRE_MAX,
            // Legacy/regional Garena identifiers, kept so older installs match.
            "com.garena.game.kgvn" to LIEN_QUAN,
            "com.garena.game.kid" to FREE_FIRE_MAX,
            "com.garena.game.IDungeon" to FREE_FIRE,
            "com.tencent.ig" to PUBG_MOBILE,
            "com.tencent.ig.lite" to PUBG_LITE,
            "com.pubg.imobile" to BGMI,
            "com.pubg.newstate" to NEW_STATE,
            "com.pubg.krmobile" to PUBG_MOBILE,
            "com.vng.pubgmobile" to PUBG_MOBILE,
            "com.rekoo.pubgm" to PUBG_MOBILE,
            "com.tencent.tmgp.pubgmhd" to PUBG_MOBILE,
            "com.moonton.mobilelegends" to MLBB,
            "com.moonton.mobilelegends.hk" to MLBB,
            "com.moonton.mobilelegends.tw" to MLBB,
            "com.moonton.mobilelegends.gb" to MLBB,
            "com.tencent.tmgp.sgame" to HONOR_OF_KINGS,
            "com.tencent.tmgp.km" to HONOR_OF_KINGS,
            "com.activision.callofduty.shooter" to COD_MOBILE
        ).forEach { (pkg, game) -> put(pkg, game) }
    }

    /** Prefix entries cover suffixed regional builds, e.g. com.moonton.mobilelegends.bh. */
    private val PREFIXES: List<Pair<String, Game>> = listOf(
        "com.dts.freefireth" to FREE_FIRE,
        "com.dts.freefiremax" to FREE_FIRE_MAX,
        "com.dts" to FREE_FIRE,
        "com.garena.game.kgvn" to LIEN_QUAN,
        "com.garena.game.kid" to FREE_FIRE_MAX,
        // Order matters: firstOrNull wins, so the specific packages have to
        // come before the broad com.pubg catch-all or they all collapse into
        // one title.
        "com.tencent.ig.lite" to PUBG_LITE,
        "com.pubg.imobile" to BGMI,
        "com.pubg.newstate" to NEW_STATE,
        "com.tencent.ig" to PUBG_MOBILE,
        "com.pubg" to PUBG_MOBILE,
        "com.vng.pubgmobile" to PUBG_MOBILE,
        "com.rekoo.pubgm" to PUBG_MOBILE,
        "com.tencent.tmgp.pubgm" to PUBG_MOBILE,
        "com.moonton.mobilelegends" to MLBB,
        "com.tencent.tmgp.sgame" to HONOR_OF_KINGS,
        "com.activision.callofduty.shooter" to COD_MOBILE,
        "ca.criticalops.thecriticalop" to CRITICAL_OPS
    )

    /**
     * Takes either a package name or a process name. Process names on modern
     * Android carry a ":suffix" (com.tencent.ig:unity) which is stripped first.
     */
    fun match(raw: String?): Game? {
        if (raw.isNullOrBlank()) return null
        val pkg = raw.substringBefore(':').trim()
        if (pkg.isEmpty()) return null
        ALIASES[pkg]?.let { return it }
        return PREFIXES.firstOrNull { pkg.startsWith(it.first) }?.second
    }

    /**
     * Every launchable app on the device, as (packageName, label).
     *
     * This is the ground truth. The ALL list above is a guess, and a guess is
     * exactly why an installed Free Fire can show as "not installed" - regional
     * and repackaged builds ship under package names nobody can enumerate in
     * advance. The manifest declares the MAIN/LAUNCHER intent query that makes
     * this list readable on API 30+.
     *
     * Binder call. Must be called off the main thread.
     */
    @Volatile private var launchableCache: List<Installed>? = null
    @Volatile private var launchableCachedAt = 0L

    /**
     * queryIntentActivities is a binder call and the detector polls it often,
     * so the result is cached for a few seconds and only invalidated by an
     * explicit rescan.
     */
    fun launchableApps(maxAgeMs: Long = 15_000L): List<Installed> {
        val c = launchableCache
        val age = System.currentTimeMillis() - launchableCachedAt
        if (c != null && age < maxAgeMs) return c
        return queryLaunchable().also {
            launchableCache = it
            launchableCachedAt = System.currentTimeMillis()
        }
    }

    fun invalidateLaunchableCache() {
        launchableCache = null
        launchableCachedAt = 0L
    }

    private fun queryLaunchable(): List<Installed> = runCatching {
        val pm = Ctx.get().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved.asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm).toString() }
                    .getOrDefault(pkg)
                Installed(pkg, label)
            }
            .distinctBy { it.packageName }
            .toList()
    }.getOrDefault(emptyList())

    data class Installed(val packageName: String, val label: String)

    /**
     * Supported titles that are actually present. Checks the launchable list
     * first and falls back to a direct lookup, because a title can be installed
     * without exposing a launcher activity (some OEM builds do this).
     */
    fun installed(): List<Game> {
        val launchable = launchableApps().map { it.packageName }.toSet()
        return ALL.filter { it.packageName in launchable || isInstalled(it.packageName) }
    }

    /**
     * Every launchable package that looks like a supported or unrecognised
     * game, so an unknown build can be identified by reading its real package
     * name off the diagnostics screen instead of guessing at it.
     */
    /**
     * Fragments that identify a game regardless of the exact package string.
     * Deliberately broad: the point is to recognise a build we have never seen
     * before, not to be precise. A false positive costs one stray "game
     * detected" line; a false negative is a detector that silently never works.
     */
    private val SHAPE = listOf(
        // "dts" is the real Free Fire namespace (com.dts.freefireth) and was
        // missing here, which is the second reason detection missed it.
        "garena", "freefire", "free fire", "free_fire", "kgvn", "kid",
        "com.dts", "dts.freefire",
        "moonton", "mobilelegends", "mobile legends", "mlbb", "mobile.legends",
        "tencent", "pubg", "krmobile", "rekoo", "pubgm", "tmgp.sgame",
        "activision", "callofduty", "call of duty", "criticalops",
        "supercell", "clash", "riot", "valorant", "mihoyo", "genshin",
        "honorofkings", "vng", "krafton", "ubisoft", "ea", "playrix"
    )

    fun looksLikeGame(packageName: String, label: String? = null): Boolean {
        val hay = (packageName + " " + (label ?: "")).lowercase()
        return SHAPE.any { hay.contains(it) }
    }

    /** Real launcher label for a package, or the package itself if unknown. */
    fun labelFor(pkg: String): String =
        launchableApps().firstOrNull { it.packageName == pkg }?.label ?: pkg

    fun gameLikePackages(): List<Installed> =
        launchableApps().filter { looksLikeGame(it.packageName, it.label) }

    fun isInstalled(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            Ctx.get().packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            Ctx.get().packageManager.getPackageInfo(pkg, 0)
        }
        true
    }.getOrDefault(false)

    /** Whether we can even attempt detection yet. */
    fun detectionReady(): Boolean = MemoryTools.hasUsageAccess()

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .setData(Uri.parse("package:${Ctx.get().packageName}"))

    @Suppress("unused")
    fun appContext(): Context = Ctx.get()
}
