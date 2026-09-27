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

    println()
    if (failures == 0) println("ALL PASS") else println("$failures FAILURE(S)")
}
