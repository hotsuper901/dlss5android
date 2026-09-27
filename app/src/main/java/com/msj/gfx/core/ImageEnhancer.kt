package com.msj.gfx.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.File

/**
 * Offline image enhancement pipeline: denoise -> sharpen -> colour -> scale.
 *
 * This is the Android port of the stage order a desktop "visual enhancer" runs
 * on saved images. It processes pixels this app owns - screenshots, recordings,
 * saved shots - and writes a new file. It never touches a running game, and
 * unlike an injector it cannot, because there is no route into another
 * process's framebuffer that does not need root or a screen capture.
 *
 * The two interesting stages are the cheap ones:
 *
 *   Unsharp mask. Blur the image, subtract it from itself, add the difference
 *   back scaled. Perceived detail goes up for almost no cost, and it is the
 *   same effect a sharpen filter in a game's graphics menu gives you.
 *
 *   ColourMatrix. Saturation, contrast and warmth are one 4x5 matrix, and
 *   Canvas applies it in hardware. This is where "vibrant" comes from: a
 *   saturation of 1.30 plus a small contrast lift is the whole look.
 *
 * Deliberately CPU-based over an IntArray rather than a GPU shader, because
 * this is a one-shot export path - correctness and portability across Adreno,
 * Mali and PowerVR matter more than shaving 40ms off an offline render.
 */
object ImageEnhancer {

    /** All values are relative: 100 (or 1.0) means "unchanged". */
    data class Params(
        /** 0..100 - how far to blend toward the blurred copy. */
        val denoise: Int = 0,
        /** 0..100 - unsharp mask amount. 0 disables the stage. */
        val sharpen: Int = 45,
        /** 0..250 - 100 is neutral. 130 is the default vivid look. */
        val saturation: Int = 130,
        /** 50..160 - 100 is neutral. */
        val contrast: Int = 112,
        /** -50..50 - negative cools, positive warms. */
        val warmth: Int = 8,
        /** Output scale. 1.0 keeps source size. */
        val scale: Float = 1f
    ) {
        val isNeutral: Boolean
            get() = denoise == 0 && sharpen == 0 && saturation == 100 &&
                contrast == 100 && warmth == 0 && scale == 1f
    }

    /** A few presets matching what a game's own graphics menu would give you. */
    object Presets {
        val SHARP = Params(denoise = 0, sharpen = 60, saturation = 108, contrast = 106, warmth = 0)
        val VIVID = Params(denoise = 0, sharpen = 45, saturation = 150, contrast = 118, warmth = 10)
        val CLEAN = Params(denoise = 40, sharpen = 35, saturation = 115, contrast = 104, warmth = 0)
        val UPSCALE2X = Params(denoise = 15, sharpen = 70, saturation = 130, contrast = 112, warmth = 5, scale = 2f)
        val ALL = listOf(
            "Sharp" to SHARP,
            "Vivid" to VIVID,
            "Clean" to CLEAN,
            "Upscale 2x" to UPSCALE2X
        )
    }

    /**
     * Run the pipeline. Never returns null and never mutates the input - the
     * caller keeps its bitmap so a before/after preview can show both.
     */
    fun process(src: Bitmap, p: Params): Bitmap {
        var w = src.width
        var h = src.height
        var pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        var work = pixels

        // Stage 1: denoise. Blending toward a blurred copy removes compression
        // speckle; it also softens, which is why sharpen runs after it.
        if (p.denoise > 0) {
            val blurred = boxBlur(work, w, h, 1)
            work = mix(work, blurred, p.denoise.coerceIn(0, 100) / 100f)
        }

        // Stage 2: unsharp mask.
        if (p.sharpen > 0) {
            val blurred = boxBlur(work, w, h, 1)
            work = unsharpMask(work, blurred, p.sharpen.coerceIn(0, 100) / 100f)
        }

        val scaled = if (p.scale != 1f && p.scale > 0f) {
            val scaledBmp = scaleBilinear(work, w, h, p.scale)
            w = scaledBmp.first
            h = scaledBmp.second
            scaledBmp.third
        } else work

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(scaled, 0, w, 0, 0, w, h)
        return applyColour(out, p)
    }

    /**
     * Saturation / contrast / warmth in one matrix.
     *
     * Order matters: setSaturation() sets the matrix, so it has to run first
     * and postConcat after it.
     */
    private fun applyColour(src: Bitmap, p: Params): Bitmap {
        val cm = ColorMatrix()
        cm.setSaturation((p.saturation.coerceIn(0, 250)) / 100f)

        val cScale = p.contrast.coerceIn(50, 160) / 100f
        val offset = (0.5f - 0.5f * cScale) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    cScale, 0f, 0f, 0f, offset,
                    0f, cScale, 0f, 0f, offset,
                    0f, 0f, cScale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        val warmth = p.warmth.coerceIn(-50, 50) / 100f
        if (warmth != 0f) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f + 0.22f * warmth, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f - 0.22f * warmth, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Separable 3-tap box blur. Two passes approximate a gaussian closely. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val n = (radius * 2 + 1)

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val c = src[row + xx]
                    a += (c ushr 24) and 0xFF
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                tmp[row + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val c = tmp[yy * w + x]
                    a += (c ushr 24) and 0xFF
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    /** orig + amount * (orig - blur), clamped per channel. */
    private fun unsharpMask(orig: IntArray, blur: IntArray, amount: Float): IntArray {
        val out = IntArray(orig.size)
        for (i in orig.indices) {
            val o = orig[i]
            val b = blur[i]
            val r = (o shr 16 and 0xFF) + amount * ((o shr 16 and 0xFF) - (b shr 16 and 0xFF))
            val g = (o shr 8 and 0xFF) + amount * ((o shr 8 and 0xFF) - (b shr 8 and 0xFF))
            val bl = (o and 0xFF) + amount * ((o and 0xFF) - (b and 0xFF))
            out[i] = (o and 0xFF000000.toInt()) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                bl.toInt().coerceIn(0, 255)
        }
        return out
    }

    /** Linear blend: a toward b by t. */
    private fun mix(a: IntArray, b: IntArray, t: Float): IntArray {
        val out = IntArray(a.size)
        val inv = 1f - t
        for (i in a.indices) {
            val ca = a[i]; val cb = b[i]
            val r = ((ca shr 16 and 0xFF) * inv + (cb shr 16 and 0xFF) * t)
            val g = ((ca shr 8 and 0xFF) * inv + (cb shr 8 and 0xFF) * t)
            val bl = ((ca and 0xFF) * inv + (cb and 0xFF) * t)
            out[i] = (ca and 0xFF000000.toInt()) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                bl.toInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Nearest-neighbour-free upscale: bilinear over the IntArray so it can be
     * chained with the filter stages without a Bitmap round-trip per stage.
     */
    private fun scaleBilinear(src: IntArray, sw: Int, sh: Int, factor: Float): Triple<Int, Int, IntArray> {
        val dw = (sw * factor).toInt().coerceAtLeast(1)
        val dh = (sh * factor).toInt().coerceAtLeast(1)
        if (dw == sw && dh == sh) return Triple(sw, sh, src)
        val out = IntArray(dw * dh)
        val xr = (sw - 1) / (dw - 1).coerceAtLeast(1).toFloat()
        val yr = (sh - 1) / (dh - 1).coerceAtLeast(1).toFloat()
        for (y in 0 until dh) {
            val sy = y * yr
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtLeast(sh - 1)
            val fy = sy - y0
            for (x in 0 until dw) {
                val sx = x * xr
                val x0 = sx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceAtLeast(sw - 1)
                val fx = sx - x0
                val p00 = src[y0 * sw + x0]
                val p10 = src[y0 * sw + x1]
                val p01 = src[y1 * sw + x0]
                val p11 = src[y1 * sw + x1]
                out[y * dw + x] = bilinear(p00, p10, p01, p11, fx, fy)
            }
        }
        return Triple(dw, dh, out)
    }

    private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
        val invX = 1f - fx
        val invY = 1f - fy
        val w00 = invX * invY
        val w10 = fx * invY
        val w01 = invX * fy
        val w11 = fx * fy
        val a = (p00 ushr 24) * w00 + (p10 ushr 24) * w10 + (p01 ushr 24) * w01 + (p11 ushr 24) * w11
        val r = (p00 shr 16 and 0xFF) * w00 + (p10 shr 16 and 0xFF) * w10 + (p01 shr 16 and 0xFF) * w01 + (p11 shr 16 and 0xFF) * w11
        val g = (p00 shr 8 and 0xFF) * w00 + (p10 shr 8 and 0xFF) * w10 + (p01 shr 8 and 0xFF) * w01 + (p11 shr 8 and 0xFF) * w11
        val b = (p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11
        return (a.toInt().coerceIn(0, 255) shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)
    }

    /** Write to a file. Returns the file, or null if compression fails. */
    fun save(src: Bitmap, file: File, asPng: Boolean, quality: Int = 95): File? = runCatching {
        file.parentFile?.mkdirs()
        val ok = if (asPng) src.compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
        else src.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), file.outputStream())
        if (ok) file else null
    }.getOrNull()
}
