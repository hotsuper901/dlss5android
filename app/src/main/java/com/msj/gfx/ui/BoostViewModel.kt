package com.msj.gfx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.GameWatcher
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfMonitor
import com.msj.gfx.core.PerfSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    private var sampleJob: Job? = null
    private var boostJob: Job? = null
    private var watcher: GameWatcher? = null

    init {
        PerfMonitor.primeCpu()
        sampleJob = viewModelScope.launch {
            while (isActive) {
                runCatching { PerfMonitor.sample() }.onSuccess { _perf.value = it }
                delay(1000)
            }
        }
        startWatcher()
    }

    private fun startWatcher() {
        val w = GameWatcher(viewModelScope)
        watcher = w
        viewModelScope.launch {
            w.state.collect { _watchState.value = it }
        }
        viewModelScope.launch {
            w.current.collect { _detected.value = it }
        }
        w.start()
    }

    /**
     * The press actually does three things and then says which of them landed,
     * because "reclaimed 0 MB" with no explanation is what made this button
     * look broken in the first place.
     */
    fun boost() {
        if (_boosting.value || boostJob?.isActive == true) return
        _boosting.value = true
        boostJob = viewModelScope.launch {
            try {
                val result = runCatching { MemoryTools.trim() }.getOrNull()
                if (result == null) {
                    _log.value = "Trim failed - the platform refused the request"
                } else {
                    _lastResult.value = result

                    val detectedGame = _detected.value
                    val target = detectedGame?.let { g ->
                        val free = MemoryTools.freeRamMb()
                        if (free >= g.minFreeRamMb) null
                        else "${g.label} wants ~${g.minFreeRamMb} MB free, you have $free MB - close apps"
                    }

                    _log.value = listOfNotNull(
                        result.note,
                        target
                    ).joinToString(". ")
                }
            } finally {
                delay(1100)
                _boosting.value = false
            }
        }
    }

    /** User tapped the retry/refresh on the detection banner. */
    fun resyncDetection() {
        PerfMonitor.primeCpu()
        watcher?.start()
        _log.value = if (MemoryTools.hasUsageAccess())
            "Detection resynced"
        else
            "Grant Usage access in Settings to detect the running game"
    }

    override fun onCleared() {
        sampleJob?.cancel()
        boostJob?.cancel()
        watcher?.stop()
        super.onCleared()
    }
}
