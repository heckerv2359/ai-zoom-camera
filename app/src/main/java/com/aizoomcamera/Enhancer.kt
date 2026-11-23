package com.aizoomcamera

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Stores the API token encrypted on the device. */
object Keys {
    private fun prefs(ctx: Context): SharedPreferences {
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx,
            "secure_prefs",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(ctx: Context): String = prefs(ctx).getString("replicate_token", "") ?: ""

    fun save(ctx: Context, value: String) {
        prefs(ctx).edit().putString("replicate_token", value.trim()).apply()
    }
}

/**
 * Faithful super resolution through the Replicate API using Real-ESRGAN.
 * Face enhancement is switched off on purpose so faces are not redrawn.
 */
class Enhancer(private val token: String) {

    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun request() = Request.Builder().header("Authorization", "Bearer $token")

    suspend fun enhance(jpeg: ByteArray, scale: Int): ByteArray = withContext(Dispatchers.IO) {
        val fileUrl = upload(jpeg)
        val outputUrl = runModel(fileUrl, scale)
        download(outputUrl)
    }

    private fun upload(jpeg: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", "input.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = request().url("https://api.replicate.com/v1/files").post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "Upload failed (${r.code})" }
            return JSONObject(text).getJSONObject("urls").getString("get")
        }
    }

    private suspend fun runModel(fileUrl: String, scale: Int): String {
        val input = JSONObject()
            .put("image", fileUrl)
            .put("scale", scale)
            .put("face_enhance", false)
        val req = request()
            .url("https://api.replicate.com/v1/models/nightmareai/real-esrgan/predictions")
            .header("Prefer", "wait")
            .post(JSONObject().put("input", input).toString().toRequestBody(json))
            .build()

        var obj = http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "AI request failed (${r.code})" }
            JSONObject(text)
        }
        val pollUrl = obj.getJSONObject("urls").getString("get")

        repeat(60) {
            when (obj.getString("status")) {
                "succeeded" -> return obj.get("output").toString()
                "failed", "canceled" -> error(obj.optString("error", "AI processing failed"))
            }
            delay(2000)
            val poll = request().url(pollUrl).get().build()
            obj = http.newCall(poll).execute().use { JSONObject(it.body?.string().orEmpty()) }
        }
        error("AI processing timed out")
    }

    private fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "Download failed (${r.code})" }
            return r.body!!.bytes()
        }
    }
}
