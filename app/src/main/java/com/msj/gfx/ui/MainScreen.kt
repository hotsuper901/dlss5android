package com.msj.gfx.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msj.gfx.core.ColorOverlayService
import com.msj.gfx.core.DisplayProfile
import com.msj.gfx.core.DisplayController
import com.msj.gfx.core.LookPreset
import com.msj.gfx.core.LookPresets
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.GraphicsEnhancer
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfSnapshot
import com.msj.gfx.core.Preset
import com.msj.gfx.core.Presets
import com.msj.gfx.core.SettingsStore
import java.io.File
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: ImageVector) {
    DASH("Boost", Icons.Filled.Bolt),
    GAMES("Games", Icons.Filled.Tune),
    ENHANCE("Enhance", Icons.Filled.AutoAwesome),
    HUD("HUD", Icons.Filled.Layers),
    ABOUT("About", Icons.Filled.Memory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsjRoot(
    overlayGranted: Boolean,
    usageGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onToggleBooster: (Boolean) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onOpenGame: (String) -> Unit
) {
    val settings = remember { SettingsStore.get() }
    val boosterOn by settings.boosterOn.collectAsState()
    val overlayOn by settings.overlayOn.collectAsState()
    val autoTrim by settings.autoTrim.collectAsState()
    val preset by settings.preset.collectAsState()
    val forceRefresh by settings.forceRefresh.collectAsState()
    val keepAwake by settings.keepAwake.collectAsState()
    val aggressiveTrim by settings.aggressiveTrim.collectAsState()
    val tintDepth by settings.tintDepth.collectAsState()
    val tintWarmth by settings.tintWarmth.collectAsState()
    val look by settings.look.collectAsState()
    val importedLooks by settings.importedLooks.collectAsState()
    val panelIsOled by settings.panelIsOled.collectAsState()
    val brightnessBoost by settings.brightnessBoost.collectAsState()
    val vm = remember { BoostViewModel() }
    val perf by vm.perf.collectAsState()
    val boosting by vm.boosting.collectAsState()
    val log by vm.log.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(log) {
        if (log.isNotBlank()) snack.showSnackbar(log)
    }

    Scaffold(
        containerColor = DeepBg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            indicatorColor = PanelHi,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.DASH -> DashTab(
                    perf = perf, boosting = boosting, boosterOn = boosterOn,
                    autoTrim = autoTrim, preset = preset,
                    detected = vm.detected.collectAsState().value,
                    hit = vm.hit.collectAsState().value,
                    watchState = vm.watchState.collectAsState().value,
                    usageGranted = usageGranted,
                    diag = vm.diag.collectAsState().value,
                    onRequestUsageAccess = onRequestUsageAccess,
                    onBoost = vm::boost,
                    onResync = vm::resyncDetection,
                    onToggleBooster = onToggleBooster,
                    onPreset = settings::setPreset,
                    onToggleAutoTrim = settings::setAutoTrim,
                    forceRefresh = forceRefresh,
                    keepAwake = keepAwake,
                    aggressiveTrim = aggressiveTrim,
                    onToggleForceRefresh = settings::setForceRefresh,
                    onToggleKeepAwake = settings::setKeepAwake,
                    onToggleAggressiveTrim = settings::setAggressiveTrim
                )
                Tab.GAMES -> GamesTab(vm, onOpenGame)
                Tab.ENHANCE -> EnhanceTab(
                    overlayGranted = overlayGranted,
                    onRequestOverlay = onRequestOverlay,
                    depth = tintDepth,
                    warmth = tintWarmth,
                    onDepth = settings::setTintDepth,
                    onWarmth = settings::setTintWarmth,
                    look = look,
                    imported = importedLooks,
                    onLook = settings::setLook,
                    onDepthForLook = { d, w ->
                        settings.setTintDepth(d)
                        settings.setTintWarmth(w)
                    },
                    onImported = settings::setImportedLooks,
                    panelIsOled = panelIsOled,
                    brightnessBoost = brightnessBoost,
                    onPanel = settings::setPanelIsOled,
                    onBrightness = settings::setBrightnessBoost
                )
                Tab.HUD -> HudTab(
                    overlayOn = overlayOn, overlayGranted = overlayGranted,
                    onRequestOverlay = onRequestOverlay,
                    onToggleOverlay = onToggleOverlay
                )
                Tab.ABOUT -> AboutTab()
            }
        }
    }
}

/* ------------------------------ DASH ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashTab(
    perf: PerfSnapshot, boosting: Boolean, boosterOn: Boolean, autoTrim: Boolean,
    preset: Preset,
    detected: GameCatalog.Game?,
    hit: com.msj.gfx.core.GameDetector.Hit?,
    watchState: com.msj.gfx.core.GameWatcher.State,
    usageGranted: Boolean,
    diag: String,
    onRequestUsageAccess: () -> Unit,
    onBoost: () -> Unit, onResync: () -> Unit,
    onToggleBooster: (Boolean) -> Unit,
    onPreset: (Preset) -> Unit, onToggleAutoTrim: (Boolean) -> Unit,
    forceRefresh: Boolean, keepAwake: Boolean, aggressiveTrim: Boolean,
    onToggleForceRefresh: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onToggleAggressiveTrim: (Boolean) -> Unit
) {
    // Special access, not a runtime permission, and the peak-refresh override
    // silently no-ops without it - so surface the state rather than pretending.
    var canWrite by remember { mutableStateOf(DisplayController.canWriteSettings()) }
    val maxHz = remember { DisplayController.maxRefreshHz() }
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        Header()

        Spacer(Modifier.height(16.dp))
        DetectionCard(
            detected, hit, watchState, usageGranted, perf.freeRamMb, perf.lowMemory,
            diag, onRequestUsageAccess, onResync
        )

        Spacer(Modifier.height(16.dp))
        DeviceRamBar(perf.freeRamMb, perf.deviceRamPct, perf.lowMemory)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                Icons.Filled.Thermostat, "TEMP",
                perf.hottestC?.let { "${it.roundToInt()}\u00b0" } ?: "--",
                Modifier.weight(1f),
                alert = perf.throttling
            )
            StatTile(
                Icons.Filled.Memory, "FREE",
                "${perf.freeRamMb}M", Modifier.weight(1f),
                alert = perf.lowMemory
            )
            StatTile(
                Icons.Filled.DeveloperBoard, "SOC",
                perf.soc.take(7), Modifier.weight(1f)
            )
        }

        if (perf.throttling) {
            Spacer(Modifier.height(12.dp))
            Notice(
                "Thermal throttling detected (${perf.hottestC?.roundToInt() ?: 0}\u00b0C). " +
                    "The SoC is dropping below its boost clock right now - set ${preset.inGameGraphics} in-game.",
                WarnYellow
            )
        }

        Spacer(Modifier.height(18.dp))
        BigButton(boosting, onBoost)

        Spacer(Modifier.height(18.dp))
        SectionLabel("GRAPHICS ENHANCER")

        val activeProfile = GraphicsEnhancer.profileFor(detected)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(14.dp)
        ) {
            Text(
                if (detected != null) "PRESET FOR ${detected.label.uppercase()}"
                else "PRESET FOR DETECTED GAME",
                fontSize = 10.sp, color = NeonCyan, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileChip("RES SCALE", "${activeProfile.resolutionScalePct}%", Modifier.weight(1f))
                ProfileChip("FPS CAP", "${activeProfile.fpsCap}", Modifier.weight(1f))
                ProfileChip("SHADOWS", activeProfile.shadows, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileChip("TEXTURES", activeProfile.textures, Modifier.weight(1f))
                ProfileChip("ANTI-ALIAS", activeProfile.antiAliasing, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(GraphicsEnhancer.checklistFor(detected)))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "COPY SETTINGS CHECKLIST",
                    color = NeonCyan, fontWeight = FontWeight.Black, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "These are the values to set inside the game's own graphics menu - the " +
                    "app cannot write another app's settings, so this is the part you apply once.",
                fontSize = 10.sp, color = Muted, lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(16.dp))
        ToggleRow(
            "Force peak refresh",
            if (maxHz != null && maxHz > 60f)
                "Pins the display to ${maxHz.roundToInt()}Hz while a game is in front, " +
                    "then hands it back on exit"
            else "No high-refresh panel detected on this device",
            forceRefresh && canWrite,
            onChange = {
                if (!canWrite) {
                    DisplayController.openWriteSettings()
                } else {
                    onToggleForceRefresh(it)
                }
            }
        )
        if (!canWrite) {
            Notice(
                "Peak refresh override needs \"Modify system settings\". Tap the toggle to grant it.",
                WarnYellow
            )
        }
        ToggleRow("Keep screen awake", "Holds the display on during a match", keepAwake, onToggleKeepAwake)
        ToggleRow(
            "Aggressive trim in game",
            "Trims background RAM the moment a game comes forward, releases on exit",
            aggressiveTrim, onToggleAggressiveTrim
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("PRESET")
        Presets.ALL.forEach { p ->
            PresetRow(
                p, p.key == preset.key,
                onClick = { onPreset(p) },
                quality = MemoryTools.recommendQuality(perf.deviceRamPct, perf.hottestC, perf.charging)
            )
        }

        Spacer(Modifier.height(16.dp))
        ToggleRow("Background booster", "Keeps RAM trimmed the whole session", boosterOn, onToggleBooster)
        ToggleRow("Auto-trim on game launch", "Trims 3.5s after a match starts", autoTrim, onToggleAutoTrim)

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Creator: M.S.J",
            color = Muted, fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                "MSJ GFX",
                fontSize = 28.sp, fontWeight = FontWeight.Black,
                color = NeonCyan, letterSpacing = 1.sp
            )
            Text("Game Graphics Enhancer", fontSize = 12.sp, color = Muted)
        }
        Text("v1.0.0", fontSize = 11.sp, color = Muted)
    }
}


@Composable
private fun DetectionCard(
    detected: GameCatalog.Game?,
    hit: com.msj.gfx.core.GameDetector.Hit?,
    state: com.msj.gfx.core.GameWatcher.State,
    usageGranted: Boolean,
    freeRamMb: Int,
    lowMemory: Boolean,
    diag: String,
    onRequestUsageAccess: () -> Unit,
    onResync: () -> Unit
) {
    val accent = detected?.let { Color(it.accent) } ?: Muted
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Radar,
                null,
                tint = if (detected != null) accent else WarnYellow,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "GAME DETECTION",
                fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            if (hit != null) {
                Text(
                    "DETECTED",
                    fontSize = 9.sp, color = OkGreen, fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            !usageGranted -> {
                Text(
                    "Android 5.1 and newer stop apps from seeing which other apps are " +
                        "running. Without Usage access there is no way to detect your game, " +
                        "so we will not pretend otherwise.",
                    color = Body, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRequestUsageAccess,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "GRANT USAGE ACCESS",
                        fontSize = 12.sp, fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Then tap Rescan - Settings does not report back to us.",
                    fontSize = 10.sp, color = Muted
                )
            }

            // A game we recognised as a game but have no preset for. Shown
            // rather than hidden, because the package name is the only way to
            // add support for it properly.
            hit != null && detected == null -> {
                Text(
                    hit.label,
                    fontSize = 17.sp, fontWeight = FontWeight.Black, color = Ink
                )
                Text(
                    hit.packageName,
                    fontSize = 10.sp, color = NeonCyan, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Game detected, but we have no tuned preset for this build yet. " +
                        "The generic booster still applies.",
                    fontSize = 12.sp, color = Body, lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "$freeRamMb MB free right now.",
                    fontSize = 12.sp, color = if (lowMemory) HotAmber else OkGreen,
                    fontWeight = FontWeight.Bold
                )
            }

            detected != null -> {
                Text(
                    detected.label,
                    fontSize = 17.sp, fontWeight = FontWeight.Black, color = Ink
                )
                Text(detected.packageName, fontSize = 10.sp, color = Muted)
                // freeRamMb is the value already sampled off the main thread.
                // Querying ActivityManager inside a composable is a binder call
                // during composition, which is exactly how you get a dropped
                // frame on a phone that is already struggling.
                val free = freeRamMb
                Spacer(Modifier.height(8.dp))
                Text(
                    if (free >= detected.minFreeRamMb && !lowMemory)
                        "Healthy - $free MB free, this title wants ~${detected.minFreeRamMb} MB"
                    else if (lowMemory)
                        "Android reports low memory - $free MB free, this title wants ~${detected.minFreeRamMb} MB"
                    else
                        "Under-provisioned - $free MB free, this title wants ~${detected.minFreeRamMb} MB",
                    fontSize = 12.sp,
                    color = if (free >= detected.minFreeRamMb) OkGreen else HotAmber,
                    fontWeight = FontWeight.Bold
                )
            }

            state == com.msj.gfx.core.GameWatcher.State.IDLE -> {
                Text("No game on screen", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    diag,
                    fontSize = 11.sp, color = WarnYellow, lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "RESCAN DETECTION",
                        color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            else -> {
                Text("Waiting for a foreground app", fontSize = 13.sp, color = Body)
            }
        }

        if (usageGranted) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onResync) {
                    Text("RESCAN", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.weight(1f))
                Text("polling every 1.2s", fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun DeviceRamBar(freeMb: Int, usedPct: Int, low: Boolean) {
    val c by animateColorAsState(
        when {
            low -> HotAmber
            usedPct >= 88 -> WarnYellow
            else -> OkGreen
        }, label = "devram"
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                "DEVICE MEMORY",
                fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold
            )
            Text(
                "$freeMb MB free",
                fontSize = 11.sp,
                color = c,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(PanelHi)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((usedPct / 100f).coerceIn(0f, 1f))
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (low) "Android is reporting low memory - close apps before you queue"
            else "$usedPct% in use across all apps",
            fontSize = 10.sp,
            color = if (low) HotAmber else Muted
        )
    }
}

@Composable
private fun StatTile(
    icon: ImageVector, label: String, value: String,
    mod: Modifier, alert: Boolean = false
) {
    Column(
        mod
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, if (alert) HotAmber else Color(0xFF1F2A3D), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Icon(icon, null, tint = if (alert) HotAmber else Color(0xFF3B4A61), modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Ink, maxLines = 1)
        Text(label, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigButton(boosting: Boolean, onClick: () -> Unit) {
    val glow by animateFloatAsState(if (boosting) 1f else 0.85f, label = "glow")
    Button(
        onClick = onClick,
        enabled = !boosting,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (boosting) OkGreen else NeonCyan,
            contentColor = DeepBg
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (boosting) 12.dp else 4.dp * glow
        )
    ) {
        Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (boosting) "BOOSTING…" else "BOOST NOW",
            fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
        )
    }
}

@Composable
private fun PresetRow(p: Preset, selected: Boolean, onClick: () -> Unit, quality: String) {
    val border by animateColorAsState(
        if (selected) NeonCyan else Color(0xFF1F2A3D), label = "pb"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("${p.inGameGraphics} - ${p.frameRateTarget}", fontSize = 11.sp, color = Muted)
            if (selected) {
                Text("Recommended now: $quality", fontSize = 10.sp, color = OkGreen)
            }
        }
        if (selected) Text("✓", color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(sub, fontSize = 11.sp, color = Muted)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepBg,
                checkedTrackColor = NeonCyan,
                uncheckedTrackColor = PanelHi
            )
        )
    }
}

@Composable
private fun Notice(text: String, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text("!  ", color = tint, fontWeight = FontWeight.Black)
        Text(text, color = Body, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

/* ------------------------------ GAMES ------------------------------ */

@Composable
private fun GamesTab(vm: BoostViewModel, onOpenGame: (String) -> Unit) {
    val installed by vm.installed.collectAsState()
    val unmatched by vm.unmatched.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val launchable by vm.launchableCount.collectAsState()

    // Rescan whenever the tab is shown. A remember{} here latched the first
    // answer, so a title installed after the app launched never appeared.
    LaunchedEffect(Unit) { vm.rescanInstalled() }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SUPPORTED GAMES",
                fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$launchable launchable apps",
                    fontSize = 10.sp, color = Muted
                )
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { vm.rescanInstalled() }, enabled = !scanning) {
                    Text(
                        if (scanning) "SCANNING" else "RESCAN",
                        color = if (scanning) Muted else NeonCyan,
                        fontSize = 11.sp, fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!scanning && installed.isEmpty()) {
            Notice(
                "None of the supported package names matched. $launchable launchable apps " +
                    "were visible to this app - see the unrecognised list below for what " +
                    "your Free Fire and Mobile Legends actually report as.",
                WarnYellow
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(installed, key = { it.packageName }) { g ->
                GameCard(g, onOpenGame, installed = true)
            }
            items(
                GameCatalog.ALL.filter { g -> installed.none { it.packageName == g.packageName } },
                key = { "missing-${it.packageName}" }
            ) { g ->
                GameCard(g, onOpenGame, installed = false)
            }

            if (unmatched.isNotEmpty()) {
                item { SectionLabel("UNRECOGNISED GAME PACKAGES") }
                item {
                    Text(
                        "Found on this device but not in the catalog. If one of these is your " +
                            "Free Fire or Mobile Legends, that package name is the real one.",
                        fontSize = 11.sp, color = Muted, lineHeight = 17.sp
                    )
                }
                items(unmatched, key = { "unmatched-${it.packageName}" }) { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Panel)
                            .padding(12.dp)
                    ) {
                        Text(
                            app.label,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink
                        )
                        Text(
                            app.packageName,
                            fontSize = 10.sp,
                            color = NeonCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelHi)
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Text(label, fontSize = 8.sp, color = Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GameCard(g: GameCatalog.Game, onOpenGame: (String) -> Unit, installed: Boolean = true) {
    val accent = Color(g.accent)
    // LocalClipboardManager.current is a @Composable getter, so it has to be
    // read in composable scope - calling it inside the onClick lambda is a
    // compile error, not just untidy.
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (installed) accent else Muted)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(g.label, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Ink)
                Text(g.packageName, fontSize = 10.sp, color = Muted)
            }
            if (installed) {
                TextButton(onClick = { onOpenGame(g.packageName) }) {
                    Text("LAUNCH", color = accent, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }
            } else {
                Text("NOT INSTALLED", fontSize = 9.sp, color = Muted)
            }
        }
        val profile = GraphicsEnhancer.profileFor(g)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ProfileChip("RES", "${profile.resolutionScalePct}%", Modifier.weight(1f))
            ProfileChip("FPS", "${profile.fpsCap}", Modifier.weight(1f))
            ProfileChip("SHADOWS", profile.shadows, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(GraphicsEnhancer.checklistFor(g)))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "COPY CHECKLIST",
                color = accent, fontWeight = FontWeight.Black, fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        g.levers.forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("› ", color = accent, fontWeight = FontWeight.Black)
                Text(it, color = Body, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

/* ------------------------------ ENHANCE ------------------------------ */

/**
 * The two halves of the visual enhancer.
 *
 * Live layer: a tint over other apps. Documented in ColorOverlayService as what
 * it actually is - it changes what your eye reads, it is not a colour-matrix
 * re-map of the game's pixels, because that would need either the OEM display
 * pipeline or a screen capture.
 *
 * Offline pipeline: the desktop enhancer's stage order run on images the user
 * owns. Denoise, unsharp mask, colour matrix, scale, export.
 */
@Composable
private fun EnhanceTab(
    overlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    depth: Int,
    warmth: Int,
    onDepth: (Int) -> Unit,
    onWarmth: (Int) -> Unit,
    look: LookPreset,
    imported: List<LookPreset>,
    onLook: (LookPreset) -> Unit,
    onDepthForLook: (Int, Int) -> Unit,
    onImported: (List<LookPreset>) -> Unit,
    panelIsOled: Boolean?,
    brightnessBoost: Int,
    onPanel: (Boolean?) -> Unit,
    onBrightness: (Int) -> Unit
) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }

    // JSON only now - the launcher is kept for look import, nothing else.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) {
            note = "Could not read that file."
        } else {
            val got = LookPresets.importJson(text)
            if (got.isEmpty()) note = "No valid looks in that file."
            else {
                onImported(got)
                note = "Imported ${got.size} look(s)."
            }
        }
    }

    // Drives the live layer. ColorOverlayService.start() sends ACTION_UPDATE
    // rather than restarting, so dragging a slider does not tear down and
    // rebuild a fullscreen window under the game.
    var layerWasOn by remember { mutableStateOf(depth != 0 || warmth != 0) }
    LaunchedEffect(depth, warmth, overlayGranted) {
        if (!overlayGranted) return@LaunchedEffect
        val nowOn = depth != 0 || warmth != 0
        if (nowOn) {
            ColorOverlayService.start(ctx, depth, warmth)
            layerWasOn = true
        } else if (layerWasOn) {
            ColorOverlayService.stop(ctx)
            layerWasOn = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("GRAPHICS LOOKS", fontSize = 24.sp, fontWeight = FontWeight.Black, color = NeonCyan)
        Text(
            "One tap, applied the moment a game opens and handed back when you leave",
            fontSize = 12.sp, color = Muted, lineHeight = 17.sp
        )
        Spacer(Modifier.height(18.dp))

        if (!overlayGranted) {
            Notice(
                "Looks need \"Display over other apps\". The tint is composited by " +
                    "SurfaceFlinger on top of the game - we never enter its process.",
                WarnYellow
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRequestOverlay,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("GRANT OVERLAY PERMISSION", fontWeight = FontWeight.Black, fontSize = 12.sp) }
            Spacer(Modifier.height(18.dp))
        }

        SectionLabel("LOOKS")
        val looks = LookPresets.all(imported)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            looks.forEach { l ->
                val sel = l.key == look.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) NeonCyan.copy(alpha = 0.12f) else Panel)
                        .border(1.dp, if (sel) NeonCyan else Panel, RoundedCornerShape(12.dp))
                        .clickable {
                            onLook(l)
                            if (l.needsOverlay) onDepthForLook(l.depth, l.warmth)
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (l.custom) l.title + "  (imported)" else l.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = if (sel) NeonCyan else Ink
                        )
                        Text(l.blurb, fontSize = 10.sp, color = Muted, lineHeight = 14.sp)
                    }
                    if (sel) {
                        Text("ON", fontSize = 11.sp, color = NeonCyan, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = {
                    val text = LookPresets.exportJson(imported)
                    val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "msj_looks.json")
                    runCatching { f.writeText(text) }
                        .onSuccess { note = "Exported ${looks.size} looks to ${f.name}" }
                        .onFailure { note = "Could not write the file." }
                },
                modifier = Modifier.weight(1f)
            ) { Text("EXPORT", fontSize = 10.sp, color = NeonCyan) }

            TextButton(
                onClick = { importPicker.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text("IMPORT JSON", fontSize = 10.sp, color = NeonCyan) }
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(note, fontSize = 11.sp, color = NeonCyan, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("FINE TUNE THE TINT")
        SliderRow("Depth", depth, 0, 40, "%", onDepth)
        SliderRow("Warmth", warmth, -60, 60, "", onWarmth)
        Text(
            "Depth darkens the frame, which is what makes saturated art read richer on " +
                "an OLED. Warmth shifts the white point. Both are tint layers over the " +
                "game's frame, not a re-map of its pixels - that is the OEM mode below.",
            fontSize = 10.sp, color = Muted, lineHeight = 15.sp
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("FIT TO THIS SCREEN")
        val panel = remember(panelIsOled) {
            DisplayProfile.read(ctx, panelIsOled)
        }
        Text(
            panel.describe(),
            fontSize = 12.sp, color = NeonCyan, fontWeight = FontWeight.Black
        )
        Text(
            "Looks are rescaled for this panel on the way in, so a depth that reads " +
                "as deep blacks on OLED does not turn to mud on an LCD. Refresh is " +
                "requested against your real ceiling, not a fixed 120.",
            fontSize = 10.sp, color = Muted, lineHeight = 15.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf<Pair<String, Boolean?>>(
                "AUTO" to null, "OLED" to true, "LCD" to false
            ).forEach { (label, v) ->
                val sel = panelIsOled == v
                TextButton(
                    onClick = { onPanel(v) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = if (sel) NeonCyan else Muted
                    )
                }
            }
        }
        Text(
            "Android has no API that reports panel type, so AUTO is a guess. " +
                "One tap here if it guessed wrong.",
            fontSize = 10.sp, color = Muted, lineHeight = 15.sp
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("BRIGHTNESS HEADROOM")
        SliderRow("Boost", brightnessBoost, 0, 100, "%", onBrightness)
        Text(
            "The one control here that puts more signal above the panel's noise " +
                "floor, so a dim game actually looks brighter rather than tinted. " +
                "Needs Write settings, and some devices clamp it - the display HAL " +
                "has the final say. Handed back to automatic when you leave the game.",
            fontSize = 10.sp, color = Muted, lineHeight = 15.sp
        )

        Spacer(Modifier.height(18.dp))
        var oemSupported by remember { mutableStateOf<Boolean?>(null) }
        var oemOn by remember { mutableStateOf(false) }
        TextButton(
            onClick = {
                val now = !oemOn
                val ok = DisplayController.setOemVividMode(now)
                if (ok) { oemOn = now; oemSupported = true }
                else oemSupported = false
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (oemOn) "OEM VIVID: ON" else "OEM VIVID COLOUR PROFILE",
                color = if (oemOn) NeonCyan else Muted,
                fontSize = 11.sp, fontWeight = FontWeight.Black
            )
        }
        if (oemSupported == false) {
            Text(
                "This build does not expose a display colour mode, so it cannot be set " +
                    "without root. The tint above is the fallback.",
                fontSize = 10.sp, color = Muted, lineHeight = 15.sp
            )
        } else if (oemSupported == true) {
            Text(
                "Applied by the display driver, downstream of the game - a genuine " +
                    "colour re-map rather than a tint.",
                fontSize = 10.sp, color = OkGreen, lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Integer slider row with a live value readout, matching the app's ToggleRow look. */
@Composable
private fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    onChange: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(96.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.weight(1f)
        )
        Text(
            "$value$suffix",
            fontSize = 11.sp, color = NeonCyan, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(54.dp),
            textAlign = TextAlign.End
        )
    }
}

/* ------------------------------ HUD ------------------------------ */

@Composable
private fun HudTab(
    overlayOn: Boolean, overlayGranted: Boolean,
    onRequestOverlay: () -> Unit, onToggleOverlay: (Boolean) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("ON-SCREEN HUD", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        if (!overlayGranted) {
            Notice(
                "Overlay permission is required. Android composites the HUD on top of the game " +
                    "window - we never touch the game's own process.",
                WarnYellow
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("GRANT OVERLAY PERMISSION", fontWeight = FontWeight.Black, fontSize = 12.sp) }
        } else {
            ToggleRow("Show HUD over games", "Temperature and free RAM while you play", overlayOn, onToggleOverlay)
        }

        Spacer(Modifier.height(16.dp))
        Text("WHAT THE HUD SHOWS", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Hottest thermal reading, from the battery NTC with a CPU-zone fallback",
            "Temperature turning orange past 42C, where the SoC drops its boost clocks",
            "Device-wide free RAM, the number a game actually allocates its textures from",
            "Everything sampled off the main thread, so the HUD never adds the stutter it is meant to measure"
        ).forEach {
            Row(Modifier.padding(vertical = 5.dp)) {
                Text("›  ", color = NeonCyan, fontWeight = FontWeight.Black)
                Text(it, color = Body, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/* ------------------------------ ABOUT ------------------------------ */

@Composable
private fun AboutTab() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("MSJ GFX", fontSize = 24.sp, fontWeight = FontWeight.Black, color = NeonCyan)
        Text("Game Graphics Enhancer & FPS Booster", fontSize = 13.sp, color = Body)
        Text("Created by M.S.J", fontSize = 13.sp, color = OkGreen, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        Text("WHY THERE IS NO INJECTOR HERE", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        listOf(
            "Free Fire, Free Fire MAX and PUBG Mobile ship signed ARM64 binaries with vendor anti-cheat. A process injector has to defeat their integrity checks first, and every one of them that claims to works is really a memory reader.",
            "Those memory readers change nothing permanently. The values are re-validated on the next match, so the \"60 FPS Ultra\" lasts one round and then the account is flagged.",
            "Accounts caught using them are banned in roughly three to ten minutes, and the ban is usually a hardware-level ID ban that survives a reinstall.",
            "Freeing RAM, staying out of thermal throttle, and using the game's own graphics menu is where actual frames come from. That is the part this app automates.",
            "The HUD is drawn with a normal user-granted overlay window. Nothing is injected into the game process, because nothing needs to be.",
            "Boost reclaims our own heap and asks the platform to trim. It cannot make your game release memory, and it will not pretend otherwise - the number it reports is a real before-and-after of device-wide available RAM."
        ).forEach {
            Row(Modifier.padding(vertical = 5.dp)) {
                Text("›  ", color = NeonCyan, fontWeight = FontWeight.Black)
                Text(it, color = Body, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("PERMISSIONS USED", fontSize = 12.sp, color = Muted, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        listOf(
            "SYSTEM_ALERT_WINDOW - draws the HUD on top of the game",
            "FOREGROUND_SERVICE_SPECIAL_USE - keeps the booster alive so Android does not force-close it",
            "RECEIVE_BOOT_COMPLETED - restarts the booster after a reboot, only if you left it on",
            "QUERY_ALL_PACKAGES - detects which supported game is installed"
        ).forEach {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("›  ", color = OkGreen, fontWeight = FontWeight.Black)
                Text(it, color = Body, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}

/* ------------------------------ shared bits ------------------------------ */

@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
}
