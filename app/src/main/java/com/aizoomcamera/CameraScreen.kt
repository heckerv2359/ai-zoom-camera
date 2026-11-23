package com.aizoomcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val MAX_PHOTO_ZOOM = 100f
private const val MAX_VIDEO_ZOOM = 30f

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }
    val store = remember { ConfigStore(ctx) }

    var settings by remember { mutableStateOf(store.toSettings()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hwMax by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var videoMode by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(Keys.load(ctx)) }

    var expMin by remember { mutableIntStateOf(0) }
    var expMax by remember { mutableIntStateOf(0) }
    var isoLo by remember { mutableIntStateOf(50) }
    var isoHi by remember { mutableIntStateOf(3200) }
    var shutterLoNs by remember { mutableStateOf(10_000L) }
    var shutterHiNs by remember { mutableStateOf(1_000_000_000L) }

    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val videoCapture = remember(settings.videoQuality, settings.stabilization) {
        val quality = when (settings.videoQuality) {
            "UHD" -> Quality.UHD
            "HD" -> Quality.HD
            else -> Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .build()
        VideoCapture.Builder(recorder)
            .setVideoStabilizationEnabled(settings.stabilization)
            .build()
    }

    val maxUi = (if (videoMode) min(MAX_VIDEO_ZOOM, hwMax) else MAX_PHOTO_ZOOM).coerceAtLeast(1.01f)
    // Photos use hardware zoom up to the lens limit, the rest is a centre crop that the AI rebuilds.
    val digital = if (videoMode) 1f else max(1f, zoom / hwMax)

    fun change(s: Settings) {
        settings = s
        store.update(s)
    }

    fun importConfig(uri: Uri) {
        scope.launch {
            status = try {
                val parsed = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { ConfigStore.parse(it) }
                }
                store.merge(parsed)
                settings = store.toSettings()
                val used = parsed.keys.count { it.startsWith(ConfigStore.PREFIX) }
                "Imported ${parsed.size} entries, $used used by this app"
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
        }
    }

    fun exportConfig(uri: Uri) {
        scope.launch {
            status = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { store.write(it) }
                }
                "Config exported"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importConfig(uri) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri: Uri? -> if (uri != null) exportConfig(uri) }

    LaunchedEffect(settings) {
        delay(500)
        store.save()
    }

    LaunchedEffect(videoMode, videoCapture) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    if (videoMode) videoCapture else imageCapture
                )
                hwMax = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f

                val expRange = cam.cameraInfo.exposureState.exposureCompensationRange
                expMin = expRange.lower
                expMax = expRange.upper

                val info = Camera2CameraInfo.from(cam.cameraInfo)
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    isoLo = it.lower
                    isoHi = it.upper
                }
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    shutterLoNs = it.lower
                    shutterHiNs = it.upper
                }

                camera = cam
                if (videoMode) zoom = min(zoom, min(MAX_VIDEO_ZOOM, hwMax))
            } catch (e: Exception) {
                status = "This phone rejected the camera setup: ${e.message}"
            }
        }, mainExec)
    }

    LaunchedEffect(zoom, camera, hwMax) {
        camera?.cameraControl?.setZoomRatio(min(zoom, hwMax))
    }

    LaunchedEffect(
        camera, settings.manual, settings.iso, settings.shutterDenom, settings.exposureIndex, expMin, expMax
    ) {
        val cam = camera ?: return@LaunchedEffect
        if (expMax > expMin) {
            cam.cameraControl.setExposureCompensationIndex(settings.exposureIndex.coerceIn(expMin, expMax))
        }
        val options = CaptureRequestOptions.Builder()
        if (settings.manual) {
            val ns = (1_000_000_000.0 / settings.shutterDenom).toLong().coerceIn(shutterLoNs, shutterHiNs)
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso.coerceIn(isoLo, isoHi))
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
    }

    fun takePhoto() {
        val file = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        val d = digital
        val s = settings
        val key = apiKey
        status = "Capturing"
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch { PhotoPipeline.process(ctx, file, d, key, s) { status = it } }
                }

                override fun onError(e: ImageCaptureException) {
                    status = "Capture failed"
                }
            }
        )
    }

    fun toggleRecord() {
        val current = recording
        if (current != null) {
            current.stop()
            recording = null
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AIZoom_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIZoomCamera")
        }
        val options = MediaStoreOutputOptions.Builder(
            ctx.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val hasAudio = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var pending = videoCapture.output.prepareRecording(ctx, options)
        if (hasAudio) pending = pending.withAudioEnabled()

        status = "Recording"
        recording = pending.start(mainExec) { event ->
            if (event is VideoRecordEvent.Finalize) {
                status = if (event.hasError()) "Video error" else "Video saved"
                recording = null
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(settings.doubleTapImport) {
                detectTapGestures(onDoubleTap = {
                    if (settings.doubleTapImport) importLauncher.launch(arrayOf("*/*"))
                })
            }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = digital
                scaleY = digital
            }
        )

        if (settings.grid) {
            Canvas(Modifier.fillMaxSize()) {
                val line = Color(0x88FFFFFF)
                for (i in 1..2) {
                    val x = size.width * i / 3
                    val y = size.height * i / 3
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 2f)
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 2f)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (status.isNotEmpty()) Text(status, color = Color.White)
            Text("%.1fx".format(zoom), color = Color.White)
            Slider(
                value = (ln(zoom) / ln(maxUi)).coerceIn(0f, 1f),
                onValueChange = { zoom = maxUi.pow(it) }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !videoMode,
                    onClick = { recording?.stop(); recording = null; videoMode = false },
                    label = { Text("Photo") }
                )
                FilterChip(
                    selected = videoMode,
                    onClick = { videoMode = true },
                    label = { Text("Video") }
                )
                TextButton(onClick = { showSettings = true }) { Text("Settings") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (videoMode) toggleRecord() else takePhoto() }) {
                Text(
                    when {
                        !videoMode -> "Capture"
                        recording != null -> "Stop"
                        else -> "Record"
                    }
                )
            }
        }

        if (showSettings) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xEE000000))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Settings", color = Color.White)

                SwitchRow("Grid", settings.grid) { change(settings.copy(grid = it)) }
                SwitchRow("AI enhance", settings.aiEnabled) { change(settings.copy(aiEnabled = it)) }
                SliderRow(
                    "AI starts at %.1fx crop".format(settings.aiMinZoom),
                    settings.aiMinZoom, 1f..10f, 17
                ) { change(settings.copy(aiMinZoom = it)) }
                ChoiceRow(
                    "AI upscale", listOf("Auto", "2x", "4x"),
                    when (settings.aiScale) { 2 -> "2x"; 4 -> "4x"; else -> "Auto" }
                ) { change(settings.copy(aiScale = when (it) { "2x" -> 2; "4x" -> 4; else -> 0 })) }
                SliderRow(
                    "JPEG quality ${settings.jpegQuality}",
                    settings.jpegQuality.toFloat(), 50f..100f
                ) { change(settings.copy(jpegQuality = it.toInt())) }

                SwitchRow("Manual exposure", settings.manual) { change(settings.copy(manual = it)) }
                if (settings.manual) {
                    SliderRow(
                        "ISO ${settings.iso.coerceIn(isoLo, isoHi)}",
                        settings.iso.toFloat(), isoLo.toFloat()..isoHi.toFloat()
                    ) { change(settings.copy(iso = it.toInt())) }
                    SliderRow(
                        "Shutter 1/${settings.shutterDenom}",
                        ln(settings.shutterDenom.toFloat()), ln(2f)..ln(4000f)
                    ) { change(settings.copy(shutterDenom = exp(it).toInt().coerceIn(2, 4000))) }
                }
                if (expMax > expMin) {
                    SliderRow(
                        "Exposure compensation ${settings.exposureIndex.coerceIn(expMin, expMax)}",
                        settings.exposureIndex.toFloat(), expMin.toFloat()..expMax.toFloat(),
                        expMax - expMin - 1
                    ) { change(settings.copy(exposureIndex = it.toInt())) }
                }

                ChoiceRow("Video quality", listOf("HD", "FHD", "UHD"), settings.videoQuality) {
                    change(settings.copy(videoQuality = it))
                }
                SwitchRow("Video stabilization", settings.stabilization) {
                    change(settings.copy(stabilization = it))
                }
                SwitchRow("Double tap opens config import", settings.doubleTapImport) {
                    change(settings.copy(doubleTapImport = it))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import XML") }
                    Button(onClick = { exportLauncher.launch("AIZoom_config.xml") }) { Text("Export XML") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showKey = true }) { Text("API token") }
                    TextButton(onClick = {
                        store.reset()
                        settings = Settings()
                    }) { Text("Reset settings") }
                }
                Button(onClick = { showSettings = false }) { Text("Close") }
            }
        }
    }

    if (showKey) {
        var text by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Replicate API token") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Keys.save(ctx, text)
                    apiKey = text.trim()
                    showKey = false
                }) { Text("Save") }
            }
        )
    }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
