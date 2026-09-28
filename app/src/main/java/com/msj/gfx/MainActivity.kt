package com.msj.gfx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.msj.gfx.core.CoolerService
import com.msj.gfx.core.DisplayController
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.GameOverlayService
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PowerKeeper
import com.msj.gfx.core.SettingsStore
import com.msj.gfx.ui.MsjRoot
import com.msj.gfx.ui.MsjTheme

class MainActivity : ComponentActivity() {

    /**
     * Both special permissions are granted in Settings, which does not reliably
     * hand back a result code. So the values are re-read from onResume through
     * a lifecycle-aware state holder rather than trusted from a callback.
     */
    private var perms by mutableStateOf(Perms())

    private data class Perms(
        val overlay: Boolean = false,
        val usage: Boolean = false,
        /** Battery-optimisation exemption: a system state, re-read in onResume. */
        val batteryExempt: Boolean = false,
        /** "Modify system settings" (WRITE_SETTINGS): another special access. */
        val writeSettings: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsStore.get().markRun()
        perms = Perms(
            overlay = overlayGrantedNow(),
            usage = false,
            // One binder call at launch, same class of check as
            // canDrawOverlays above. Live values are re-read in onResume.
            batteryExempt = PowerKeeper.isBatteryExempt(),
            writeSettings = DisplayController.canWriteSettings()
        )

        setContent {
            MsjTheme {
                val boosterOn by SettingsStore.get().boosterOn.collectAsState()
                val p = perms

                MsjRoot(
                    overlayGranted = p.overlay,
                    usageGranted = p.usage,
                    batteryExempt = p.batteryExempt,
                    writeSettings = p.writeSettings,
                    onRequestOverlay = ::requestOverlay,
                    onRequestUsageAccess = {
                        runCatching { startActivity(GameCatalog.usageAccessIntent()) }
                            .onFailure {
                                runCatching {
                                    startActivity(
                                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    )
                                }
                            }
                    },
                    onRequestBatteryExemption = { PowerKeeper.requestExemption() },
                    onOpenOemAutostart = { PowerKeeper.openOemAutostart() },
                    onToggleBooster = { on ->
                        SettingsStore.get().setBooster(on)
                        if (on) CoolerService.start(this) else CoolerService.stop(this)
                    },
                    onToggleOverlay = { on ->
                        SettingsStore.get().setOverlay(on)
                        if (on) {
                            if (!p.overlay) requestOverlay() else GameOverlayService.start(this)
                        } else {
                            GameOverlayService.stop(this)
                        }
                    },
                    onOpenGame = { pkg ->
                        runCatching {
                            startActivity(
                                packageManager.getLaunchIntentForPackage(pkg)
                                    ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", pkg, null))
                            )
                        }
                    }
                )

                if (boosterOn) {
                    LaunchedEffect(Unit) { CoolerService.start(this@MainActivity) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user just came back from Settings, possibly with new grants.
        // Every special access this app asks for is re-read here, overlay
        // included. Only onCreate used to read canDrawOverlays, so granting
        // "Display over other apps" while the app was alive stayed invisible
        // until the process was restarted - reported as "must force close the
        // app before it detects the permission".
        lifecycleScope.launch {
            // Binder calls and a cursor walk; never on the main thread.
            val overlay = withContext(Dispatchers.Default) { overlayGrantedNow() }
            val usage = withContext(Dispatchers.Default) { MemoryTools.hasUsageAccess() }
            val exempt = withContext(Dispatchers.Default) { PowerKeeper.isBatteryExempt() }
            val write = withContext(Dispatchers.Default) { DisplayController.canWriteSettings() }
            perms = Perms(
                overlay = overlay,
                usage = usage,
                batteryExempt = exempt,
                writeSettings = write
            )

            // If the HUD toggle was stored as on before the user left for
            // Settings, the arriving grant is the missing half - bring the
            // service up now instead of making them flip the switch again.
            if (overlay && SettingsStore.get().overlayOn.value) {
                GameOverlayService.start(this@MainActivity)
            }
        }
    }

    private fun overlayGrantedNow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // No result contract on purpose: ACTION_MANAGE_OVERLAY_PERMISSION does
        // not report back a usable result, so onResume re-reads canDrawOverlays
        // instead of trusting a callback that may lie.
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
