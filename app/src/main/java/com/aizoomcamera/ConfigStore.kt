package com.aizoomcamera

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/** One value from a config file, kept with its original type so exports stay lossless. */
data class Entry(val type: String, val value: String = "", val items: List<String> = emptyList())

/**
 * Reads and writes the same XML layout LMC and other GCam ports use, which is the Android
 * SharedPreferences format with a map root and string, boolean, int, long, float and set children.
 *
 * Every entry in an imported file is kept and written back untouched. Keys that start with
 * aizoom_ change how this app behaves. A set of LMC keys with a matching feature here is also
 * translated into aizoom_ keys on import, see translate. The rest drive the GCam HDR+ engine.
 */
class ConfigStore(ctx: Context) {

    private val file = File(ctx.filesDir, "config.xml")
    private val entries = LinkedHashMap<String, Entry>()

    init {
        if (file.exists()) {
            try {
                file.inputStream().use { entries.putAll(parse(it)) }
            } catch (e: Exception) {
                entries.clear()
            }
        }
    }

    fun merge(imported: Map<String, Entry>) {
        entries.putAll(imported)
        save()
    }

    fun reset() {
        entries.keys.removeAll { it.startsWith(PREFIX) }
        save()
    }

    fun save() {
        file.outputStream().use { write(it) }
    }

    fun write(out: OutputStream) {
        val s = Xml.newSerializer()
        s.setOutput(out, "UTF-8")
        s.startDocument("UTF-8", true)
        s.startTag(null, "map")
        for ((key, e) in entries) {
            when (e.type) {
                "string" -> {
                    s.startTag(null, "string")
                    s.attribute(null, "name", key)
                    s.text(e.value)
                    s.endTag(null, "string")
                }
                "set" -> {
                    s.startTag(null, "set")
                    s.attribute(null, "name", key)
                    for (item in e.items) {
                        s.startTag(null, "string")
                        s.text(item)
                        s.endTag(null, "string")
                    }
                    s.endTag(null, "set")
                }
                else -> {
                    s.startTag(null, e.type)
                    s.attribute(null, "name", key)
                    s.attribute(null, "value", e.value)
                    s.endTag(null, e.type)
                }
            }
        }
        s.endTag(null, "map")
        s.endDocument()
    }

    private fun raw(key: String): String? = entries[PREFIX + key]?.value

    private fun bool(key: String, def: Boolean): Boolean =
        raw(key)?.let { it.equals("true", true) || it == "1" } ?: def

    private fun int(key: String, def: Int): Int = raw(key)?.toFloatOrNull()?.toInt() ?: def

    private fun float(key: String, def: Float): Float = raw(key)?.toFloatOrNull() ?: def

    private fun text(key: String, def: String): String =
        raw(key)?.trim()?.takeIf { it.isNotEmpty() } ?: def

    private fun choice(key: String, def: String, allowed: List<String>): String {
        val v = text(key, def)
        return allowed.firstOrNull { it.equals(v, true) } ?: def
    }

    fun toSettings(): Settings {
        val d = Settings()
        val fps = int("video_fps", d.videoFps)
        val legacyDoubleTap = if (bool("double_tap_import", true)) "import" else "none"
        val prefix = text("file_prefix", d.filePrefix)
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(24)
            .ifEmpty { d.filePrefix }
        val scale = int("ai_scale", d.aiScale)
        return Settings(
            grid = bool("grid", d.grid),
            gridStyle = choice("grid_style", d.gridStyle, listOf("3x3", "4x4", "Golden", "Center")),
            aiEnabled = bool("ai_enabled", d.aiEnabled),
            aiMinZoom = float("ai_min_zoom", d.aiMinZoom).coerceIn(1f, 10f),
            aiScale = if (scale == 2 || scale == 4) scale else 0,
            sharpness = float("sharpness", d.sharpness).coerceIn(0f, 1.5f),
            contrast = float("contrast", d.contrast).coerceIn(0.5f, 1.5f),
            saturation = float("saturation", d.saturation).coerceIn(0f, 2f),
            brightness = int("brightness", d.brightness).coerceIn(-50, 50),
            jpegQuality = int("jpeg_quality", d.jpegQuality).coerceIn(50, 100),
            photoMp = int("photo_mp", d.photoMp).coerceIn(0, 200),
            maxPhotoZoom = int("max_photo_zoom", d.maxPhotoZoom).coerceIn(2, 100),
            maxVideoZoom = int("max_video_zoom", d.maxVideoZoom).coerceIn(2, 100),
            videoQuality = choice("video_quality", d.videoQuality, listOf("HD", "FHD", "UHD")),
            videoFps = if (fps == 24 || fps == 30 || fps == 60) fps else 0,
            videoBitrate = int("video_bitrate", d.videoBitrate).coerceIn(0, 100),
            stabilization = bool("video_stabilization", d.stabilization),
            exposureIndex = int("exposure_index", d.exposureIndex),
            manual = bool("manual", d.manual),
            iso = int("iso", d.iso).coerceIn(50, 12800),
            shutterDenom = int("shutter_denom", d.shutterDenom).coerceIn(2, 4000),
            manualFocus = bool("manual_focus", d.manualFocus),
            focusDistance = float("focus_distance", d.focusDistance).coerceIn(0f, 1f),
            focusLockSec = int("focus_lock_sec", d.focusLockSec).coerceIn(0, 30),
            whiteBalance = choice(
                "white_balance", d.whiteBalance,
                listOf("Auto", "Daylight", "Cloudy", "Shade", "Incandescent", "Fluorescent")
            ),
            antiBanding = choice("anti_banding", d.antiBanding, listOf("Auto", "50Hz", "60Hz", "Off")),
            doubleTap = choice("double_tap", legacyDoubleTap, listOf("zoom", "import", "none")),
            volumeAction = choice("volume_action", d.volumeAction, listOf("shutter", "zoom", "none")),
            shutterSound = bool("shutter_sound", d.shutterSound),
            mirrorSelfie = bool("mirror_selfie", d.mirrorSelfie),
            locationTag = bool("location_tag", d.locationTag),
            filePrefix = prefix,
            signature = text("signature", "").take(60),
            lensIds = text("lens_ids", ""),
            lensNames = text("lens_names", "")
        )
    }

    fun update(s: Settings) {
        put("grid", "boolean", s.grid.toString())
        put("grid_style", "string", s.gridStyle)
        put("ai_enabled", "boolean", s.aiEnabled.toString())
        put("ai_min_zoom", "float", s.aiMinZoom.toString())
        put("ai_scale", "int", s.aiScale.toString())
        put("sharpness", "float", s.sharpness.toString())
        put("contrast", "float", s.contrast.toString())
        put("saturation", "float", s.saturation.toString())
        put("brightness", "int", s.brightness.toString())
        put("jpeg_quality", "int", s.jpegQuality.toString())
        put("photo_mp", "int", s.photoMp.toString())
        put("max_photo_zoom", "int", s.maxPhotoZoom.toString())
        put("max_video_zoom", "int", s.maxVideoZoom.toString())
        put("video_quality", "string", s.videoQuality)
        put("video_fps", "int", s.videoFps.toString())
        put("video_bitrate", "int", s.videoBitrate.toString())
        put("video_stabilization", "boolean", s.stabilization.toString())
        put("exposure_index", "int", s.exposureIndex.toString())
        put("manual", "boolean", s.manual.toString())
        put("iso", "int", s.iso.toString())
        put("shutter_denom", "int", s.shutterDenom.toString())
        put("manual_focus", "boolean", s.manualFocus.toString())
        put("focus_distance", "float", s.focusDistance.toString())
        put("focus_lock_sec", "int", s.focusLockSec.toString())
        put("white_balance", "string", s.whiteBalance)
        put("anti_banding", "string", s.antiBanding)
        put("double_tap", "string", s.doubleTap)
        put("volume_action", "string", s.volumeAction)
        put("shutter_sound", "boolean", s.shutterSound.toString())
        put("mirror_selfie", "boolean", s.mirrorSelfie.toString())
        put("location_tag", "boolean", s.locationTag.toString())
        put("file_prefix", "string", s.filePrefix)
        put("signature", "string", s.signature)
        put("lens_ids", "string", s.lensIds)
        put("lens_names", "string", s.lensNames)
    }

    private fun put(key: String, type: String, value: String) {
        entries[PREFIX + key] = Entry(type, value)
    }

    companion object {
        const val PREFIX = "aizoom_"

        fun parse(input: InputStream): Map<String, Entry> {
            val p = Xml.newPullParser()
            p.setInput(input, null)
            val out = LinkedHashMap<String, Entry>()
            var sawMap = false
            var setName: String? = null
            val setItems = mutableListOf<String>()

            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = p.getAttributeValue(null, "name")
                    when (val tag = p.name) {
                        "map" -> sawMap = true
                        "set" -> {
                            setName = name
                            setItems.clear()
                        }
                        "string" -> {
                            val text = p.nextText()
                            if (setName != null) setItems.add(text)
                            else if (name != null) out[name] = Entry("string", text)
                        }
                        "boolean", "int", "long", "float" -> {
                            val v = p.getAttributeValue(null, "value")
                            if (name != null && v != null) out[name] = Entry(tag, v)
                        }
                    }
                } else if (event == XmlPullParser.END_TAG && p.name == "set") {
                    setName?.let { out[it] = Entry("set", items = setItems.toList()) }
                    setName = null
                }
                event = p.next()
            }
            if (!sawMap) error("This is not a config file")
            return out
        }

        /** Turns LMC and GCam keys that have a matching feature here into aizoom_ keys. */
        fun translate(src: Map<String, Entry>): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()

            fun get(key: String): String? = src[key]?.value?.trim()
            fun put(key: String, type: String, value: String) {
                out[PREFIX + key] = Entry(type, value)
            }
            fun flag(v: String) = v == "1" || v.equals("true", true)

            get("pref_qjpg_key")?.toFloatOrNull()?.let { put("jpeg_quality", "int", it.toInt().toString()) }
            get("pref_camera_grid_lines_mode")?.let { put("grid", "boolean", (it.isNotEmpty() && it != "0").toString()) }
            get("pref_video_resolution")?.let { r ->
                val q = when {
                    r.contains("4K") || r.contains("2160") -> "UHD"
                    r.contains("1080") -> "FHD"
                    r.contains("720") -> "HD"
                    else -> null
                }
                if (q != null) put("video_quality", "string", q)
            }
            get("pref_video_fps_cm_key")?.let { f ->
                put("video_fps", "int", (f.removePrefix("FPS_").toIntOrNull() ?: 0).toString())
            }
            get("pref_camera_single_shot_sound_key")?.let { put("shutter_sound", "boolean", flag(it).toString()) }
            get("pref_camera_selfie_mirror_key")?.let { put("mirror_selfie", "boolean", flag(it).toString()) }
            get("pref_camera_recordlocation_key")?.let { put("location_tag", "boolean", flag(it).toString()) }
            get("pref_volume_key_action")?.let { v ->
                val a = when (v.lowercase()) {
                    "shutter" -> "shutter"
                    "zoom" -> "zoom"
                    else -> "none"
                }
                put("volume_action", "string", a)
            }
            get("pref_double_tap_key")?.let { v ->
                val a = when (v.lowercase()) {
                    "zoom" -> "zoom"
                    else -> "none"
                }
                put("double_tap", "string", a)
            }
            get("pref_camera_picturesize_back_key")?.let { size ->
                val parts = size.lowercase().split("x")
                val w = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                val h = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (w != null && h != null) put("photo_mp", "int", (w * h / 1e6).roundToInt().toString())
            }
            get("pref_focus_lock_time_key")?.toFloatOrNull()?.let { put("focus_lock_sec", "int", it.toInt().toString()) }
            get("pref_signtext_key")?.let { put("signature", "string", it) }
            get("prefix_key")?.takeIf { it.isNotBlank() }?.let { put("file_prefix", "string", it) }

            val ids = (1..5).mapNotNull { get("pref_manual_cameraid_back_${it}_key") }
            if (ids.isNotEmpty()) {
                val names = listOf(get("pref_manual_camera_name_key_main") ?: "1x") +
                    (2..5).map { get("pref_manual_camera_name_key_$it") ?: "ID$it" }
                put("lens_ids", "string", ids.joinToString(","))
                put("lens_names", "string", names.take(ids.size).joinToString(","))
            }
            return out
        }
    }
}
