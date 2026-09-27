package com.msj.gfx.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msj.gfx.core.GameCatalog
import com.msj.gfx.core.MemoryTools
import com.msj.gfx.core.PerfSnapshot
import com.msj.gfx.core.Preset
import com.msj.gfx.core.Presets
import com.msj.gfx.core.SettingsStore
import kotlin.math.roundToInt

private enum class Tab(val label: String, val icon: ImageVector) {
    DASH("Boost", Icons.Filled.Bolt),
    GAMES("Games", Icons.Filled.Tune),
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
                    watchState = vm.watchState.collectAsState().value,
                    usageGranted = usageGranted,
                    onRequestUsageAccess = onRequestUsageAccess,
                    onBoost = vm::boost,
                    onResync = vm::resyncDetection,
                    onToggleBooster = onToggleBooster,
                    onPreset = settings::setPreset,
                    onToggleAutoTrim = settings::setAutoTrim
                )
                Tab.GAMES -> GamesTab(vm, onOpenGame)
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
    watchState: com.msj.gfx.core.GameWatcher.State,
    usageGranted: Boolean,
    onRequestUsageAccess: () -> Unit,
    onBoost: () -> Unit, onResync: () -> Unit,
    onToggleBooster: (Boolean) -> Unit,
    onPreset: (Preset) -> Unit, onToggleAutoTrim: (Boolean) -> Unit
) {
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
            detected, watchState, usageGranted, perf.freeRamMb, perf.lowMemory,
            onRequestUsageAccess, onResync
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

        Spacer(Modifier.height(16.dp))
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
    state: com.msj.gfx.core.GameWatcher.State,
    usageGranted: Boolean,
    freeRamMb: Int,
    lowMemory: Boolean,
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
            if (detected != null) {
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
                Text("No supported game on screen", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Start Free Fire or Mobile Legends, then pull this screen back to the " +
                        "foreground. Detection only reports while our process is alive.",
                    color = Body, fontSize = 12.sp, lineHeight = 18.sp
                )
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
private fun GameCard(g: GameCatalog.Game, onOpenGame: (String) -> Unit, installed: Boolean = true) {
    val accent = Color(g.accent)
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
        Spacer(Modifier.height(10.dp))
        g.levers.forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("› ", color = accent, fontWeight = FontWeight.Black)
                Text(it, color = Body, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
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
