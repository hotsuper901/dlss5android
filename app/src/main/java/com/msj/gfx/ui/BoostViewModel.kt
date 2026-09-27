package com.msj.gfx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val _freedMb = MutableStateFlow(0)
    val freedMb = _freedMb.asStateFlow()

    private val _log = MutableStateFlow("")
    val log = _log.asStateFlow()

    private var sampleJob: Job? = null
    private var boostJob: Job? = null

    init {
        PerfMonitor.primeCpu()
        sampleJob = viewModelScope.launch {
            while (isActive) {
                // Never let a bad read stop the dashboard.
                runCatching { PerfMonitor.sample() }.onSuccess { _perf.value = it }
                delay(1000)
            }
        }
    }

    fun boost() {
        if (_boosting.value || boostJob?.isActive == true) return
        _boosting.value = true
        boostJob = viewModelScope.launch {
            try {
                var freed = 0
                repeat(3) { i ->
                    val r = runCatching { MemoryTools.trim() }.getOrNull()
                    if (r != null) freed += r.reclaimedMb
                    delay(320)
                }
                _freedMb.value += freed
                val free = MemoryTools.freeRamMb()
                _log.value = if (freed > 0)
                    "Reclaimed ~$freed MB - $free MB free for the game"
                else
                    "Memory was already clean - $free MB free"
            } finally {
                delay(800)
                _boosting.value = false
            }
        }
    }

    override fun onCleared() {
        sampleJob?.cancel()
        boostJob?.cancel()
        super.onCleared()
    }
}
