package com.msj.gfx.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Only restarts the booster if the user had it on when the phone rebooted.
 * Doing this unconditionally is the single most annoying thing these apps do.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Ctx.install(context)
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val settings = SettingsStore.get()
        runCatching { settings.markRun() }

        if (settings.boosterOn.value) {
            val result = goAsync()
            Thread {
                runCatching {
                    // Give the framework a moment to finish BOOT_COMPLETED
                    // broadcast processing before we ask for a foreground slot.
                    Thread.sleep(2500)
                    CoolerService.start(context)
                }
                runCatching { result.finish() }
            }.start()
        }
    }
}
