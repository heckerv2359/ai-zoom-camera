package com.aizoomcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.MediaStore
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val MAX_PHOTO_ZOOM = 100f
private const val MAX_VIDEO_ZOOM = 30f

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }

    val previewView = remember { PreviewView(ctx) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
        )
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var hwMax by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var videoMode by remember { mutableStateOf(false) }
    var aiOn by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(Keys.load(ctx)) }

    val maxUi = (if (videoMode) min(MAX_VIDEO_ZOOM, hwMax) else MAX_PHOTO_ZOOM).coerceAtLeast(1.01f)
    // Photos use hardware zoom up to the lens limit, the rest is a centre crop that the AI rebuilds.
    val digital = if (videoMode) 1f else max(1f, zoom / hwMax)

    LaunchedEffect(videoMode) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
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
            camera = cam
            if (videoMode) zoom = min(zoom, min(MAX_VIDEO_ZOOM, hwMax))
        }, mainExec)
    }

    LaunchedEffect(zoom, camera, hwMax) {
        camera?.cameraControl?.setZoomRatio(min(zoom, hwMax))
    }

    fun takePhoto() {
        val file = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        val d = digital
        val ai = aiOn
        val key = apiKey
        status = "Capturing"
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch { PhotoPipeline.process(ctx, file, d, ai, key) { status = it } }
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = digital
                scaleY = digital
            }
        )

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
                if (!videoMode) {
                    Text("AI", color = Color.White)
                    Switch(checked = aiOn, onCheckedChange = { aiOn = it })
                }
                TextButton(onClick = { showKey = true }) { Text("API token") }
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
