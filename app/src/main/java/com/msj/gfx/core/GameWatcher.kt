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

    private val _launches = MutableStateFlow(0)
    val launches = _launches

    private var previous: GameCatalog.Game? = null

    var onGameLaunched: ((GameCatalog.Game) -> Unit)? = null
    var onGameExited: ((GameCatalog.Game) -> Unit)? = null

    fun start() {
        if (job?.isActive == true) return
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
            _current.value = null
            _foregroundPkg.value = null
            return
        }

        val pkg = MemoryTools.foregroundPackage()
        _foregroundPkg.value = pkg
        val game = GameCatalog.match(pkg)

        if (game?.packageName != previous?.packageName) {
            previous?.let { onGameExited?.invoke(it) }
            if (game != null) {
                _launches.value = _launches.value + 1
                onGameLaunched?.invoke(game)
            }
        }

        previous = game
        _current.value = game
        _state.value = if (game != null) State.IN_GAME else State.IDLE
    }

    fun stop() {
        job?.cancel()
        job = null
        _current.value = null
        _state.value = State.UNKNOWN_NO_PERMISSION
    }
}
