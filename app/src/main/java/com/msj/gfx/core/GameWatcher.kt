package com.msj.gfx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-app watcher.
 *
 * Polling UsageStats is the only thing that works on Android 5.1+: a normal app
 * cannot enumerate other processes any more, so the earlier
 * ActivityManager.getRunningAppProcesses() approach could never have detected
 * Free Fire - it returned exactly one entry, our own.
 *
 * When Usage access has not been granted we do not fake a result. We report
 * unknown and say why, because a detector that silently always says "no game"
 * is worse than no detector at all.
 */
class GameWatcher(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1200L
) {
    private var job: Job? = null
    private var lookJob: Job? = null
    private var boostJob: Job? = null

    enum class State { UNKNOWN_NO_PERMISSION, IDLE, IN_GAME }

    private val _state = MutableStateFlow(State.UNKNOWN_NO_PERMISSION)
    val state = _state

    private val _current = MutableStateFlow<GameCatalog.Game?>(null)
    val current = _current

    private val _foregroundPkg = MutableStateFlow<String?>(null)
    val foregroundPkg = _foregroundPkg

    /** The full detection result, including unrecognised-but-game-shaped builds. */
    private val _hit = MutableStateFlow<GameDetector.Hit?>(null)
    val hit = _hit

    private val _launches = MutableStateFlow(0)
    val launches = _launches

    private var applied = false

    /**
     * What was actually pushed, not what the store currently says.
     *
     * Teardown used to re-read the selected look, which meant that changing the
     * look while sitting inside a game left a tint with nobody willing to turn
     * it off. Tracking the applied look makes teardown symmetric with apply
     * even if the selection moves underneath us.
     */
    private var appliedLook: LookPreset? = null

    /**
     * Brightness as found before we raised it, plus whether we raised it at all.
     *
     * This is tracked separately from [appliedLook] on purpose. Brightness used
     * to be a side effect of choosing a look, which meant tapping a preset
     * forced manual brightness mode and flashed the screen - reported as
     * "flashlight screen when select preset when game on". It is now its own
     * explicit action, and this field is what lets it be undone.
     */
    private var brightnessSnap: DisplayController.BrightnessSnapshot? = null
    private var brightnessApplied = false
    private var previous: GameCatalog.Game? = null
    private var previousPkg: String? = null

    var onGameLaunched: ((GameCatalog.Game) -> Unit)? = null
    var onGameExited: ((GameCatalog.Game) -> Unit)? = null

    fun start() {
        if (job?.isActive == true) return
        // Rehydrate so a restarted service does not show a blank HUD.
        runCatching { GameDetector.restore() }
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { tick() }
                delay(intervalMs)
            }
        }
        // Re-apply when the selection changes while a game is already open.
        // applyEnhancePolicy() is guarded by `applied`, so a look picked from
        // the UI mid-session would otherwise sit there doing nothing until the
        // next game launch. Collect the flow and push immediately instead.
        lookJob = scope.launch(Dispatchers.Default) {
            runCatching {
                SettingsStore.get().look.collect { _ ->
                    if (applied) applyLook()
                }
            }

        // The brightness slider is the only thing that may move luminance, and
        // only because the user moved it.
        boostJob = scope.launch(Dispatchers.Default) {
            runCatching {
                SettingsStore.get().brightnessBoost.collect { _ ->
                    if (applied) applyBrightnessBoost()
                }
            }
        }
        }
    }

    private fun tick() {
        if (!MemoryTools.hasUsageAccess()) {
            _state.value = State.UNKNOWN_NO_PERMISSION
            if (previous != null) {
                previous?.let { onGameExited?.invoke(it) }
                previous = null
            }
            previousPkg = null
            _current.value = null
            _foregroundPkg.value = null
            _hit.value = null
            return
        }

        val result = GameDetector.poll()
        _hit.value = result
        _foregroundPkg.value = result?.packageName
        val game = result?.game

        // Transition on the package, not on the preset. An unrecognised but
        // game-shaped build has game == null, and keying off the preset meant
        // the launch counter never moved for exactly the builds that needed it.
        val pkg = result?.packageName
        if (pkg != null && pkg != previousPkg) {
            previous?.let { onGameExited?.invoke(it) }
            _launches.value = _launches.value + 1
            game?.let { onGameLaunched?.invoke(it) }
        }
        if (pkg == null) previous?.let { onGameExited?.invoke(it) }

        previousPkg = pkg
        previous = game
        _current.value = game
        _state.value = if (result != null) State.IN_GAME else State.IDLE

        if (result != null) applyEnhancePolicy() else releaseEnhancePolicy()
    }

    /**
     * The display and memory side of the Graphics Enhancer, applied on entry.
     *
     * Everything here is a control the OS actually exposes to us - peak
     * refresh rate and trim aggressiveness. It is deliberately not a graphics
     * patch: the in-game values live in the game's own process and are
     * surfaced to the player as a checklist instead.
     */
    private fun applyEnhancePolicy() {
        if (applied) return
        applied = true
        val store = SettingsStore.get()
        if (store.forceRefresh.value) {
            DisplayController.maxRefreshHz()?.let { DisplayController.setPeakRefresh(it) }
        }
        if (store.aggressiveTrim.value) {
            runCatching { MemoryTools.trim() }
        }
        applyLook()
        // Boost, if the user asked for one, comes up on game entry - this is
        // an explicit setting, unlike luminance as a side effect of a look.
        applyBrightnessBoost()
    }

    /**
     * The "it just works on launch" path.
     *
     * Runs on the watcher's background scope the moment a tracked game reaches
     * the foreground: pushes the selected look's tint over the game, asks the
     * display for its refresh target, and requests the OEM vivid profile where
     * the OEM has one. Everything is best-effort and wrapped, because on a
     * device without the overlay grant or without WRITE_SETTINGS a failure here
     * must never take the watcher down or stop it watching.
     */
    private fun applyLook() {
        val store = SettingsStore.get()
        val look = store.look.value
        // Re-entering the same look should not restart the service, but a
        // different look must replace the running one cleanly.
        if (appliedLook?.key == look.key) return

        scope.launch {
            // Switching to Off has to actively clear, not just do nothing.
            // The old early-return meant picking Off while a tint was running
            // left that tint up until the user left the game.
            if (look.key == "off") {
                clearLook()
                return@launch
            }

            val ctx = Ctx.get()

            // Rescale for the panel before anything is applied. A depth that
            // looks right on OLED is mud on an LCD, so this is the difference
            // between a look that suits the phone and a slider someone else set.
            val panel = DisplayProfile.read(ctx, store.panelIsOled.value)
            val fitted = look.adaptTo(panel)

            // Overlay tint: depth + warmth over the game's own frame.
            if (fitted.needsOverlay) {
                runCatching {
                    if (android.provider.Settings.canDrawOverlays(ctx)) {
                        ColorOverlayService.start(ctx, fitted.depth, fitted.warmth)
                    }
                }
            }
            // Refresh target, if this look asks for one and the panel supports it.
            if (fitted.refreshHz > 0) {
                runCatching {
                    val max = DisplayController.maxRefreshHz()
                    if (max != null && fitted.refreshHz <= max) {
                        DisplayController.setPeakRefresh(fitted.refreshHz.toFloat())
                    }
                }
            }
            // OEM colour profile: hardware, downstream of the game, so this is
            // a genuine colour re-map rather than a tint.
            if (fitted.oemVivid) {
                runCatching { DisplayController.setOemVividMode(true) }
            }
            appliedLook = fitted
        }
    }

    /** Undo exactly one look, used both by Off and by teardown. */
    private fun clearLook() {
        val was = appliedLook
        appliedLook = null
        if (was == null) return
        runCatching { ColorOverlayService.stop(Ctx.get()) }
        if (was.refreshHz > 0) runCatching { DisplayController.releasePeakRefresh() }
        if (was.oemVivid) runCatching { DisplayController.setOemVividMode(false) }
    }

    /**
     * Apply or undo the brightness headroom boost.
     *
     * Only ever called from the brightness slider, never from preset selection:
     * a colour change must not move the panel's luminance. Safe to call
     * repeatedly - the write is idempotent and capped, and moving the slider to
     * zero restores the captured snapshot rather than forcing automatic mode on
     * a user who had deliberately set brightness by hand.
     */
    fun applyBrightnessBoost() {
        if (!applied) return
        val boost = SettingsStore.get().brightnessBoost.value
        if (boost <= 0) {
            releaseBrightness()
            return
        }
        runCatching {
            // Capture before the first raise so restore has something real.
            if (!brightnessApplied) brightnessSnap = DisplayController.captureBrightness()
            // Pass the captured baseline every time. Re-reading the live value
            // here is what made repeated preset taps ratchet brightness up.
            val baseline = brightnessSnap?.value
            if (DisplayController.setBrightnessHeadroom(boost, baseline)) brightnessApplied = true
        }
    }

    /** Hand brightness back exactly as it was found. */
    fun releaseBrightness() {
        if (!brightnessApplied) return
        brightnessApplied = false
        val snap = brightnessSnap
        brightnessSnap = null
        runCatching { DisplayController.restoreBrightness(snap) }
    }

    /** Hand the display back and stop trimming, so we do not drain a battery. */
    private fun releaseEnhancePolicy() {
        if (!applied) return
        applied = false
        val store = SettingsStore.get()
        if (store.forceRefresh.value) {
            runCatching { DisplayController.releasePeakRefresh() }
        }
        // Put the screen back the way we found it. Leaving a tint over whatever
        // app the user opens next is the kind of thing that gets an app removed.
        clearLook()
        // Unconditional, and not gated on the boost still being non-zero: if
        // the user zeroed the slider mid-game the old check skipped the restore
        // and left the panel stuck in forced manual brightness.
        releaseBrightness()
    }

    companion object {
        /** Last detection result from any live watcher, for the UI. */
        fun lastHit(): GameDetector.Hit? = GameDetector.lastKnown()
    }

    fun stop() {
        releaseEnhancePolicy()
        job?.cancel()
        job = null
        lookJob?.cancel()
        lookJob = null
        boostJob?.cancel()
        boostJob = null
        previousPkg = null
        previous = null
        _current.value = null
        _state.value = State.UNKNOWN_NO_PERMISSION
    }
}
