package com.aizoomcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Takes the raw capture, applies the digital part of the zoom as a centre crop,
 * saves the untouched crop, then optionally saves an enhanced copy next to it.
 * The original is never overwritten.
 *
 * Enhancement uses the API when a token is set. With no token, or if the API call fails,
 * it runs on the device instead.
 */
object PhotoPipeline {

    private const val DEVICE_SHARPEN = 0.6f

    suspend fun process(
        ctx: Context,
        raw: File,
        digital: Float,
        token: String,
        s: Settings,
        onSaved: (Uri) -> Unit,
        report: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val bmp = cropAndRotate(raw, digital)
            onSaved(save(ctx, bmp, "AIZoom_$stamp.jpg", s.jpegQuality))

            if (!s.aiEnabled || digital < s.aiMinZoom) {
                report("Saved")
                return@withContext
            }

            val longSide = max(bmp.width, bmp.height)
            val scale = if (s.aiScale > 0) s.aiScale else if (longSide <= 700) 4 else 2

            var onDevice = token.isBlank()
            val result: Bitmap = if (onDevice) {
                report("Enhancing on device")
                LocalEnhancer.enhance(bmp, scale, DEVICE_SHARPEN)
            } else {
                report("Enhancing with API")
                try {
                    viaApi(bmp, scale, token)
                } catch (e: Exception) {
                    onDevice = true
                    report("API failed, enhancing on device")
                    LocalEnhancer.enhance(bmp, scale, DEVICE_SHARPEN)
                }
            }

            onSaved(save(ctx, result, "AIZoom_${stamp}_AI.jpg", s.jpegQuality))
            report(if (onDevice) "Saved original and enhanced on device" else "Saved original and enhanced")
        } catch (e: Exception) {
            report("Error: ${e.message}")
        } finally {
            raw.delete()
        }
    }

    private suspend fun viaApi(bmp: Bitmap, scale: Int, token: String): Bitmap {
        val longSide = max(bmp.width, bmp.height)
        val maxSend = 4000 / scale
        val toSend = if (longSide > maxSend) {
            val f = maxSend.toFloat() / longSide
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else bmp

        val out = ByteArrayOutputStream()
        toSend.compress(Bitmap.CompressFormat.JPEG, 95, out)
        val enhanced = Enhancer(token).enhance(out.toByteArray(), scale)
        return BitmapFactory.decodeByteArray(enhanced, 0, enhanced.size)
            ?: error("Could not read the AI result")
    }

    @Suppress("DEPRECATION")
    private fun cropAndRotate(raw: File, digital: Float): Bitmap {
        val orientation = ExifInterface(raw.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        val decoder = BitmapRegionDecoder.newInstance(raw.path, false)
        val w = decoder.width
        val h = decoder.height
        val cw = (w / digital).toInt().coerceAtLeast(16)
        val ch = (h / digital).toInt().coerceAtLeast(16)
        val rect = Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
        val region = decoder.decodeRegion(rect, BitmapFactory.Options())
        decoder.recycle()

        if (degrees == 0f) return region
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(region, 0, 0, region.width, region.height, m, true)
    }

    private fun save(ctx: Context, bmp: Bitmap, name: String, quality: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIZoomCamera")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create file")
        ctx.contentResolver.openOutputStream(uri)!!.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)
        }
        return uri
    }
}
