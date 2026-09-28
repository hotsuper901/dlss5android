package com.msj.gfx.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Preset the user picks per game; persisted so it survives a reboot. */
data class Preset(
    val key: String,
    val title: String,
    val subtitle: String,
    val inGameGraphics: String,   // what to set inside the game
    val frameRateTarget: String,
    val antiAliasing: Boolean,
    val autoTrimOnLaunch: Boolean
)

object Presets {
    val BATTERY = Preset(
        "battery", "Battery Saver", "Play 3+ matches on one charge",
        "Smooth", "30 fps", false, true
    )
    val BALANCED = Preset(
        "balanced", "Balanced", "Default daily driver setting",
        "Balanced", "60 fps", false, true
    )
    val AGGRESSIVE = Preset(
        "aggressive", "Competitive", "Chase the lowest frametime, ignore heat",
        "HD / Ultra", "90 fps", false, true
    )
    val ALL = listOf(BATTERY, BALANCED, AGGRESSIVE)
    fun byKey(k: String) = ALL.firstOrNull { it.key == k } ?: BALANCED
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("msj_gfx", Context.MODE_PRIVATE)

    private val _boosterOn = MutableStateFlow(prefs.getBoolean(K_BOOSTER, true))
    val boosterOn = _boosterOn

    private val _overlayOn = MutableStateFlow(prefs.getBoolean(K_OVERLAY, false))
    val overlayOn = _overlayOn

    private val _autoTrim = MutableStateFlow(prefs.getBoolean(K_AUTOTRIM, true))
    val autoTrim = _autoTrim

    private val _preset = MutableStateFlow(Presets.byKey(prefs.getString(K_PRESET, "balanced")!!))
    val preset = _preset

    // --- Graphics Enhancer -------------------------------------------------
    // Defaults are conservative: we do not pin a phone to 120Hz on first run,
    // because that is a real battery cost and it should be the user's call.
    private val _forceRefresh = MutableStateFlow(prefs.getBoolean(K_FORCE_REFRESH, false))
    val forceRefresh = _forceRefresh

    private val _keepAwake = MutableStateFlow(prefs.getBoolean(K_KEEP_AWAKE, true))
    val keepAwake = _keepAwake

    // On by default: a foreground service is not enough on its own, Android can
    // still park the process mid-match, and the lock is only held while a game
    // is actually in front - never at idle, so the default costs nothing off-session.
    private val _cpuWakeLock = MutableStateFlow(prefs.getBoolean(K_CPU_WAKELOCK, true))
    val cpuWakeLock = _cpuWakeLock

    private val _aggressiveTrim = MutableStateFlow(prefs.getBoolean(K_AGGRESSIVE_TRIM, true))
    val aggressiveTrim = _aggressiveTrim

    // Visual layer (live tint over other apps). 0 = layer removed entirely.
    private val _tintDepth = MutableStateFlow(prefs.getInt(K_TINT_DEPTH, 0))
    val tintDepth = _tintDepth

    private val _tintWarmth = MutableStateFlow(prefs.getInt(K_TINT_WARMTH, 0))
    val tintWarmth = _tintWarmth

    /**
     * User override for panel type. null means "use the guess".
     *
     * Android exposes no API for "is this an OLED", so the guess in
     * DisplayProfile is wrong often enough that the UI offers a toggle. Being
     * wrong halves or doubles a tint, and the user can see which way it went.
     */
    private val _panelIsOled = MutableStateFlow(
        when (prefs.getString(K_PANEL, "auto")) {
            "oled" -> true
            "lcd" -> false
            else -> null
        }
    )
    val panelIsOled = _panelIsOled

    /** Brightness headroom, 0..100. Applied on game entry, released on exit. */
    private val _brightnessBoost = MutableStateFlow(prefs.getInt(K_BRIGHT_BOOST, 0))
    val brightnessBoost = _brightnessBoost

    fun setPanelIsOled(v: Boolean?) = prefs.edit()
        .putString(K_PANEL, when (v) { true -> "oled"; false -> "lcd"; null -> "auto" })
        .apply().also { _panelIsOled.value = v }

    fun setBrightnessBoost(v: Int) = prefs.edit()
        .putInt(K_BRIGHT_BOOST, v.coerceIn(0, 100)).apply()
        .also { _brightnessBoost.value = v.coerceIn(0, 100) }

    /** Which look auto-applies when a tracked game hits the foreground. */
    private val _look = MutableStateFlow(
        LookPresets.byKey(prefs.getString(K_LOOK, "off"))
    )
    val look = _look

    /** Looks the user imported, restored from prefs on launch. */
    private val _importedLooks = MutableStateFlow(readImportedLooks())
    val importedLooks = _importedLooks

    fun setLook(l: LookPreset) = prefs.edit().putString(K_LOOK, l.key).apply().also {
        _look.value = l
    }

    fun setImportedLooks(list: List<LookPreset>) {
        val json = org.json.JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        prefs.edit().putString(K_LOOKS_CUSTOM, json).apply()
        _importedLooks.value = list
    }

    private fun readImportedLooks(): List<LookPreset> = runCatching {
        val raw = prefs.getString(K_LOOKS_CUSTOM, null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { LookPreset.fromJson(it) }
        }
    }.getOrDefault(emptyList())

    private val _firstRun = MutableStateFlow(prefs.getInt(K_RUNS, 0) == 0)
    val firstRun = _firstRun

    fun setBooster(on: Boolean) = prefs.edit().putBoolean(K_BOOSTER, on).apply().also {
        _boosterOn.value = on
    }

    fun setOverlay(on: Boolean) = prefs.edit().putBoolean(K_OVERLAY, on).apply().also {
        _overlayOn.value = on
    }

    fun setAutoTrim(on: Boolean) = prefs.edit().putBoolean(K_AUTOTRIM, on).apply().also {
        _autoTrim.value = on
    }

    fun setPreset(p: Preset) = prefs.edit().putString(K_PRESET, p.key).apply().also {
        _preset.value = p
    }

    fun setForceRefresh(on: Boolean) = prefs.edit().putBoolean(K_FORCE_REFRESH, on).apply().also {
        _forceRefresh.value = on
    }

    fun setKeepAwake(on: Boolean) = prefs.edit().putBoolean(K_KEEP_AWAKE, on).apply().also {
        _keepAwake.value = on
    }

    fun setCpuWakeLock(on: Boolean) = prefs.edit().putBoolean(K_CPU_WAKELOCK, on).apply().also {
        _cpuWakeLock.value = on
    }

    fun setAggressiveTrim(on: Boolean) = prefs.edit().putBoolean(K_AGGRESSIVE_TRIM, on).apply().also {
        _aggressiveTrim.value = on
    }

    fun setTintDepth(v: Int) = prefs.edit().putInt(K_TINT_DEPTH, v.coerceIn(0, 40)).apply().also {
        _tintDepth.value = v.coerceIn(0, 40)
    }

    fun setTintWarmth(v: Int) = prefs.edit().putInt(K_TINT_WARMTH, v.coerceIn(-60, 60)).apply().also {
        _tintWarmth.value = v.coerceIn(-60, 60)
    }

    fun markRun() {
        prefs.edit().putInt(K_RUNS, prefs.getInt(K_RUNS, 0) + 1).apply()
        _firstRun.value = false
    }

    companion object {
        private const val K_BOOSTER = "booster_on"
        private const val K_OVERLAY = "overlay_on"
        private const val K_AUTOTRIM = "auto_trim"
        private const val K_PRESET = "preset"
        private const val K_RUNS = "runs"
        private const val K_FORCE_REFRESH = "force_refresh"
        private const val K_KEEP_AWAKE = "keep_awake"
        private const val K_CPU_WAKELOCK = "cpu_wakelock"
        private const val K_AGGRESSIVE_TRIM = "aggressive_trim"
        private const val K_TINT_DEPTH = "tint_depth"
        private const val K_TINT_WARMTH = "tint_warmth"
        private const val K_LOOK = "look_key"
        private const val K_LOOKS_CUSTOM = "looks_custom"
        private const val K_PANEL = "panel_type"
        private const val K_BRIGHT_BOOST = "brightness_boost"
        @Volatile private var instance: SettingsStore? = null

        fun get(): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(Ctx.get()).also { instance = it }
        }
    }
}
