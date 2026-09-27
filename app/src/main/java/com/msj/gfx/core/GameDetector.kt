package com.msj.gfx.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground game detection that actually works.
 *
 * The previous implementation asked a single question - "what package resumed
 * most recently in the last four seconds, ignoring ourselves?" - and that
 * question has a fatal flaw:
 *
 * To read the dashboard you have to bring MSJ GFX to the foreground. The moment
 * you do, the most recent foreground event is *our own* Activity. We skip
 * ourselves, and the previous foreground event is the game you launched a minute
 * ago, which is outside the four second window. The result is null. So the
 * detector could never report a game at the exact moment the user was looking
 * at the app to see whether it had - which is precisely when they look.
 *
 * Two fixes:
 *
 * 1. A wide window (two minutes) so a game launched a while ago is still
 *    visible, and stickiness so a poll that finds nothing new keeps the last
 *    known hit instead of blanking the UI.
 *
 * 2. Recognition is not limited to the hardcoded catalog. A package that looks
 *    like a game is treated as one even when its exact name is unknown, which
 *    is what happens with regional and repackaged builds.
 *
 * The last hit is persisted, so it also survives the booster service being
 * killed and restarted.
 */
object GameDetector {

    data class Hit(
        val packageName: String,
        val label: String,
        /** Non-null only when the package is one we ship a preset for. */
        val game: GameCatalog.Game?,
        val at: Long
    ) {
        val known: Boolean get() = game != null
    }

    private val sticky = AtomicReference<Hit?>(null)

    private const val STICKY_TTL_MS = 10 * 60 * 1000L

    /** Last known hit regardless of freshness, for diagnostics. */
    fun lastKnown(): Hit? = sticky.get()

    fun clear() {
        sticky.set(null)
        runCatching { Ctx.get().getSharedPreferences("msj_detector", 0).edit().remove("last").apply() }
    }

    /**
     * One detection pass. Binder calls, so never call this from the main thread.
     */
    fun poll(): Hit? {
        if (!MemoryTools.hasUsageAccess()) return sticky.get()

        val raw = MemoryTools.foregroundPackage()
        if (raw.isNullOrBlank()) {
            // Nothing in the window. Hold the previous answer rather than
            // blanking the UI on every poll that lands in a gap.
            return freshen(sticky.get())
        }

        val pkg = raw.substringBefore(':').trim()
        if (pkg.isEmpty() || pkg == Ctx.get().packageName) return freshen(sticky.get())

        val hit = classify(pkg)
        if (hit != null) {
            sticky.set(hit)
            persist(hit)
        }
        return hit ?: freshen(sticky.get())
    }

    /**
     * Catalog match if we know it, otherwise a shape match so an unrecognised
     * build still registers. Returns null for things that are plainly not games
     * (launcher, settings, our own package).
     */
    fun classify(pkg: String): Hit? {
        val known = GameCatalog.match(pkg)
        val label = known?.label ?: GameCatalog.labelFor(pkg)
        if (known != null) return Hit(pkg, label, known, System.currentTimeMillis())
        if (GameCatalog.looksLikeGame(pkg, label)) {
            return Hit(pkg, label, null, System.currentTimeMillis())
        }
        return null
    }

    private fun freshen(prev: Hit?): Hit? {
        if (prev == null) return null
        val age = System.currentTimeMillis() - prev.at
        return if (age < STICKY_TTL_MS) prev else null.also { sticky.set(null) }
    }

    private fun persist(hit: Hit) {
        runCatching {
            Ctx.get().getSharedPreferences("msj_detector", 0).edit()
                .putString("last", hit.packageName)
                .putLong("at", hit.at)
                .apply()
        }
    }

    /** Rehydrates after a process restart so the HUD is not blank on cold start. */
    fun restore(): Hit? {
        sticky.get()?.let { return freshen(it) }
        val hit = runCatching {
            val sp = Ctx.get().getSharedPreferences("msj_detector", 0)
            val pkg = sp.getString("last", null) ?: return null
            val at = sp.getLong("at", 0L)
            classify(pkg)?.copy(at = at)
        }.getOrNull()
        if (hit != null) sticky.set(hit)
        return freshen(hit)
    }
}
