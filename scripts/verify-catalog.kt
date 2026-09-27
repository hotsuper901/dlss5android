// Guards the package -> title mapping in GameCatalog.
//
// Package names rot. com.garena.game.kgvn used to be Free Fire and now belongs
// to Garena Lien Quan Mobile, which silently broke detection for the real game.
// This asserts the mapping against package names verified against live Play
// Store listings on 2026-09, so a wrong entry fails loudly instead of quietly
// reporting "no game detected".
//
// Run: see scripts/typecheck.sh
import com.msj.gfx.core.GameCatalog

fun main() {
    val expect = listOf(
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.garena.game.kgvn" to "Garena Lien Quan Mobile",
        "com.tencent.ig" to "PUBG Mobile",
        "com.tencent.ig.lite" to "PUBG Mobile Lite",
        "com.pubg.imobile" to "BGMI",
        "com.pubg.newstate" to "New State Mobile",
        "com.pubg.krmobile" to "PUBG Mobile",
        "com.moonton.mobilelegends" to "Mobile Legends: Bang Bang",
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile",
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
    // process-name form and regional suffixes
    val a = GameCatalog.match("com.tencent.ig:unity")?.label
    val b = GameCatalog.match("com.moonton.mobilelegends.bh")?.label
    println("${if (a == "PUBG Mobile") "PASS" else "FAIL"}  process suffix com.tencent.ig:unity -> $a")
    println("${if (b == "Mobile Legends: Bang Bang") "PASS" else "FAIL"}  regional suffix .bh -> $b")
    if (a != "PUBG Mobile") bad++
    if (b != "Mobile Legends: Bang Bang") bad++
    // non-games must not match
    val n = GameCatalog.match("com.android.settings")
    println("${if (n == null) "PASS" else "FAIL"}  settings does not match -> $n")
    if (n != null) bad++
    println(if (bad == 0) "\nALL PASS" else "\n$bad FAILURES")
}
