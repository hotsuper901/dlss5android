package com.msj.gfx.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the running-process list instead of using a UsageStats permission.
 * Polling is the right call here: UsageStats needs the user to dig through
 * Settings > Special access, and a foreground service already has a 1Hz budget
 * we can spend without any prompt at all.
 */
class GameWatcher(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 2000L
) {
    private var job: Job? = null

    private val _current = MutableStateFlow<GameCatalog.Game?>(null)
    val current = _current

    private val _launches = MutableStateFlow(0)
    val launches = _launches

    /** Called on the first transition from nothing -> a game. */
    var onGameLaunched: ((GameCatalog.Game) -> Unit)? = null
    var onGameExited: ((GameCatalog.Game) -> Unit)? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            var previous: GameCatalog.Game? = null
            while (isActive) {
                val now = runCatching { MemoryTools.foregroundGame() }.getOrNull()

                if (now?.packageName != previous?.packageName) {
                    previous?.let { onGameExited?.invoke(it) }
                    now?.let {
                        _launches.value = _launches.value + 1
                        onGameLaunched?.invoke(it)
                    }
                }
                _current.value = now
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _current.value = null
    }

    fun scope(): CoroutineScope = scope
}
