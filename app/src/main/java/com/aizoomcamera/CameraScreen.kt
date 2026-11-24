package com.aizoomcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationManager
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Lens(val id: String, val name: String)

private val MODES = listOf("Photo", "Auto", "HDR", "Night", "Portrait", "Video")

/** Finds the rear cameras the phone exposes and names them by zoom factor against the main lens. */
@OptIn(ExperimentalCamera2Interop::class)
private fun detectLenses(provider: ProcessCameraProvider): List<Lens> {
    val found = ArrayList<Pair<String, Float>>()
    for (info in provider.availableCameraInfos) {
        val c2 = Camera2CameraInfo.from(info)
        val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
        val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (f != null && size != null) f * 43.27f / hypot(size.width, size.height) else 0f
        if (found.none { it.first == c2.cameraId }) found.add(Pair(c2.cameraId, equiv))
    }
    if (found.size < 2) return emptyList()
    val main = found.firstOrNull { it.first == "0" }?.second?.takeIf { it > 0f }
        ?: found.firstOrNull { it.second > 0f }?.second
        ?: 0f
    return found.sortedBy { it.second }.map { (id, equiv) ->
        val name = if (equiv > 0f && main > 0f) {
            val r = (equiv / main * 10f).roundToInt() / 10f
            if (r == r.toInt().toFloat()) "${r.toInt()}x" else "${r}x"
        } else "ID$id"
        Lens(id, name)
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }
    val store = remember { ConfigStore(ctx) }
    val sound = remember { MediaActionSound() }

    var settings by remember { mutableStateOf(store.toSettings()) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hwMax by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var mode by remember { mutableStateOf("Photo") }
    var front by remember { mutableStateOf(false) }
    var lensId by remember { mutableStateOf<String?>(null) }
    var detected by remember { mutableStateOf<List<Lens>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var showKey by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(Keys.load(ctx)) }

    var flash by remember { mutableIntStateOf(0) }
    var hasFlash by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf("4:3") }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }

    var expMin by remember { mutableIntStateOf(0) }
    var expMax by remember { mutableIntStateOf(0) }
    var isoLo by remember { mutableIntStateOf(50) }
    var isoHi by remember { mutableIntStateOf(3200) }
    var shutterLoNs by remember { mutableStateOf(10_000L) }
    var shutterHiNs by remember { mutableStateOf(1_000_000_000L) }
    var minFocus by remember { mutableFloatStateOf(0f) }

    val videoMode = mode == "Video"
    val wide = ratio == "16:9"
    val viewRatio = if (videoMode || wide) 9f / 16f else 3f / 4f

    val lensList: List<Lens> = run {
        val ids = settings.lensIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isNotEmpty()) {
            val names = settings.lensNames.split(",").map { it.trim() }
            ids.mapIndexed { i, id -> Lens(id, names.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "ID$id") }
        } else detected
    }

    val previewView = remember { PreviewView(ctx) }

    val imageCapture = remember(wide, settings.photoMp) {
        val rs = ResolutionSelector.Builder().setAspectRatioStrategy(
            if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        )
        if (settings.photoMp > 0) {
            val r = if (wide) 16.0 / 9.0 else 4.0 / 3.0
            val w = sqrt(settings.photoMp * 1_000_000.0 * r).toInt()
            val h = (w / r).toInt()
            rs.setResolutionStrategy(
                ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
        } else {
            rs.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        }
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(rs.build())
            .build()
    }

    val videoCapture = remember(settings.videoQuality, settings.videoBitrate) {
        val quality = when (settings.videoQuality) {
            "UHD" -> Quality.UHD
            "HD" -> Quality.HD
            else -> Quality.FHD
        }
        val builder = Recorder.Builder().setQualitySelector(
            QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        )
        if (settings.videoBitrate > 0) builder.setTargetVideoEncodingBitRate(settings.videoBitrate * 1_000_000)
        VideoCapture.withOutput(builder.build())
    }

    val maxUi = (if (videoMode) min(settings.maxVideoZoom.toFloat(), hwMax) else settings.maxPhotoZoom.toFloat())
        .coerceAtLeast(1.01f)
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
                val mapped = ConfigStore.translate(parsed)
                val all = LinkedHashMap<String, Entry>(mapped)
                all.putAll(parsed)
                store.merge(all)
                settings = store.toSettings()
                val used = mapped.size + parsed.keys.count { it.startsWith(ConfigStore.PREFIX) }
                "Imported ${parsed.size} entries, $used applied to this app"
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

    fun openLast() {
        val u = lastUri ?: return
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            status = "No gallery app found"
        }
    }

    fun shareLast() {
        val u = lastUri ?: return
        try {
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, u)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Share photo"))
        } catch (e: Exception) {
            status = "Could not open the share sheet"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importConfig(uri) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri: Uri? -> if (uri != null) exportConfig(uri) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) change(settings.copy(locationTag = true))
        else status = "Location permission was denied"
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun setLocation(on: Boolean) {
        if (!on) {
            change(settings.copy(locationTag = false))
        } else if (hasLocationPermission()) {
            change(settings.copy(locationTag = true))
        } else {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    fun currentLocation(): Location? {
        if (!settings.locationTag || !hasLocationPermission()) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val builder = FocusMeteringAction.Builder(point)
            if (settings.focusLockSec > 0) {
                builder.setAutoCancelDuration(settings.focusLockSec.toLong(), TimeUnit.SECONDS)
            } else {
                builder.disableAutoCancel()
            }
            cam.cameraControl.startFocusAndMetering(builder.build())
            status = "Focusing"
        } catch (e: Exception) {
            status = "Could not focus here"
        }
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, maxUi)
    }

    fun selectMode(m: String) {
        if (m != "Video") {
            recording?.stop()
            recording = null
        }
        mode = m
    }

    fun switchCamera() {
        recording?.stop()
        recording = null
        front = !front
        lensId = null
        zoom = 1f
    }

    fun takePhoto() {
        val file = File(ctx.cacheDir, "raw_${System.currentTimeMillis()}.jpg")
        val d = digital
        val s = settings
        val key = apiKey
        val mirror = front && s.mirrorSelfie
        val loc = currentLocation()
        status = "Capturing"
        if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExec,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        PhotoPipeline.process(ctx, file, d, key, s, mirror, loc, { lastUri = it }) {
                            status = it
                        }
                    }
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
            put(MediaStore.Video.Media.DISPLAY_NAME, "${settings.filePrefix}_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AIZoomCamera")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            ctx.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val hasAudio = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var pending = videoCapture.output.prepareRecording(ctx, outputOptions)
        if (hasAudio) pending = pending.withAudioEnabled()

        status = "Recording"
        recording = pending.start(mainExec) { event ->
            if (event is VideoRecordEvent.Finalize) {
                status = if (event.hasError()) "Video error" else "Video saved"
                recording = null
            }
        }
    }

    fun shoot() {
        if (videoMode) toggleRecord() else takePhoto()
    }

    fun onDoubleTap() {
        when (settings.doubleTap) {
            "zoom" -> zoom = if (zoom < 1.5f) min(2f, maxUi) else 1f
            "import" -> importLauncher.launch(arrayOf("*/*"))
        }
    }

    fun fail(e: Exception) {
        status = "Camera setup failed: ${e.message}"
        if (lensId != null) lensId = null
    }

    fun pickSelector(): CameraSelector {
        val id = lensId
        return when {
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            id != null -> CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                .build()
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    fun bindUseCases(provider: ProcessCameraProvider, selector: CameraSelector) {
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(
                    if (videoMode || wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                ).build()
            )
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        val cam = provider.bindToLifecycle(
            owner,
            selector,
            preview,
            if (videoMode) videoCapture else imageCapture
        )
        hwMax = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        hasFlash = cam.cameraInfo.hasFlashUnit()

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
        minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        camera = cam
        if (videoMode) zoom = min(zoom, min(settings.maxVideoZoom.toFloat(), hwMax))
    }

    LaunchedEffect(settings) {
        delay(500)
        store.save()
    }

    // Show the newest photo this app saved before, so the thumbnail is not empty on launch.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("Pictures/AIZoomCamera%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        lastUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                        )
                    }
                }
            } catch (e: Exception) {
                // No previous photos or no access, the thumbnail just stays empty.
            }
        }
    }

    LaunchedEffect(lastUri) {
        val u = lastUri
        thumb = if (u == null) null else withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(u, Size(256, 256), null).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    LaunchedEffect(mode, videoCapture, imageCapture, front, lensId) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                if (detected.isEmpty()) detected = detectLenses(provider)
                val base = pickSelector()
                if (mode == "Photo" || mode == "Video") {
                    bindUseCases(provider, base)
                } else {
                    val extFuture = ExtensionsManager.getInstanceAsync(ctx, provider)
                    extFuture.addListener({
                        try {
                            val ext = extFuture.get()
                            val extMode = when (mode) {
                                "Auto" -> ExtensionMode.AUTO
                                "HDR" -> ExtensionMode.HDR
                                "Night" -> ExtensionMode.NIGHT
                                else -> ExtensionMode.BOKEH
                            }
                            if (ext.isExtensionAvailable(base, extMode)) {
                                bindUseCases(provider, ext.getExtensionEnabledCameraSelector(base, extMode))
                            } else {
                                status = "$mode is not supported by this camera"
                                bindUseCases(provider, base)
                            }
                        } catch (e: Exception) {
                            fail(e)
                        }
                    }, mainExec)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }, mainExec)
    }

    LaunchedEffect(zoom, camera, hwMax) {
        camera?.cameraControl?.setZoomRatio(min(zoom, hwMax))
    }

    LaunchedEffect(camera, flash, videoMode, imageCapture) {
        imageCapture.flashMode = when (flash) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        // In video mode the flash button works as a torch.
        camera?.cameraControl?.enableTorch(videoMode && flash == 2)
    }

    LaunchedEffect(
        camera, videoMode, settings.stabilization, settings.manual, settings.iso, settings.shutterDenom,
        settings.exposureIndex, settings.manualFocus, settings.focusDistance, settings.whiteBalance,
        settings.antiBanding, settings.videoFps, expMin, expMax, minFocus
    ) {
        val cam = camera ?: return@LaunchedEffect
        if (expMax > expMin) {
            cam.cameraControl.setExposureCompensationIndex(settings.exposureIndex.coerceIn(expMin, expMax))
        }
        val o = CaptureRequestOptions.Builder()
        if (settings.manual) {
            val ns = (1_000_000_000.0 / settings.shutterDenom).toLong().coerceIn(shutterLoNs, shutterHiNs)
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            o.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso.coerceIn(isoLo, isoHi))
            o.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        }
        if (settings.manualFocus && minFocus > 0f) {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            o.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance * minFocus)
        }
        val awb = when (settings.whiteBalance) {
            "Daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "Cloudy" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "Shade" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            "Incandescent" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "Fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> null
        }
        if (awb != null) o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
        val banding = when (settings.antiBanding) {
            "50Hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            "60Hz" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            "Off" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> null
        }
        if (banding != null) o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, banding)
        if (videoMode && settings.videoFps > 0) {
            o.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(settings.videoFps, settings.videoFps)
            )
        }
        if (videoMode && settings.stabilization) {
            o.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(o.build())
    }

    SideEffect {
        KeyBus.handler = { code, rep ->
            val volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            when {
                volume && settings.volumeAction == "shutter" -> {
                    if (rep == 0) shoot()
                    true
                }
                volume && settings.volumeAction == "zoom" -> {
                    zoomBy(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1.15f else 1f / 1.15f)
                    true
                }
                code == KeyEvent.KEYCODE_CAMERA && rep == 0 -> {
                    shoot()
                    true
                }
                code == KeyEvent.KEYCODE_ENTER && rep == 0 && !showSettings -> {
                    shoot()
                    true
                }
                code == KeyEvent.KEYCODE_DPAD_UP -> {
                    zoomBy(1.15f)
                    true
                }
                code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    zoomBy(1f / 1.15f)
                    true
                }
                code == KeyEvent.KEYCODE_S && rep == 0 -> {
                    switchCamera()
                    true
                }
                code == KeyEvent.KEYCODE_RIGHT_BRACKET && rep == 0 -> {
                    selectMode(MODES[(MODES.indexOf(mode) + 1) % MODES.size])
                    true
                }
                code == KeyEvent.KEYCODE_LEFT_BRACKET && rep == 0 -> {
                    selectMode(MODES[(MODES.indexOf(mode) + MODES.size - 1) % MODES.size])
                    true
                }
                else -> false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { KeyBus.handler = null }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(maxUi) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, maxUi)
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(viewRatio)
                .clipToBounds()
                .pointerInput(settings.doubleTap, camera) {
                    detectTapGestures(
                        onTap = { o -> focusAt(o.x, o.y) },
                        onDoubleTap = { onDoubleTap() }
                    )
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
                    val fractions = when (settings.gridStyle) {
                        "4x4" -> listOf(0.25f, 0.5f, 0.75f)
                        "Golden" -> listOf(0.382f, 0.618f)
                        "Center" -> listOf(0.5f)
                        else -> listOf(1f / 3f, 2f / 3f)
                    }
                    for (fr in fractions) {
                        val x = size.width * fr
                        val y = size.height * fr
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), 2f)
                        drawLine(line, Offset(0f, y), Offset(size.width, y), 2f)
                    }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasFlash) {
                FilterChip(
                    selected = flash != 0,
                    onClick = { flash = (flash + 1) % 3 },
                    label = {
                        Text(
                            when (flash) {
                                1 -> "Flash auto"
                                2 -> "Flash on"
                                else -> "Flash off"
                            }
                        )
                    }
                )
            }
            FilterChip(
                selected = settings.grid,
                onClick = { change(settings.copy(grid = !settings.grid)) },
                label = { Text("Grid") }
            )
            if (!videoMode) {
                FilterChip(
                    selected = false,
                    onClick = { ratio = if (ratio == "4:3") "16:9" else "4:3" },
                    label = { Text(ratio) }
                )
                FilterChip(
                    selected = settings.aiEnabled,
                    onClick = { change(settings.copy(aiEnabled = !settings.aiEnabled)) },
                    label = { Text("HD") }
                )
            } else {
                FilterChip(
                    selected = true,
                    onClick = {
                        val next = when (settings.videoQuality) {
                            "HD" -> "FHD"
                            "FHD" -> "UHD"
                            else -> "HD"
                        }
                        change(settings.copy(videoQuality = next))
                    },
                    label = { Text(settings.videoQuality) }
                )
            }
            FilterChip(
                selected = front,
                onClick = { switchCamera() },
                label = { Text("Flip") }
            )
            TextButton(onClick = { showSettings = true }) { Text("More") }
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
            if (!front && lensList.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lensList.forEach { l ->
                        FilterChip(
                            selected = lensId == l.id,
                            onClick = {
                                lensId = l.id
                                zoom = 1f
                            },
                            label = { Text(l.name) }
                        )
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MODES.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { selectMode(m) },
                        label = { Text(m) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    val t = thumb
                    if (t != null) {
                        Image(
                            bitmap = t,
                            contentDescription = "Last photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .pointerInput(lastUri) {
                                    detectTapGestures(
                                        onTap = { openLast() },
                                        onLongPress = { shareLast() }
                                    )
                                }
                        )
                    } else {
                        Spacer(Modifier.size(56.dp))
                    }
                }
                Button(onClick = { shoot() }) {
                    Text(
                        when {
                            !videoMode -> "Capture"
                            recording != null -> "Stop"
                            else -> "Record"
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }

        if (showSettings) {
            SettingsPanel(
                s = settings,
                onChange = { change(it) },
                expMin = expMin,
                expMax = expMax,
                isoLo = isoLo,
                isoHi = isoHi,
                canFocus = minFocus > 0f,
                onLocation = { setLocation(it) },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onExport = { exportLauncher.launch("AIZoom_config.xml") },
                onToken = { showKey = true },
                onReset = {
                    store.reset()
                    settings = Settings()
                },
                onClose = { showSettings = false }
            )
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
