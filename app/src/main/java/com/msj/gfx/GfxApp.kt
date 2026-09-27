package com.msj.gfx

import android.app.Application
import com.msj.gfx.core.Ctx
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfMonitor
import com.msj.gfx.core.SettingsStore

class GfxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ctx.install(this)
        // Touch the thermal + package APIs once so the first real sample is warm
        // and the first frame of the dashboard is not the slow one.
        PerfMonitor.primeCpu()
        runCatching { PerfMonitor.cpuTempZone() }
        runCatching { SettingsStore.get() }
        runCatching { MemoryTools.freeRamMb() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) runCatching { MemoryTools.trim() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { MemoryTools.trim() }
    }
}
