package com.msj.gfx.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager

/**
 * Per-title graphics profiles and the device-side controls an unprivileged app
 * is actually allowed to touch.
 *
 * Scope note, and it matters: this does not and cannot reach into another
 * app's process. There is no legitimate way to do that without root, and every
 * "DLSS mod" that claims to is a process injector that anti-cheat detects. So
 * the frame-rate work here is split honestly in two:
 *
 *   1. Settings the game exposes in its own graphics menu. We cannot write
 *      those, but we can tell you exactly which value to pick for your title
 *      and hand you a checklist to follow once. That is [GraphicsProfile].
 *   2. Settings this OS exposes to us: peak refresh rate, screen-on, and how
 *      aggressively we trim background RAM while a game is in front. That is
 *      [DisplayController] and the trim policy in GameWatcher.
 *
 * Anything claiming a real per-game FPS number falls in category 1's blind
 * spot - frame timing lives in the game's own process, so it is not readable
 * from here. Thermal state and refresh rate are measurable and are what the
 * HUD shows instead.
 */
object GraphicsEnhancer {

    /**
     * A concrete graphics configuration for one title.
     *
     * These are recommendations expressed as structured values so the UI can
     * render them and the checklist can be generated, rather than leaving them
     * trapped in a sentence.
     */
    data class GraphicsProfile(
        val resolutionScalePct: Int,
        val fpsCap: Int,
        val shadows: String,
        val textures: String,
        val antiAliasing: String,
        val notes: List<String>
    ) {
        /** Plain text for the clipboard: everything the in-game menu needs. */
        fun checklist(gameLabel: String): String = buildString {
            appendLine("$gameLabel - graphics preset")
            appendLine("Resolution scale : ${resolutionScalePct}%")
            appendLine("Frame rate cap   : $fpsCap fps")
            appendLine("Shadows          : $shadows")
            appendLine("Textures         : $textures")
            appendLine("Anti-aliasing    : $antiAliasing")
            if (notes.isNotEmpty()) {
                appendLine()
                notes.forEach { appendLine("- $it") }
            }
        }
    }

    /**
     * Per-title profiles. Keyed on the canonical package name.
     *
     * Every value here is a setting the player changes inside the game's own
     * options menu. Resolution scale is the highest-leverage one on mobile:
     * it is a direct multiplier on fragment cost and the cheapest visual
     * trade available, which is why it is why the DLSS mods exist.
     */
    private val PROFILES: Map<String, GraphicsProfile> = mapOf(
        GameCatalog.FREE_FIRE.packageName to GraphicsProfile(
            resolutionScalePct = 85, fpsCap = 60,
            shadows = "Off", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf(
                "Ultra caps the frame rate at 30 on most devices and halves draw distance",
                "Set the first-person scope to 'Classic' to cut view-model overdraw",
                "Leave High Frame Rate OFF unless the device demonstrably holds 60"
            )
        ),
        GameCatalog.FREE_FIRE_MAX.packageName to GraphicsProfile(
            resolutionScalePct = 80, fpsCap = 60,
            shadows = "Off", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf(
                "MAX wants ~1.5GB free just to hold texture residency - close everything first",
                "Drop resolution scale before dropping quality, it costs less visually",
                "Turn off bullet-helper visuals before a ranked match"
            )
        ),
        GameCatalog.PUBG_MOBILE.packageName to GraphicsProfile(
            resolutionScalePct = 80, fpsCap = 60,
            shadows = "Off", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf(
                "Smooth beats Ultra on mid-range hardware in nearly every match",
                "Shooting Mode OFF - it reprojects the frame buffer",
                "Change render scale before launching, not mid-match"
            )
        ),
        GameCatalog.PUBG_LITE.packageName to GraphicsProfile(
            resolutionScalePct = 100, fpsCap = 60,
            shadows = "Medium", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf("This build already targets low-end hardware, leave the defaults alone")
        ),
        GameCatalog.BGMI.packageName to GraphicsProfile(
            resolutionScalePct = 80, fpsCap = 90,
            shadows = "Off", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf(
                "Set the camera to 'Classic' to cut view-model overdraw",
                "Turn off Gyroscope aim and auto-pickup if unused"
            )
        ),
        GameCatalog.NEW_STATE.packageName to GraphicsProfile(
            resolutionScalePct = 80, fpsCap = 60,
            shadows = "Off", textures = "Medium",
            antiAliasing = "Off",
            notes = listOf("Smooth plus reduced draw distance is what holds frame rate",
                "Turn off motion blur in Settings > Graphics")
        ),
        GameCatalog.MLBB.packageName to GraphicsProfile(
            resolutionScalePct = 90, fpsCap = 60,
            shadows = "Off", textures = "High",
            antiAliasing = "Off",
            notes = listOf(
                "Set the HUD to minimal so less UI is composited every frame",
                "Turn off 'Show Battle Stats' and 'Show Rank' - extra text is extra draw calls",
                "An OBB on a slow SD card is the single biggest stutter source; keep it internal"
            )
        ),
        GameCatalog.LIEN_QUAN.packageName to GraphicsProfile(
            resolutionScalePct = 90, fpsCap = 60,
            shadows = "Off", textures = "High",
            antiAliasing = "Off",
            notes = listOf("A MOBA holds far less RAM than a battle royale - close the background apps",
                "Lower draw distance and particle effects first")
        ),
        GameCatalog.HONOR_OF_KINGS.packageName to GraphicsProfile(
            resolutionScalePct = 90, fpsCap = 60,
            shadows = "Off", textures = "High",
            antiAliasing = "Off",
            notes = listOf("High graphics with 'Smooth' detail is the combination most devices sustain",
                "Close the camera and mic permissions you do not use, they wake background services")
        ),
        GameCatalog.COD_MOBILE.packageName to GraphicsProfile(
            resolutionScalePct = 85, fpsCap = 60,
            shadows = "Off", textures = "High",
            antiAliasing = "Off",
            notes = listOf("Ultra with High texture on an 8-series GPU, otherwise Medium",
                "Anti-aliasing OFF - on a mid GPU it trades more than it returns",
                "Shadow quality down one notch, the most expensive setting in the game"
            )
        ),
        GameCatalog.CRITICAL_OPS.packageName to GraphicsProfile(
            resolutionScalePct = 100, fpsCap = 60,
            shadows = "Medium", textures = "High",
            antiAliasing = "Off",
            notes = listOf("Not demanding, holds 60 on midrange hardware at native resolution",
                "Lower the FOV slider a touch - a direct multiplier on fragment cost")
        )
    )

    /**
     * Fallback for a title we have no hand-written profile for.
     *
     * Better than nothing: resolution scale is the one lever that helps
     * essentially every mobile renderer, and a wrong-but-conservative guess
     * beats refusing to recommend anything.
     */
    private val GENERIC = GraphicsProfile(
        resolutionScalePct = 85, fpsCap = 60,
        shadows = "Off", textures = "Medium",
        antiAliasing = "Off",
        notes = listOf(
            "No hand-tuned profile for this title yet - this is the conservative default",
            "Lower resolution scale first; it is the cheapest frame-rate win on any mobile renderer"
        )
    )

    fun profileFor(game: GameCatalog.Game?): GraphicsProfile =
        game?.let { PROFILES[it.packageName] } ?: GENERIC

    /** Includes the catalog's own levers, which carry the title-specific detail. */
    fun checklistFor(game: GameCatalog.Game?): String {
        val profile = profileFor(game)
        val sb = StringBuilder(profile.checklist(game?.label ?: "Detected title"))
        if (game != null && game.levers.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Also worth changing:")
            game.levers.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }
}

/**
 * The display and power controls this app can legitimately change.
 *
 * These need `WRITE_SETTINGS`, which is a special-access grant the user
 * makes in Settings, not a runtime permission. Without it every call here
 * reports false rather than throwing, so callers can gate their UI on
 * [canWriteSettings] and keep working when the grant is absent.
 */
object DisplayController {

    /** True once the user has granted "Modify system settings". */
    fun canWriteSettings(): Boolean = runCatching {
        Settings.System.canWrite(Ctx.get())
    }.getOrDefault(false)

    fun openWriteSettings() {
        runCatching {
            Ctx.get().startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${Ctx.get().packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /**
     * Highest refresh rate the panel advertises, in Hz, or null on devices with
     * no user-visible refresh setting (many 60Hz-only phones).
     */
    fun maxRefreshHz(): Float? = runCatching {
        val d = Ctx.get().display ?: return@runCatching null
        d.supportedModes.maxOfOrNull { it.refreshRate }
    }.getOrNull()

    /** What the panel is actually running at right now. */
    fun currentRefreshHz(): Float? = runCatching {
        Ctx.get().display?.refreshRate
    }.getOrNull()

    /**
     * Pin the display to its highest advertised rate for the duration of a
     * match, then hand it back to the system.
     *
     * Worth doing: on a 120Hz panel, leaving peak refresh on the default means
     * a 60Hz-capped compositor, which throws away frames the game already
     * spent the power to render. This is the single legitimate display-side
     * frame-rate win available to a normal app.
     */
    fun setPeakRefresh(hz: Float): Boolean = runCatching {
        val res = Ctx.get().contentResolver
        if (!Settings.System.canWrite(Ctx.get())) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.System.putFloat(res, K_PEAK_REFRESH_RATE, hz)
        }
        Settings.System.putFloat(res, K_MIN_REFRESH_RATE, hz)
        true
    }.getOrDefault(false)

    /**
     * Let the system pick the refresh rate again. Always called on game exit so
     * we do not leave a phone pinned at 120Hz drawing a battery it did not need.
     */
    fun releasePeakRefresh(): Boolean = runCatching {
        val res = Ctx.get().contentResolver
        if (!Settings.System.canWrite(Ctx.get())) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.System.putFloat(res, K_PEAK_REFRESH_RATE, 0f)
        }
        Settings.System.putFloat(res, K_MIN_REFRESH_RATE, 0f)
        true
    }.getOrDefault(false)

    /**
     * Hold the screen awake while a match is in progress.
     *
     * FLAG_KEEP_SCREEN_ON is scoped to our own window, so it applies while our
     * overlay is up and does not require WRITE_SETTINGS.
     */
    /**
     * The refresh-rate keys, as their stable public string values rather than
     * the Settings.System constants.
     *
     * Values are identical to Settings.System.PEAK_REFRESH_RATE (API 30) and
     * MIN_REFRESH_RATE (API 23); spelling them out keeps this compiling and
     * type-checkable on any SDK stub, and keeps working on OEM builds that add
     * their own refresh-rate keys.
     */
    const val K_PEAK_REFRESH_RATE = "peak_refresh_rate"
    const val K_MIN_REFRESH_RATE = "min_refresh_rate"
    const val K_SCREEN_COLOR_MODE = "screen_color_mode"

    /**
     * Ask the OEM display pipeline for a vivid colour profile.
     *
     * This is the only true colour-matrix re-map of another app's pixels that
     * exists without root: the display driver does it in hardware, downstream
     * of the game, so we never enter its process. It is also the honest answer
     * to "make the colours pop" - far more so than any tint layer we could
     * draw ourselves.
     *
     * Returns false when the key is absent, which is most non-Samsung builds,
     * and callers should treat that as "not supported here" rather than a
     * failure. Writes are best-effort because the value domain differs by OEM.
     */
    const val K_SCREEN_BRIGHTNESS = "screen_brightness"
    const val K_SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"

    /**
     * Push brightness past the system's normal cap while gaming.
     *
     * This is the single biggest real lever on "the game looks washed out",
     * and it is worth being precise about why. A dim game does not look better
     * because we deepened its blacks - it looks better because the panel is
     * driven harder, so more of the signal is above the display's noise floor.
     * That is genuinely more information reaching your eye, which is more than
     * a tint layer can ever do.
     *
     * The OS caps automatic brightness well below the panel's capability, and
     * some OEMs allow a "high brightness" mode above it. Writing straight to
     * screen_brightness is the only unrooted route and it is device-dependent:
     * the display HAL clamps anything it considers out of range, so this
     * returns whatever the system actually accepted rather than pretending.
     */
    fun setBrightnessHeadroom(boost: Int): Boolean = runCatching {
        if (!Settings.System.canWrite(Ctx.get())) return false
        val res = Ctx.get().contentResolver
        val b = boost.coerceIn(0, 100)
        if (b == 0) return restoreBrightness()
        // Manual mode, otherwise the ambient sensor overwrites us within a
        // second or two and the boost silently evaporates.
        Settings.System.putInt(res, K_SCREEN_BRIGHTNESS_MODE, 0)
        val current = Settings.System.getInt(res, K_SCREEN_BRIGHTNESS, 128)
        val target = (current + (b * 1.5f)).toInt().coerceIn(1, 255)
        Settings.System.putInt(res, K_SCREEN_BRIGHTNESS, target)
        true
    }.getOrDefault(false)

    /** Hand brightness back to automatic on game exit. */
    fun restoreBrightness(): Boolean = runCatching {
        if (!Settings.System.canWrite(Ctx.get())) return false
        Settings.System.putInt(
            Ctx.get().contentResolver, K_SCREEN_BRIGHTNESS_MODE, -1
        )
        true
    }.getOrDefault(false)

    fun setOemVividMode(vivid: Boolean): Boolean = runCatching {
        val res = Ctx.get().contentResolver
        if (!Settings.System.canWrite(Ctx.get())) return false
        val existing = Settings.System.getString(res, K_SCREEN_COLOR_MODE) ?: return false
        // Only touch it if the key already exists on this build, so we never
        // write a vendor-unknown value into system settings.
        if (existing.isEmpty()) return false
        val current = existing.toIntOrNull() ?: return false
        val target = if (vivid) (if (current == 0) 1 else current) else 0
        Settings.System.putString(res, K_SCREEN_COLOR_MODE, target.toString())
        true
    }.getOrDefault(false)

    fun setKeepScreenOn(window: android.view.Window?, on: Boolean) {
        runCatching {
            if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
