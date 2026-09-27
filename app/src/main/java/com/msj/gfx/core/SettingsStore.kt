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
        @Volatile private var instance: SettingsStore? = null

        fun get(): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(Ctx.get()).also { instance = it }
        }
    }
}
