package com.msj.gfx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    }

    /** Hand the display back and stop trimming, so we do not drain a battery. */
    private fun releaseEnhancePolicy() {
        if (!applied) return
        applied = false
        if (SettingsStore.get().forceRefresh.value) {
            runCatching { DisplayController.releasePeakRefresh() }
        }
    }

    companion object {
        /** Last detection result from any live watcher, for the UI. */
        fun lastHit(): GameDetector.Hit? = GameDetector.lastKnown()
    }

    fun stop() {
        releaseEnhancePolicy()
        job?.cancel()
        job = null
        previousPkg = null
        previous = null
        _current.value = null
        _state.value = State.UNKNOWN_NO_PERMISSION
    }
}
