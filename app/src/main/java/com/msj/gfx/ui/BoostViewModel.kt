package com.msj.gfx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.GameWatcher
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfMonitor
import com.msj.gfx.core.PerfSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BoostViewModel : ViewModel() {

    private val _perf = MutableStateFlow(PerfSnapshot())
    val perf = _perf.asStateFlow()

    private val _boosting = MutableStateFlow(false)
    val boosting = _boosting.asStateFlow()

    private val _lastResult = MutableStateFlow<MemoryTools.TrimResult?>(null)
    val lastResult = _lastResult.asStateFlow()

    private val _log = MutableStateFlow("")
    val log = _log.asStateFlow()

    private val _detected = MutableStateFlow<GameCatalog.Game?>(null)
    val detected = _detected.asStateFlow()

    private val _watchState = MutableStateFlow(GameWatcher.State.UNKNOWN_NO_PERMISSION)
    val watchState = _watchState.asStateFlow()

    private val _usageGranted = MutableStateFlow(false)
    val usageGranted = _usageGranted.asStateFlow()

    private val _installed = MutableStateFlow<List<GameCatalog.Game>>(emptyList())
    val installed = _installed.asStateFlow()

    private val _unmatched = MutableStateFlow<List<GameCatalog.Installed>>(emptyList())
    val unmatched = _unmatched.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _launchableCount = MutableStateFlow(0)
    val launchableCount = _launchableCount.asStateFlow()

    private var sampleJob: Job? = null
    private var boostJob: Job? = null
    private var permJob: Job? = null
    private var watcher: GameWatcher? = null

    init {
        // Default would be Dispatchers.Main.immediate, which put gc() and the
        // binder calls on the UI thread. That is what produced the ANR.
        sampleJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { PerfMonitor.sample() }.onSuccess { _perf.value = it }
                delay(1000)
            }
        }
        refreshPermissions()
        startWatcher()
        rescanInstalled()
    }

    /**
     * Re-reads the installed titles. Runs on Dispatchers.Default because
     * queryIntentActivities is a binder call, and the previous version of this
     * did it inside a composable via remember{}, which both blocked the UI
     * thread and latched the first (possibly empty) answer forever.
     */
    fun rescanInstalled() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val all = GameCatalog.launchableApps()
                _launchableCount.value = all.size
                _installed.value = GameCatalog.installed()
                // Anything game-shaped that is not in the catalog. This is how
                // an unrecognised build gets identified - by its real package
                // name, read off this list, instead of a guess.
                val known = _installed.value.map { it.packageName }.toSet()
                _unmatched.value = GameCatalog.gameLikePackages()
                    .filterNot { it.packageName in known }
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun startWatcher() {
        val w = GameWatcher(viewModelScope)
        watcher = w
        viewModelScope.launch { w.state.collect { _watchState.value = it } }
        viewModelScope.launch { w.current.collect { _detected.value = it } }
        w.start()
    }

    private fun refreshPermissions() {
        permJob?.cancel()
        permJob = viewModelScope.launch(Dispatchers.Default) {
            _usageGranted.value = MemoryTools.hasUsageAccess()
            watcher?.start()
        }
    }

    /**
     * Runs entirely off the main thread and reports exactly what happened.
     * "Reclaimed 0 MB" with no explanation is what made this look broken.
     */
    fun boost() {
        if (_boosting.value || boostJob?.isActive == true) return
        _boosting.value = true
        boostJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = runCatching { MemoryTools.trim() }.getOrNull()
                if (result == null) {
                    _log.value = "Trim failed - the platform refused the request"
                } else {
                    _lastResult.value = result
                    val game = _detected.value
                    val advice = game?.let { g ->
                        val free = MemoryTools.freeRamMb()
                        if (free < g.minFreeRamMb)
                            "${g.label} wants ~${g.minFreeRamMb} MB free, you have $free MB - close apps"
                        else null
                    }
                    _log.value = listOfNotNull(result.note, advice).joinToString(". ")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    delay(900)
                    _boosting.value = false
                }
            }
        }
    }

    /** User tapped RESCAN. */
    fun resyncDetection() {
        refreshPermissions()
        _log.value = "Rescanned - detection updates while a game is on screen"
    }

    override fun onCleared() {
        sampleJob?.cancel()
        boostJob?.cancel()
        permJob?.cancel()
        watcher?.stop()
        super.onCleared()
    }
}
