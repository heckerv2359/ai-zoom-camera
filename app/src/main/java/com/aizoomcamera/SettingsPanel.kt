package com.aizoomcamera

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.exp
import kotlin.math.ln

@Composable
fun SettingsPanel(
    s: Settings,
    onChange: (Settings) -> Unit,
    expMin: Int,
    expMax: Int,
    isoLo: Int,
    isoHi: Int,
    canFocus: Boolean,
    onLocation: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onToken: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Settings", color = Color.White)

        Heading("Photo")
        SwitchRow("AI enhance", s.aiEnabled) { onChange(s.copy(aiEnabled = it)) }
        SliderRow("AI starts at %.1fx crop".format(s.aiMinZoom), s.aiMinZoom, 1f..10f, 17) {
            onChange(s.copy(aiMinZoom = it))
        }
        ChoiceRow(
            "AI upscale", listOf("Auto", "2x", "4x"),
            when (s.aiScale) { 2 -> "2x"; 4 -> "4x"; else -> "Auto" }
        ) { onChange(s.copy(aiScale = when (it) { "2x" -> 2; "4x" -> 4; else -> 0 })) }
        SliderRow("On device sharpness %.1f".format(s.sharpness), s.sharpness, 0f..1.5f) {
            onChange(s.copy(sharpness = it))
        }
        SliderRow("Contrast %.2f".format(s.contrast), s.contrast, 0.5f..1.5f) {
            onChange(s.copy(contrast = it))
        }
        SliderRow("Saturation %.2f".format(s.saturation), s.saturation, 0f..2f) {
            onChange(s.copy(saturation = it))
        }
        SliderRow("Brightness ${s.brightness}", s.brightness.toFloat(), -50f..50f) {
            onChange(s.copy(brightness = it.toInt()))
        }
        SliderRow("JPEG quality ${s.jpegQuality}", s.jpegQuality.toFloat(), 50f..100f) {
            onChange(s.copy(jpegQuality = it.toInt()))
        }
        ChoiceRow(
            "Photo size", listOf("Max", "12MP", "8MP", "4MP"),
            when (s.photoMp) { 12 -> "12MP"; 8 -> "8MP"; 4 -> "4MP"; 0 -> "Max"; else -> "" }
        ) { onChange(s.copy(photoMp = when (it) { "12MP" -> 12; "8MP" -> 8; "4MP" -> 4; else -> 0 })) }
        SliderRow("Max photo zoom ${s.maxPhotoZoom}x", s.maxPhotoZoom.toFloat(), 2f..100f) {
            onChange(s.copy(maxPhotoZoom = it.toInt()))
        }
        SwitchRow("Grid", s.grid) { onChange(s.copy(grid = it)) }
        ChoiceRow("Grid style", listOf("3x3", "4x4", "Golden", "Center"), s.gridStyle) {
            onChange(s.copy(gridStyle = it))
        }
        TextRow("Signature text on edited copies", s.signature) { onChange(s.copy(signature = it.take(60))) }
        TextRow("File name prefix", s.filePrefix) {
            onChange(s.copy(filePrefix = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(24)))
        }

        Heading("Exposure and focus")
        SwitchRow("Manual exposure", s.manual) { onChange(s.copy(manual = it)) }
        if (s.manual) {
            SliderRow("ISO ${s.iso.coerceIn(isoLo, isoHi)}", s.iso.toFloat(), isoLo.toFloat()..isoHi.toFloat()) {
                onChange(s.copy(iso = it.toInt()))
            }
            SliderRow("Shutter 1/${s.shutterDenom}", ln(s.shutterDenom.toFloat()), ln(2f)..ln(4000f)) {
                onChange(s.copy(shutterDenom = exp(it).toInt().coerceIn(2, 4000)))
            }
        }
        if (expMax > expMin) {
            SliderRow(
                "Exposure compensation ${s.exposureIndex.coerceIn(expMin, expMax)}",
                s.exposureIndex.toFloat(), expMin.toFloat()..expMax.toFloat(), expMax - expMin - 1
            ) { onChange(s.copy(exposureIndex = it.toInt())) }
        }
        SwitchRow("Manual focus", s.manualFocus) { onChange(s.copy(manualFocus = it)) }
        if (s.manualFocus) {
            if (canFocus) {
                SliderRow("Focus distance, left is far and right is near", s.focusDistance, 0f..1f) {
                    onChange(s.copy(focusDistance = it))
                }
            } else {
                Text("This lens does not report a focus range", color = Color.White)
            }
        }
        SliderRow(
            if (s.focusLockSec == 0) "Focus lock stays until you tap again" else "Focus lock ${s.focusLockSec} s",
            s.focusLockSec.toFloat(), 0f..10f, 9
        ) { onChange(s.copy(focusLockSec = it.toInt())) }
        ChoiceRow(
            "White balance", listOf("Auto", "Daylight", "Cloudy", "Shade", "Incandescent", "Fluorescent"),
            s.whiteBalance
        ) { onChange(s.copy(whiteBalance = it)) }
        ChoiceRow("Anti banding", listOf("Auto", "50Hz", "60Hz", "Off"), s.antiBanding) {
            onChange(s.copy(antiBanding = it))
        }

        Heading("Video")
        ChoiceRow("Video quality", listOf("HD", "FHD", "UHD"), s.videoQuality) {
            onChange(s.copy(videoQuality = it))
        }
        ChoiceRow(
            "Frame rate", listOf("Auto", "24", "30", "60"),
            if (s.videoFps == 0) "Auto" else s.videoFps.toString()
        ) { onChange(s.copy(videoFps = it.toIntOrNull() ?: 0)) }
        SliderRow(
            if (s.videoBitrate == 0) "Video bitrate Auto" else "Video bitrate ${s.videoBitrate} Mbps",
            s.videoBitrate.toFloat(), 0f..60f
        ) { onChange(s.copy(videoBitrate = it.toInt())) }
        SwitchRow("Video stabilization", s.stabilization) { onChange(s.copy(stabilization = it)) }
        SliderRow("Max video zoom ${s.maxVideoZoom}x", s.maxVideoZoom.toFloat(), 2f..100f) {
            onChange(s.copy(maxVideoZoom = it.toInt()))
        }

        Heading("Controls")
        ChoiceRow(
            "Double tap", listOf("Import", "Zoom", "Off"),
            when (s.doubleTap) { "zoom" -> "Zoom"; "none" -> "Off"; else -> "Import" }
        ) { onChange(s.copy(doubleTap = when (it) { "Zoom" -> "zoom"; "Off" -> "none"; else -> "import" })) }
        ChoiceRow(
            "Volume keys", listOf("Shutter", "Zoom", "Off"),
            when (s.volumeAction) { "zoom" -> "Zoom"; "none" -> "Off"; else -> "Shutter" }
        ) { onChange(s.copy(volumeAction = when (it) { "Zoom" -> "zoom"; "Off" -> "none"; else -> "shutter" })) }
        SwitchRow("Shutter sound", s.shutterSound) { onChange(s.copy(shutterSound = it)) }
        SwitchRow("Mirror selfies", s.mirrorSelfie) { onChange(s.copy(mirrorSelfie = it)) }
        SwitchRow("Save location in photos", s.locationTag) { onLocation(it) }

        Heading("Config")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport) { Text("Import XML") }
            Button(onClick = onExport) { Text("Export XML") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onToken) { Text("API token") }
            TextButton(onClick = onReset) { Text("Reset settings") }
        }
        Button(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun Heading(title: String) {
    Text(title, color = Color(0xFF9E9E9E))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Text(label, color = Color.White)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0)
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label, color = Color.White)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option) }
                )
            }
        }
    }
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Color.White)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
