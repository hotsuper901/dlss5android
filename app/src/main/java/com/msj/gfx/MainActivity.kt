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
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.GameOverlayService
import com.msj.gfx.core.MemoryTools
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
        val usage: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsStore.get().markRun()
        perms = Perms(overlay = overlayGrantedNow(), usage = false)

        setContent {
            MsjTheme {
                val boosterOn by SettingsStore.get().boosterOn.collectAsState()
                val p = perms

                MsjRoot(
                    overlayGranted = p.overlay,
                    usageGranted = p.usage,
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
        // hasUsageAccess() is a binder call plus a cursor walk, so it goes to a
        // background thread - doing it inline here is launch-time jank.
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.Default) { MemoryTools.hasUsageAccess() }
            perms = Perms(overlay = perms.overlay, usage = usage)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && perms.overlay) {
            if (SettingsStore.get().overlayOn.value) GameOverlayService.start(this)
        }
    }

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
