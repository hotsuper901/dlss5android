// Guards the package -> title mapping in GameCatalog.
//
// Package names rot, and several popular titles ship under a different id
// than the one you would guess: Mobile Legends is com.mobile.legends, not
// com.moonton.mobilelegends; Honor of Kings is com.levelinfinite.sgameGlobal,
// not com.tencent.tmgp.sgame; Critical Ops is
// com.criticalforceentertainment.criticalops, not ca.criticalops.thecriticalop;
// PUBG MOBILE LITE is com.tencent.iglite with no dot. Each of those wrong ids
// made detection report "no game detected".
//
// Everything below was verified against a live Google Play listing on
// 2026-09. See scripts/verify-catalog.kt.
import com.msj.gfx.core.GameCatalog

fun main() {
    val expect = listOf(
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.garena.game.kgvn" to "Garena Lien Quan Mobile",
        "com.tencent.ig" to "PUBG Mobile",
        "com.tencent.iglite" to "PUBG Mobile Lite",
        "com.pubg.imobile" to "BGMI",
        "com.pubg.newstate" to "New State Mobile",
        "com.pubg.krmobile" to "PUBG Mobile",
        "com.mobile.legends" to "Mobile Legends: Bang Bang",
        "com.levelinfinite.sgameGlobal" to "Honor of Kings",
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile",
        "com.criticalforceentertainment.criticalops" to "Critical Ops",
        // retired / regional ids must still resolve, not fall through
        "com.moonton.mobilelegens" to "Mobile Legends: Bang Bang",
        "com.moonton.mobilelegends" to "Mobile Legends: Bang Bang",
        "com.tencent.tmgp.sgame" to "Honor of Kings",
        "ca.criticalops.thecriticalop" to "Critical Ops"
    )
    var bad = 0
    for ((pkg, want) in expect) {
        val got = GameCatalog.match(pkg)?.label ?: "NO MATCH"
        val ok = got == want
        if (!ok) bad++
        println("${if (ok) "PASS" else "FAIL"}  $pkg -> $got" + if (ok) "" else "  (expected $want)")
    }
    // prefix-collision traps: the shorter id must not swallow the longer one
    val traps = listOf(
        "com.tencent.iglite" to "PUBG Mobile Lite",
        "com.pubg.imobile" to "BGMI",
        "com.pubg.newstate" to "New State Mobile",
        "com.dts.freefiremax" to "Free Fire MAX"
    )
    for ((pkg, want) in traps) {
        val got = GameCatalog.match(pkg)?.label ?: "NO MATCH"
        val ok = got == want
        if (!ok) bad++
        println("${if (ok) "PASS" else "FAIL"}  trap $pkg -> $got" + if (ok) "" else "  (expected $want)")
    }
    // process-name form, regional suffix
    val a = GameCatalog.match("com.tencent.ig:unity")?.label
    val b = GameCatalog.match("com.mobile.legends.bh")?.label
    println("${if (a == "PUBG Mobile") "PASS" else "FAIL"}  process suffix com.tencent.ig:unity -> $a")
    println("${if (b == "Mobile Legends: Bang Bang") "PASS" else "FAIL"}  regional suffix .bh -> $b")
    if (a != "PUBG Mobile") bad++
    if (b != "Mobile Legends: Bang Bang") bad++
    val n = GameCatalog.match("com.android.settings")
    println("${if (n == null) "PASS" else "FAIL"}  settings does not match -> $n")
    if (n != null) bad++
    println(if (bad == 0) "\nALL PASS" else "\n$bad FAILURES")
}
