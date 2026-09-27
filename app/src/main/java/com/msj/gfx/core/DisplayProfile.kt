package com.msj.gfx.core

import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * What the panel can actually do, and what that means for a look.
 *
 * A look is authored as a set of numbers, but those numbers mean different
 * things on different panels, so applying them raw wastes them on the wrong
 * hardware:
 *
 *  - On OLED, black is physically off, so a depth tint deepens something the
 *    panel is already good at. Strong depth looks superb.
 *  - On an LCD, the backlight is always on, so heavy depth just lifts the
 *    black point and turns shadows into grey mud. The same preset that sings
 *    on a flagship AMOLED looks broken on a budget IPS.
 *  - A 120Hz request on a 90Hz panel either fails or gets clamped by the OS,
 *    so asking for the panel's real max is strictly better than a hardcoded
 *    number.
 *  - Wide gamut decides whether an OLED vivid profile is worth asking for at
 *    all; on a sRGB-only panel it is a no-op at best.
 *
 * The tint layer still cannot add sharpness, detail or HDR - a uniform
 * translucent window carries no more information than the frame underneath
 * it. What it can do is stop each look being applied at a strength the panel
 * cannot render, which is the difference between "this looks made for my
 * phone" and "this is a slider someone else set".
 */
data class DisplayProfile(
    /** True when the panel has genuinely off pixels. */
    val isOled: Boolean,
    /** Highest refresh the display reports, e.g. 120.0 */
    val maxRefreshHz: Float,
    /** Panel can render HDR content. */
    val hdrCapable: Boolean,
    /** Panel covers DCI-P3 rather than plain sRGB. */
    val wideGamut: Boolean,
    /** Long edge in px, so adaptation can distinguish a phone from a tablet. */
    val longEdgePx: Int
) {
    /**
     * How much of an authored depth value this panel can actually show.
     *
     * 0.45 on an LCD is not arbitrary: the eye reads a lifted black as a
     * washed-out image, so the useful fraction of the requested depth is
     * small before it turns into fog. OLED keeps the full amount because the
     * darks genuinely disappear.
     */
    val depthScale: Float get() = if (isOled) 1.0f else 0.45f

    val shortEdgeDp: Int
        get() = if (longEdgePx <= 0) 0
        else (longEdgePx / 3).coerceIn(0, 1200)

    /** A short, honest summary for the UI. */
    fun describe(): String = buildString {
        append(if (isOled) "OLED" else "LCD")
        if (maxRefreshHz > 0f) append(" \u00b7 up to ${maxRefreshHz.toInt()}Hz")
        if (wideGamut) append(" \u00b7 wide gamut")
        if (hdrCapable) append(" \u00b7 HDR")
    }

    companion object {
        /**
         * Fallback used before the display is available, and in tests.
         * Assumes a modern OLED at 120Hz, which is the common case and the
         * least lossy assumption: depth is not scaled down on OLED.
         */
        val ASSUMED = DisplayProfile(
            isOled = true,
            maxRefreshHz = 120f,
            hdrCapable = true,
            wideGamut = true,
            longEdgePx = 2400
        )

        /**
         * Read the live display.
         *
         * Panel technology is the one thing Android does not expose - there is
         * no public API for "is this an OLED", and every reliable check is a
         * fingerprint of model strings that goes stale constantly. So it is
         * left as a user setting with a conservative guess here, and [looksLikeOled]
         * documents the guess rather than pretending to certainty.
         */
        fun read(ctx: android.content.Context, userSaysOled: Boolean?): DisplayProfile {
            val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager
            val d = wm?.defaultDisplay ?: return ASSUMED

            val maxHz = runCatching {
                val modes = d.supportedModes ?: return@runCatching 0f
                modes.maxOfOrNull { it.refreshRate } ?: 0f
            }.getOrDefault(0f)

            val wide = runCatching { d.isWideColorGamut }.getOrDefault(false)
            val hdr = runCatching { d.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true }
                .getOrDefault(false)

            val metrics = runCatching {
                android.util.DisplayMetrics().also { d.getRealMetrics(it) }
            }.getOrNull()

            val longEdge = ((metrics?.widthPixels ?: 0).coerceAtLeast(metrics?.heightPixels ?: 0))

            return DisplayProfile(
                isOled = userSaysOled ?: looksLikeOled(d, hdr, wide),
                maxRefreshHz = maxHz,
                hdrCapable = hdr,
                wideGamut = wide,
                longEdgePx = longEdge
            )
        }

        /**
         * The guess, stated as a guess.
         *
         * Wide gamut is the strongest public signal, because an sRGB-only
         * phone is almost always a budget LCD, and the flags Android does
         * surface skew towards flagships. It is still wrong often enough that
         * the UI exposes an override - guessing a user's panel and then
         * silently halving their tint would be worse than asking.
         */
        private fun looksLikeOled(d: Display, hdr: Boolean, wide: Boolean): Boolean {
            if (wide) return true
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".lowercase()
            val knownLcd = listOf(
                "moto g", "redmi note 12", "redmi note 13", "realme c",
                "a15", "a25", "a35", "a55", "galaxy a0", "galaxy a1",
                "galaxy a2", "galaxy a3", "reno", "oppo a"
            )
            if (knownLcd.any { name.contains(it) }) return false
            return hdr
        }
    }
}

/**
 * Rescale an authored look for the panel it is about to run on.
 *
 * Pure function on purpose - this is the part worth testing, and it has to be
 * testable without an Android device, because a wrong guess here is a tint
 * that is too weak to see or too strong to play through.
 */
fun LookPreset.adaptTo(p: DisplayProfile): LookPreset = copy(
    depth = (depth * p.depthScale).toInt().coerceIn(0, 40),
    // Ask for the panel's real ceiling rather than a fixed number, so a
    // 144Hz phone is not held at 120 and a 90Hz phone is not asked for 120.
    refreshHz = if (refreshHz > 0) {
        if (p.maxRefreshHz <= 0f) refreshHz else refreshHz.toInt()
            .coerceAtMost(p.maxRefreshHz.toInt()).coerceAtLeast(1)
    } else 0,
    // A vivid profile on an sRGB-only panel does nothing worth the write.
    oemVivid = oemVivid && p.wideGamut
)
