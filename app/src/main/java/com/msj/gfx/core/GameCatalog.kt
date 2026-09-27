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
        "com.garena.game.kgvn", "Free Fire", 0xFFFF7A45, 1200,
        "Settings > Graphics > Smooth. Ultra caps at 30fps and costs you half the draw distance",
        "Camera > first-person scope set to 'Classic' to cut view-model overdraw",
        "Turn off Auto-shoot and the aim training popup so the frame budget goes to rendering",
        "Background apps are the real cause of stutter - 400MB of stale RAM is where it comes from"
    )

    val FREE_FIRE_MAX = g(
        "com.garena.game.kid", "Free Fire MAX", 0xFFFFB020, 1600,
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

    val ALL: List<Game> = listOf(
        FREE_FIRE, FREE_FIRE_MAX, PUBG_MOBILE, MLBB, HONOR_OF_KINGS, COD_MOBILE, CRITICAL_OPS
    )

    /** Regional and repackaged variants that report a different launcher package. */
    private val ALIASES: Map<String, Game> = buildMap {
        listOf(
            "com.garena.game.kgvn" to FREE_FIRE,
            "com.garena.game.kid" to FREE_FIRE_MAX,
            "com.garena.game.IDungeon" to FREE_FIRE,
            "com.tencent.ig" to PUBG_MOBILE,
            "com.pubg.krmobile" to PUBG_MOBILE,
            "com.pubg.imobile" to PUBG_MOBILE,
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
        "com.garena.game.kgvn" to FREE_FIRE,
        "com.garena.game.kid" to FREE_FIRE_MAX,
        "com.garena.game" to FREE_FIRE,
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
    fun launchableApps(): List<Installed> = runCatching {
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
    fun gameLikePackages(): List<Installed> {
        val needles = listOf(
            "garena", "freefire", "free.fire", "moonton", "mobilelegends", "mlbb",
            "tencent", "pubg", "ig", "krmobile", "rekoo", "vng", "activision",
            "callofduty", "criticalops", "supercell", "clash", "riot", "valorant",
            "miHoYo", "genshin", "honorofkings", "sgame", "荒野", "free fire"
        )
        return launchableApps().filter { app ->
            val hay = "${app.packageName} ${app.label}".lowercase()
            needles.any { hay.contains(it.lowercase()) }
        }
    }

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
