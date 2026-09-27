package com.msj.gfx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named "look" - the thing you actually wanted when you said you wanted
 * presets that apply to the game.
 *
 * The first four fields drive the live overlay [ColorOverlayService] and the
 * image pipeline [ImageEnhancer], so a single tap sets the whole look: depth,
 * warmth, saturation, contrast, sharpen, plus an optional refresh target and
 * an optional request for the OEM vivid colour mode.
 *
 * There is no renderer hook in here and there is not going to be one. This is
 * the display-side half and the offline pipeline half, which is everything
 * reachable without root on Android 16, where PAC/BTI and memory tagging have
 * closed off the injection path the older injector guides assume.
 */
data class LookPreset(
    val key: String,
    val title: String,
    val blurb: String,
    /** 0..40 overlay depth. 0 = no overlay at all. */
    val depth: Int,
    /** -60..60 white-point shift. */
    val warmth: Int,
    /** 0..250, 100 neutral. */
    val saturation: Int,
    /** 50..160, 100 neutral. */
    val contrast: Int,
    /** 0..100 unsharp mask. */
    val sharpen: Int,
    /** 0..100 denoise. */
    val denoise: Int,
    /** Peak refresh target in Hz, 0 = leave the display alone. */
    val refreshHz: Int,
    /** Ask the OEM display pipeline for its vivid profile. */
    val oemVivid: Boolean,
    val custom: Boolean = false
) {
    /** True when this look needs the live overlay, i.e. it is not purely offline. */
    val needsOverlay: Boolean get() = depth != 0 || warmth != 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("title", title)
        put("blurb", blurb)
        put("depth", depth)
        put("warmth", warmth)
        put("saturation", saturation)
        put("contrast", contrast)
        put("sharpen", sharpen)
        put("denoise", denoise)
        put("refreshHz", refreshHz)
        put("oemVivid", oemVivid)
    }

    companion object {
        fun fromJson(o: JSONObject): LookPreset = LookPreset(
            key = o.optString("key", "custom"),
            title = o.optString("title", "Custom"),
            blurb = o.optString("blurb", "Imported look"),
            depth = o.optInt("depth", 0).coerceIn(0, 40),
            warmth = o.optInt("warmth", 0).coerceIn(-60, 60),
            saturation = o.optInt("saturation", 100).coerceIn(0, 250),
            contrast = o.optInt("contrast", 100).coerceIn(50, 160),
            sharpen = o.optInt("sharpen", 0).coerceIn(0, 100),
            denoise = o.optInt("denoise", 0).coerceIn(0, 100),
            refreshHz = o.optInt("refreshHz", 0).coerceIn(0, 240),
            oemVivid = o.optBoolean("oemVivid", false),
            custom = true
        )
    }
}

object LookPresets {

    /**
     * OFF is the default. Not because a preset is scary, but because the first
     * time the app changes how your screen looks should be your decision, not
     * a side effect of installing it.
     */
    val OFF = LookPreset(
        "off", "Off", "No colour change at all",
        depth = 0, warmth = 0, saturation = 100, contrast = 100,
        sharpen = 0, denoise = 0, refreshHz = 0, oemVivid = false
    )

    /**
     * The one that matters most on a phone. A real OLED pixel is off at black,
     * so crushing the black point and lifting the mid-tones adds apparent
     * contrast the panel is physically capable of showing, and the perceived
     * brightness jump comes from the darks genuinely disappearing rather than
     * from us pushing the backlight. Refresh stays off by default because a
     * 120Hz pin is a real battery cost and that should be opt-in.
     */
    val OLED_BOOST = LookPreset(
        "oled", "OLED Boost", "Deep blacks, lifted mids",
        depth = 18, warmth = 4, saturation = 128, contrast = 126,
        sharpen = 55, denoise = 10, refreshHz = 0, oemVivid = true
    )

    /** Maximum display refresh, nothing else touched. For input-latency feel. */
    val FPS_FOCUS = LookPreset(
        "fps", "FPS Focus", "Peak refresh, colour left alone",
        depth = 0, warmth = 0, saturation = 100, contrast = 100,
        sharpen = 0, denoise = 0, refreshHz = 0, oemVivid = false
    )

    /** Flat, high-chroma look. Reads well on cheap LCDs, heavy on OLED. */
    val POP = LookPreset(
        "pop", "Anime Pop", "Flat and heavily saturated",
        depth = 6, warmth = 10, saturation = 175, contrast = 118,
        sharpen = 75, denoise = 0, refreshHz = 0, oemVivid = true
    )

    /** The teal-and-orange grade. Pushes cool into shadows, warm into skin. */
    val TEAL_ORANGE = LookPreset(
        "teal", "Teal / Orange", "Cool shadows, warm skin",
        depth = 14, warmth = 18, saturation = 120, contrast = 120,
        sharpen = 50, denoise = 15, refreshHz = 0, oemVivid = false
    )

    /** For late sessions. Warm, low blue, no sharpening. */
    val NIGHT = LookPreset(
        "night", "Night Warm", "Amber white point, soft",
        depth = 10, warmth = 34, saturation = 95, contrast = 104,
        sharpen = 20, denoise = 25, refreshHz = 0, oemVivid = false
    )

    /** Slight lift so dark areas in a dark game are readable without flashlight. */
    val LIFT = LookPreset(
        "lift", "Shadow Lift", "Reads dark scenes better",
        depth = -0, warmth = 0, saturation = 112, contrast = 96,
        sharpen = 30, denoise = 35, refreshHz = 0, oemVivid = false
    )

    /** Everything at once, for measuring what each stage is actually worth. */
    val MAX = LookPreset(
        "max", "Max Look", "Every stage turned up",
        depth = 26, warmth = 0, saturation = 205, contrast = 142,
        sharpen = 100, denoise = 0, refreshHz = 0, oemVivid = true
    )

    val BUILT_IN = listOf(
        OFF, OLED_BOOST, FPS_FOCUS, POP, TEAL_ORANGE, NIGHT, LIFT, MAX
    )

    /** Imported looks are appended by [merge]. */
    fun all(imported: List<LookPreset> = emptyList()): List<LookPreset> =
        BUILT_IN + imported.filter { it.custom }

    fun byKey(k: String?, imported: List<LookPreset> = emptyList()): LookPreset =
        all(imported).firstOrNull { it.key == k } ?: OFF

    /** Serialise every look, built-in and imported, for backup. */
    fun exportJson(imported: List<LookPreset> = emptyList()): String {
        val arr = JSONArray()
        all(imported).forEach { arr.put(it.toJson()) }
        return JSONObject().put("version", 1).put("looks", arr).toString(2)
    }

    /**
     * Parse a look file. Returns only entries that survive validation, so a
     * hand-edited or truncated file degrades to "some looks imported" instead
     * of throwing the user out of the screen.
     */
    fun importJson(text: String): List<LookPreset> = runCatching {
        val root = JSONObject(text)
        val arr = root.optJSONArray("looks") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { LookPreset.fromJson(it) }
        }
    }.getOrDefault(emptyList())
}
