import com.msj.gfx.core.LookPreset
import com.msj.gfx.core.LookPresets

/**
 * Guards the look library.
 *
 * Three presets shipped doing nothing at all: FPS Focus and Shadow Lift were
 * written with every field at zero, and Shadow Lift was literally "depth = -0"
 * where depth clamps to 0..40. A no-op preset still compiles, still renders,
 * still shows an ON badge, so nothing caught it - which is the whole reason
 * this file exists.
 */

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (!ok) {
        println("  FAIL  $name${if (detail.isNotBlank()) " - $detail" else ""}")
        failures++
    } else {
        println("  ok    $name")
    }
}

private fun actsOnSomething(l: LookPreset): Boolean =
    l.depth != 0 || l.warmth != 0 || l.refreshHz > 0 || l.oemVivid

fun main() {
    println("== every built-in look must do something ==")
    val dead = LookPresets.noOpBuiltIns()
    check("no dead built-in looks", dead.isEmpty(), "dead: $dead")

    println("== per-look invariants ==")
    LookPresets.BUILT_IN.forEach { l ->
        if (l.key == "off") return@forEach
        check("${l.key} is not a no-op", actsOnSomething(l), l.blurb)
    }

    println("== ranges stay inside what the service accepts ==")
    LookPresets.BUILT_IN.forEach { l ->
        check("${l.key} depth 0..40", l.depth in 0..40, "got ${l.depth}")
        check("${l.key} warmth -60..60", l.warmth in -60..60, "got ${l.warmth}")
        check("${l.key} refresh 0..240", l.refreshHz in 0..240, "got ${l.refreshHz}")
    }

    println("== keys are unique, and every key resolves back to itself ==")
    val keys = LookPresets.BUILT_IN.map { it.key }
    check("keys unique", keys.size == keys.toSet().size, "dupes: $keys")
    keys.forEach { k ->
        val got = LookPresets.byKey(k)
        check("byKey($k) round-trips", got.key == k, "got ${got.key}")
    }
    check("unknown key falls back to off", LookPresets.byKey("nope").key == "off")
    check("null key falls back to off", LookPresets.byKey(null).key == "off")

    println("== off is genuinely inert, and reports itself as such ==")
    val off = LookPresets.byKey("off")
    check("off needs no overlay", !off.needsOverlay)
    check("off does nothing", !actsOnSomething(off))

    println("== import/export survives a round trip ==")
    val json = LookPresets.exportJson()
    val back = LookPresets.importJson(json)
    check("all looks survive export/import", back.size == LookPresets.BUILT_IN.size,
        "got ${back.size}")
    LookPresets.BUILT_IN.forEach { orig ->
        val rt = back.firstOrNull { it.key == orig.key }
        check("round-trip ${orig.key}", rt != null &&
            rt.depth == orig.depth &&
            rt.warmth == orig.warmth &&
            rt.refreshHz == orig.refreshHz &&
            rt.oemVivid == orig.oemVivid,
            "orig=$orig got=$rt")
    }

    println("== bad input degrades instead of throwing ==")
    check("garbage returns empty", LookPresets.importJson("not json at all").isEmpty())
    check("empty object returns empty", LookPresets.importJson("{}").isEmpty())
    val hostile = LookPresets.importJson(
        """{"version":1,"looks":[{"key":"x","title":"X","depth":9999,"warmth":-9999,"refreshHz":-5}]}"""
    )
    check("out-of-range values are clamped", hostile.size == 1 &&
        hostile[0].depth == 40 && hostile[0].warmth == -60 && hostile[0].refreshHz == 0,
        "got $hostile")

    println("== imported looks are kept, built-ins are not shadowed ==")
    val mine = LookPreset("mine", "Mine", "custom", depth = 5, warmth = 5,
        refreshHz = 0, oemVivid = false, custom = true)
    val merged = LookPresets.all(listOf(mine))
    check("imported appended", merged.any { it.key == "mine" })
    check("built-in still present", merged.any { it.key == "oled" })
    check("no duplicate built-ins", merged.count { it.key == "oled" } == 1)
    val impostor = mine.copy(key = "oled", custom = true)
    val shadowed = LookPresets.all(listOf(impostor))
    check("built-in wins over a look that claims its key",
        shadowed.count { it.key == "oled" } == 1 &&
            shadowed.first { it.key == "oled" }.custom.not())

    println()
    if (failures == 0) println("ALL PASS") else println("$failures FAILURE(S)")
}
