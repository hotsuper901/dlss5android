package com.msj.gfx

import android.app.Application
import android.os.HandlerThread
import com.msj.gfx.core.Ctx
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfMonitor
import com.msj.gfx.core.SettingsStore

class GfxApp : Application() {

    /**
     * Everything that touches the binder or the filesystem runs here, never on
     * the main thread. Runtime.gc() in particular is fast enough to look like a
     * hang on a loaded phone, and doing it from onLowMemory - which is itself
     * delivered on the main thread - is a straight path to an ANR.
     */
    private val worker = HandlerThread("msj-gfx-worker").apply { start() }
    private val handler = android.os.Handler(worker.looper)

    override fun onCreate() {
        super.onCreate()
        Ctx.install(this)
        runCatching { SettingsStore.get() }

        // Warm the thermal and memory reads so the first dashboard frame is not
        // also the slowest one.
        handler.post {
            runCatching { PerfMonitor.cpuTempZone() }
            runCatching { MemoryTools.freeRamMb() }
            runCatching { MemoryTools.totalRamMb() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) postTrim()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        postTrim()
    }

    private fun postTrim() {
        handler.post { runCatching { MemoryTools.trim() } }
    }

    override fun onTerminate() {
        worker.quitSafely()
        super.onTerminate()
    }
}
