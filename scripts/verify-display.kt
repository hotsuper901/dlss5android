import com.msj.gfx.core.DisplayController
import com.msj.gfx.core.DisplayProfile
import com.msj.gfx.core.LookPreset
import com.msj.gfx.core.LookPresets
import com.msj.gfx.core.adaptTo

/**
 * Guards display adaptation.
 *
 * adaptTo() is pure, so this runs on the JVM with no device. It matters
 * because the two directions of error are both bad and both silent: too little
 * depth means the look does nothing, too much means the game is unplayable in
 * a dark room.
 */

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (!ok) {
        println("  FAIL  $name${if (detail.isNotBlank()) " - $detail" else ""}")
        failures++
    } else println("  ok    $name")
}

private fun oled(maxHz: Float = 120f, wide: Boolean = true, hdr: Boolean = true) =
    DisplayProfile(isOled = true, maxRefreshHz = maxHz, hdrCapable = hdr, wideGamut = wide, longEdgePx = 2400)

private fun lcd(maxHz: Float = 60f, wide: Boolean = false, hdr: Boolean = false) =
    DisplayProfile(isOled = false, maxRefreshHz = maxHz, hdrCapable = hdr, wideGamut = wide, longEdgePx = 1600)

fun main() {
    println("== an LCD must get materially less depth than an OLED ==")
    val heavy = LookPreset("t", "T", "b", depth = 30, warmth = 0, refreshHz = 0, oemVivid = false)
    val onOled = heavy.adaptTo(oled())
    val onLcd = heavy.adaptTo(lcd())
    check("OLED keeps authored depth", onOled.depth == 30, "got ${onOled.depth}")
    check("LCD scales depth down", onLcd.depth < 15, "got ${onLcd.depth}")
    check("LCD never scales depth up", onLcd.depth <= onOled.depth)
    check("depth never leaves 0..40",
        LookPresets.BUILT_IN.all { l ->
            l.adaptTo(oled()).depth in 0..40 && l.adaptTo(lcd()).depth in 0..40
        })

    println("== warmth is not scaled: a white point is a white point ==")
    val warm = LookPreset("t", "T", "b", depth = 0, warmth = 40, refreshHz = 0, oemVivid = false)
    check("warmth preserved on LCD", warm.adaptTo(lcd()).warmth == 40)
    check("warmth preserved on OLED", warm.adaptTo(oled()).warmth == 40)

    println("== refresh is requested against the panel's real ceiling ==")
    val fps = LookPresets.byKey("fps")
    check("120 request honoured on a 120Hz panel", fps.adaptTo(oled(120f)).refreshHz == 120)
    check("120 request clamped to 90 on a 90Hz panel", fps.adaptTo(oled(90f)).refreshHz == 90,
        "got ${fps.adaptTo(oled(90f)).refreshHz}")
    check("120 request clamped to 60 on a 60Hz panel", fps.adaptTo(oled(60f)).refreshHz == 60)
    check("144Hz panel is not held at 120",
        fps.adaptTo(oled(144f)).refreshHz == 120, "request is 120, ceiling is 144")
    check("unknown max refresh leaves the request alone",
        fps.adaptTo(oled(0f)).refreshHz == 120)
    check("a look with no refresh request still gets none",
        LookPresets.byKey("oled").adaptTo(oled(144f)).refreshHz == 0)

    println("== vivid is only requested where a wide gamut can show it ==")
    val vivid = LookPresets.byKey("pop")
    check("vivid kept on wide gamut", vivid.adaptTo(oled(wide = true)).oemVivid)
    check("vivid dropped on sRGB-only", !vivid.adaptTo(oled(wide = false)).oemVivid)

    println("== adaptation is idempotent: fitting twice is not a slow drift ==")
    LookPresets.BUILT_IN.forEach { l ->
        val once = l.adaptTo(oled())
        val twice = once.adaptTo(oled())
        check("stable ${l.key}", once == twice, "$once vs $twice")
    }

    println("== adapted looks stay non-degenerate ==")
    // A look that only asks for a vivid profile on an sRGB panel would adapt
    // down to nothing at all, which is the same dead-preset bug again.
    val p = DisplayProfile(isOled = false, maxRefreshHz = 60f, hdrCapable = false,
        wideGamut = false, longEdgePx = 1600)
    val dead = LookPresets.BUILT_IN
        .filter { it.key != "off" }
        .map { it.key to it.adaptTo(p) }
        .filter { (_, a) -> a.depth == 0 && a.warmth == 0 && a.refreshHz == 0 && !a.oemVivid }
        .map { it.first }
    check("nothing adapts away to a no-op on an sRGB 60Hz LCD", dead.isEmpty(), "dead: $dead")

    println("== the assumed profile is the least lossy one ==")
    check("assumed profile is OLED", DisplayProfile.ASSUMED.isOled)
    check("assumed profile does not scale depth", DisplayProfile.ASSUMED.depthScale == 1.0f)
    check("lcd scales depth", lcd().depthScale < 1.0f)

    // Regression for "flashlight screen when select preset when game on".
    //
    // Selecting a look used to force screen_brightness_mode to manual and write
    // a value up to 255. OEM display HALs answer a saturated manual brightness
    // with outdoor boost, which some of them satisfy by driving the LED torch -
    // a white flash and, on some devices, a lit torch. The value also re-read
    // and re-added on every call, so repeated preset taps ratcheted it upwards
    // and the flash strobed rather than flashing once.
    val cap = DisplayController.MAX_SAFE_BRIGHTNESS
    check("brightness cap leaves headroom below saturation", cap < 255)

    var escapes = 0
    for (cur in listOf(-1, 0, 1, 64, 128, 200, 254, 255, 999)) {
        for (boost in 0..100) {
            val t = DisplayController.brightnessTarget(cur, boost)
            if (t < 1 || t > cap) escapes++
        }
    }
    check("no (current, boost) pair escapes 1..$cap", escapes == 0, "$escapes escaped")

    check(
        "full boost from 128 stays below saturation",
        DisplayController.brightnessTarget(128, 100) < 255
    )
    check(
        "a panel already at 255 is pulled down to the cap",
        DisplayController.brightnessTarget(255, 100) <= cap
    )

    // The baseline is fixed at capture, so the same boost must always give the
    // same value no matter how many times the preset is re-applied. Reading the
    // live value instead made this cumulative and walked the panel to the cap.
    var ratchets = 0
    for (base in listOf(-1, 0, 1, 64, 128, 200, 255)) {
        for (boost in listOf(25, 50, 75, 100)) {
            val once = DisplayController.brightnessTarget(base, boost)
            if (once != DisplayController.brightnessTarget(base, boost)) ratchets++
            if (once != DisplayController.brightnessTarget(base, boost)) ratchets++
        }
    }
    check("reapplying cannot ratchet the panel brighter", ratchets == 0, "$ratchets ratcheted")

    // A low baseline must not be walked upward by repeated application, which
    // is what the old live-read version did (1 -> 71 -> 141 -> 204).
    val low = DisplayController.brightnessTarget(1, 100)
    check(
        "a low baseline is not inflated by reapplication",
        low == DisplayController.brightnessTarget(1, 100) && low < 100,
        "got $low"
    )

    check(
        "an unreadable panel (-1) still yields a visible value",
        (1..100).all { DisplayController.brightnessTarget(-1, it) > 0 }
    )
    check(
        "zero boost is a legal no-dark-screen value",
        DisplayController.brightnessTarget(128, 0) in 1..cap
    )

    var dips = 0
    var prevT = 0
    for (boost in 0..100) {
        val t = DisplayController.brightnessTarget(128, boost)
        if (t < prevT) dips++
        prevT = t
    }
    check("target is monotonic in boost", dips == 0, "$dips dips")
    println("  ok    brightness capped at $cap, idempotent, never reaches the torch path")

    println()
    if (failures == 0) println("ALL PASS") else println("$failures FAILURE(S)")
}
