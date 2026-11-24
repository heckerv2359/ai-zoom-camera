package com.aizoomcamera

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On device fallback used when there is no API token or the API call fails.
 * It upscales the crop and then sharpens only the brightness channel, so colours stay true.
 * It cannot invent new detail the way the API model does. It only makes existing detail clearer.
 */
object LocalEnhancer {

    private const val MAX_PIXELS = 10_000_000.0
    private const val NOISE_FLOOR = 3

    suspend fun enhance(src: Bitmap, scale: Int, amount: Float): Bitmap =
        withContext(Dispatchers.Default) {
            var factor = scale.toDouble()
            val target = src.width * factor * src.height * factor
            if (target > MAX_PIXELS) factor *= sqrt(MAX_PIXELS / target)
            factor = max(1.0, factor)

            val w = (src.width * factor).toInt()
            val h = (src.height * factor).toInt()
            val scaled =
                if (w == src.width && h == src.height) src
                else Bitmap.createScaledBitmap(src, w, h, true)

            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled !== src) scaled.recycle()

            sharpen(pixels, w, h, amount)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            out
        }

    /** Unsharp mask on luma with a 3 by 3 blur. Tiny differences are skipped so noise is not boosted. */
    private suspend fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        if (w < 3 || h < 3 || amount <= 0f) return

        val luma = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val l = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
            luma[i] = l.toByte()
        }

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val band = (h - 2 + workers - 1) / workers

        coroutineScope {
            (0 until workers).map { k ->
                async {
                    val y0 = 1 + k * band
                    val y1 = minOf(h - 1, y0 + band)
                    for (y in y0 until y1) {
                        val row = y * w
                        for (x in 1 until w - 1) {
                            val i = row + x
                            val blur = (
                                (luma[i - w - 1].toInt() and 0xFF) +
                                    2 * (luma[i - w].toInt() and 0xFF) +
                                    (luma[i - w + 1].toInt() and 0xFF) +
                                    2 * (luma[i - 1].toInt() and 0xFF) +
                                    4 * (luma[i].toInt() and 0xFF) +
                                    2 * (luma[i + 1].toInt() and 0xFF) +
                                    (luma[i + w - 1].toInt() and 0xFF) +
                                    2 * (luma[i + w].toInt() and 0xFF) +
                                    (luma[i + w + 1].toInt() and 0xFF)
                                ) shr 4
                            val d = (luma[i].toInt() and 0xFF) - blur
                            if (abs(d) < NOISE_FLOOR) continue

                            val delta = (amount * d).toInt()
                            val c = px[i]
                            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
                            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
                            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
                            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
