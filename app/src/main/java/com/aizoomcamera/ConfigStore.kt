package com.aizoomcamera

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One value from a config file, kept with its original type so exports stay lossless. */
data class Entry(val type: String, val value: String = "", val items: List<String> = emptyList())

/**
 * Reads and writes the same XML layout LMC and other GCam ports use, which is the Android
 * SharedPreferences format with a map root and string, boolean, int, long, float and set children.
 *
 * Every entry in an imported file is kept and written back untouched. Only keys that start with
 * aizoom_ change how this app behaves, because the other keys drive the GCam HDR+ engine.
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

    fun toSettings(): Settings {
        val d = Settings()
        val scale = int("ai_scale", d.aiScale)
        val quality = text("video_quality", d.videoQuality).uppercase()
        return Settings(
            grid = bool("grid", d.grid),
            aiEnabled = bool("ai_enabled", d.aiEnabled),
            aiMinZoom = float("ai_min_zoom", d.aiMinZoom).coerceIn(1f, 10f),
            aiScale = if (scale == 2 || scale == 4) scale else 0,
            jpegQuality = int("jpeg_quality", d.jpegQuality).coerceIn(50, 100),
            videoQuality = if (quality in listOf("HD", "FHD", "UHD")) quality else d.videoQuality,
            stabilization = bool("video_stabilization", d.stabilization),
            exposureIndex = int("exposure_index", d.exposureIndex),
            manual = bool("manual", d.manual),
            iso = int("iso", d.iso).coerceIn(50, 12800),
            shutterDenom = int("shutter_denom", d.shutterDenom).coerceIn(2, 4000),
            doubleTapImport = bool("double_tap_import", d.doubleTapImport)
        )
    }

    fun update(s: Settings) {
        put("grid", "boolean", s.grid.toString())
        put("ai_enabled", "boolean", s.aiEnabled.toString())
        put("ai_min_zoom", "float", s.aiMinZoom.toString())
        put("ai_scale", "int", s.aiScale.toString())
        put("jpeg_quality", "int", s.jpegQuality.toString())
        put("video_quality", "string", s.videoQuality)
        put("video_stabilization", "boolean", s.stabilization.toString())
        put("exposure_index", "int", s.exposureIndex.toString())
        put("manual", "boolean", s.manual.toString())
        put("iso", "int", s.iso.toString())
        put("shutter_denom", "int", s.shutterDenom.toString())
        put("double_tap_import", "boolean", s.doubleTapImport.toString())
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
    }
}
