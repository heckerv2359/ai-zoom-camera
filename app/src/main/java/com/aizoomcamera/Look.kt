package com.aizoomcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max

/** Contrast, saturation, brightness and signature text, applied to the edited copy only. */
object Look {

    fun isActive(s: Settings): Boolean =
        s.contrast != 1f || s.saturation != 1f || s.brightness != 0 || s.signature.isNotBlank()

    fun apply(src: Bitmap, s: Settings): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val c = s.contrast
        val shift = (1f - c) * 128f + s.brightness
        val cm = ColorMatrix()
        cm.setSaturation(s.saturation)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, shift,
                    0f, c, 0f, 0f, shift,
                    0f, 0f, c, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        val text = s.signature.trim()
        if (text.isNotEmpty()) {
            val size = max(14f, out.width / 40f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.WHITE
            p.textSize = size
            p.textAlign = Paint.Align.RIGHT
            p.setShadowLayer(size / 8f, 0f, 0f, Color.BLACK)
            canvas.drawText(text, out.width - size, out.height - size, p)
        }
        return out
    }
}
