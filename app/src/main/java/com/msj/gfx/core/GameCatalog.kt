package com.msj.gfx.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Tiny global context holder. Set once in Application.onCreate, which runs
 * before any service or receiver, so nothing ever races on first read.
 */
object Ctx {
    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        if (app == null) {
            synchronized(this) {
                if (app == null) app = context.applicationContext
            }
        }
    }

    fun get(): Context = app ?: error("Ctx not installed - Application.onCreate did not run")
}

/** Supported titles, matched on package name only. */
object GameCatalog {

    data class Game(
        val packageName: String,
        val label: String,
        val accent: Long,          // ARGB
        /** What actually moves framerate on this title, in plain language. */
        val levers: List<String>
    )

    val FREE_FIRE = Game(
        "com.garena.game.kgvn", "Free Fire", 0xFFFF7A45,
        listOf(
            "Settings > Graphics > Smooth (Ultra caps at 30fps, costs you half the draw distance)",
            "Camera > first-person scope lowered to 'Classic' to cut view-model overdraw",
            "Turn off Auto-shoot and the auto-aim training popup so the frame budget goes to rendering",
            "Background apps are the real killer - 400MB of stale RAM is where stutter comes from"
        )
    )

    val FREE_FIRE_MAX = Game(
        "com.garena.game.kid", "Free Fire MAX", 0xFFFFB020,
        listOf(
            "Graphics > Ultra, and turn on 'Anti-aliasing' OFF (it costs 8-10fps on most Adreno/Mali)",
            "Camera > 'Classic' scope",
            "Turn OFF bullet-helper visual effects before a ranked match, they re-render every bullet",
            "Close everything else - MAX wants 1.5GB free just to hold texture residency"
        )
    )

    val PUBG_MOBILE = Game(
        "com.tencent.ig", "PUBG Mobile", 0xFF3DDC97,
        listOf(
            "Graphics > Smooth + Ultra if your SoC is a 8-series - Smooth is worth more than Ultra on mid-rangers",
            "Frame rate > 90fps only if the device holds it, otherwise it oscillates and stutters",
            "Anti-aliasing OFF, and turn the 'Shooting Mode' off (it reprojects the frame buffer)",
            "Raise the render scale in developer options BEFORE launching, not mid-match"
        )
    )

    val ALL: List<Game> = listOf(FREE_FIRE, FREE_FIRE_MAX, PUBG_MOBILE)

    private val EXTRA_ALIASES = mapOf(
        "com.garena.game.kid" to FREE_FIRE_MAX,
        "com.pubg.krmobile" to PUBG_MOBILE,
        "com.vng.pubgmobile" to PUBG_MOBILE,
        "com.rekoo.pubgm" to PUBG_MOBILE,
        "com.pubg.imobile" to PUBG_MOBILE
    )

    fun match(packageName: String?): Game? {
        if (packageName.isNullOrEmpty()) return null
        ALL.firstOrNull { it.packageName == packageName }?.let { return it }
        // Process names get mangled by the vendor (com.tencent.ig:unity) - prefix match
        return EXTRA_ALIASES[packageName.substringBefore(':')]
            ?: ALL.firstOrNull { packageName.startsWith(it.packageName) }
    }

    fun installed(): List<Game> {
        val pm: PackageManager = Ctx.get().packageManager
        return ALL.filter {
            runCatching { pm.getPackageInfo(it.packageName, 0); true }.getOrDefault(false)
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
}
