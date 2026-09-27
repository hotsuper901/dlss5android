package com.msj.gfx.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named "look" - the thing you actually wanted when you said you wanted
 * presets that apply to the game.
 *
 * A single tap sets the whole look: the tint depth and warmth pushed over the
 * game by [ColorOverlayService], an optional refresh target, and an optional
 * request for the OEM vivid colour mode.
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
        depth = 0, warmth = 0, refreshHz = 0, oemVivid = false
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
        depth = 18, warmth = 4, refreshHz = 0, oemVivid = true
    )

    val CRUSHED = LookPreset(
        "crush", "Crushed", "Heaviest blacks, hard contrast",
        depth = 30, warmth = 0, refreshHz = 0, oemVivid = true
    )

    val SUBTLE = LookPreset(
        "subtle", "Subtle", "Barely there, all day",
        depth = 5, warmth = 2, refreshHz = 0, oemVivid = false
    )

    val DARK_ROOM = LookPreset(
        "dark", "Dark Room", "For a dim room, cuts glare",
        depth = 24, warmth = -6, refreshHz = 0, oemVivid = false
    )

    val NIGHT = LookPreset(
        "night", "Night Warm", "Amber white point",
        depth = 10, warmth = 34, refreshHz = 0, oemVivid = false
    )

    /**
     * Was "Shadow Lift", and it could never have worked: a tint layer can only
     * darken, and lifting shadows means brightening them. depth clamps to
     * 0..40, so the -0 it was written with was a zero wearing a minus sign and
     * the preset did nothing at all. This is the one direction the mechanism
     * genuinely goes.
     */
    val COOL = LookPreset(
        "cool", "Cool Shadow", "Blue tint, cuts glare late at night",
        depth = 12, warmth = -30, refreshHz = 0, oemVivid = false
    )

    val ICE = LookPreset(
        "ice", "Ice", "Hard blue, coldest white point",
        depth = 8, warmth = -52, refreshHz = 0, oemVivid = false
    )

    val EMBER = LookPreset(
        "ember", "Ember", "Strong amber, heavy",
        depth = 20, warmth = 48, refreshHz = 0, oemVivid = false
    )

    val SEPIA = LookPreset(
        "sepia", "Sepia", "Warm and vintage",
        depth = 14, warmth = 40, refreshHz = 0, oemVivid = false
    )

    val BLUE_SHIFT = LookPreset(
        "blue", "Blue Shift", "Strong cool, moderate dark",
        depth = 16, warmth = -44, refreshHz = 0, oemVivid = false
    )

    // --- combinations -------------------------------------------------------

    /**
     * The most "vibrant" look the mechanism can honestly be.
     *
     * Anime art is flat-shaded and already high-chroma, so the useful move is
     * to stop the darks from muddying it and let the OEM vivid profile widen
     * the gamut underneath. Depth stays low deliberately: pushing it here
     * crushes the shadow lines that make cel shading read as cel shading.
     *
     * Worth being clear about the ceiling - a uniform tint cannot add detail
     * or make the art sharper. What this does is stop the panel fighting the
     * artwork, which is the part that was actually on the table.
     */
    val POP = LookPreset(
        "pop", "Anime Pop", "Flat, wide gamut, clean shadows",
        depth = 6, warmth = 8, refreshHz = 0, oemVivid = true
    )

    val TEAL_ORANGE = LookPreset(
        "teal", "Teal / Orange", "Slight depth, warm white point",
        depth = 14, warmth = 18, refreshHz = 0, oemVivid = false
    )

    val CINEMATIC = LookPreset(
        "cine", "Cinema", "Deep, slightly warm, vivid profile",
        depth = 22, warmth = 12, refreshHz = 0, oemVivid = true
    )

    val MATTE = LookPreset(
        "matte", "Matte", "Flat and even, no crush",
        depth = 8, warmth = -4, refreshHz = 0, oemVivid = false
    )

    val CONTRAST_KING = LookPreset(
        "king", "Max Contrast", "Blackest blacks plus vivid",
        depth = 38, warmth = 0, refreshHz = 0, oemVivid = true
    )

    val MAX = LookPreset(
        "max", "Max Look", "Heavy depth plus vivid profile",
        depth = 26, warmth = 0, refreshHz = 0, oemVivid = true
    )

    // --- performance --------------------------------------------------------

    /**
     * refreshHz is a request, not a pin: GameWatcher compares it against the
     * panel's actual max and skips if the screen cannot reach it.
     */
    val FPS_FOCUS = LookPreset(
        "fps", "FPS Focus", "Peak refresh, colour untouched",
        depth = 0, warmth = 0, refreshHz = 120, oemVivid = false
    )

    val FPS_CLEAN = LookPreset(
        "fpsc", "FPS Clean", "Peak refresh and deep blacks",
        depth = 16, warmth = 0, refreshHz = 120, oemVivid = false
    )

    val FPS_LAG = LookPreset(
        "fpsl", "FPS + Vivid", "Peak refresh and vivid profile",
        depth = 0, warmth = 0, refreshHz = 120, oemVivid = true
    )

    val EVERYTHING = LookPreset(
        "all", "Everything", "Peak refresh, deep, warm, vivid",
        depth = 28, warmth = 14, refreshHz = 120, oemVivid = true
    )

    val BUILT_IN = listOf(
        OFF,
        OLED_BOOST, CRUSHED, SUBTLE, DARK_ROOM,
        NIGHT, COOL, ICE, EMBER, SEPIA, BLUE_SHIFT,
        POP, TEAL_ORANGE, CINEMATIC, MATTE, CONTRAST_KING, MAX,
        FPS_FOCUS, FPS_CLEAN, FPS_LAG, EVERYTHING
    )

    /**
     * Every built-in except [OFF] must change something. Three presets shipped
     * dead before this existed - two with all-zero fields and one written as
     * "-0" - and nothing caught it, because a no-op preset still compiles, still
     * renders, still shows an ON badge. verify-looks.kt asserts this.
     */
    fun noOpBuiltIns(): List<String> = BUILT_IN
        .filter { it.key != "off" }
        .filter { it.depth == 0 && it.warmth == 0 && it.refreshHz == 0 && !it.oemVivid }
        .map { it.key }

    /**
     * Imported looks are appended, minus any that collide with a built-in key.
     *
     * Without that filter an import file could put a second "OLED Boost" in the
     * list, and since byKey() takes the first match, the duplicate would be an
     * unreachable row that could never be selected. A built-in key always wins:
     * shipping a corrected built-in should not be defeated by a stale export.
     */
    fun all(imported: List<LookPreset> = emptyList()): List<LookPreset> =
        BUILT_IN + imported.filter { imp ->
            imp.custom && BUILT_IN.none { it.key == imp.key }
        }

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
