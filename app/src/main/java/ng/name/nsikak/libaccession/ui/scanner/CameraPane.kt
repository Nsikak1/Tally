package ng.name.nsikak.libaccession.ui.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ng.name.nsikak.libaccession.ui.common.OverlayBackButton
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import ng.name.nsikak.libaccession.domain.AutoZoom
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.domain.ScannedIsbn
import ng.name.nsikak.libaccession.domain.TapBox
import ng.name.nsikak.libaccession.domain.TapZoom
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ScanMode { BARCODE, TEXT }

private val SelectedGreen = Color(0xFF3DDC84)

/** Photos for text reading: up to ~5 MP is plenty for handwriting and kind to old tablets. */
private val StillResolution: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
    )
    .build()

/** Optional higher-resolution analysis, so small or distant barcodes are found. */
private val HdAnalysis: ResolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
    )
    .build()

private const val MAX_STILL_SIDE = 3000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPane(
    mode: ScanMode,
    detections: List<Detection>,
    selected: ScannedIsbn?,
    labels: Map<String, String>,
    doubleTapZoom: Float,
    onBarcodes: (List<RawBarcode>, Long) -> Unit,
    onTap: (Offset, Float) -> Boolean,
    onRegionIsbn: (ScannedIsbn) -> Unit,
    onCaptured: (Bitmap) -> Unit,
    onCapturedWithBox: (Bitmap, Rect) -> Unit,
    onBack: () -> Unit,
    onModeChange: (ScanMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val slopPx = with(LocalDensity.current) { 12.dp.toPx() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { captureExecutor.shutdown() } }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // The overlay sits above PreviewView and eats the touches, so focus and zoom are done by hand.
            isTapToFocusEnabled = false
            isPinchToZoomEnabled = false
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageCaptureResolutionSelector = StillResolution
        }
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = cameraController
        }
    }
    val autoZoomer = remember { AutoZoom() }

    var torchOn by remember { mutableStateOf(false) }
    var autoZoom by remember { mutableStateOf(true) }
    var hd by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var viewWidthPx by remember { mutableFloatStateOf(0f) }
    var viewHeightPx by remember { mutableFloatStateOf(0f) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    /** Every zoom change goes through here so [zoomRatio] always matches the camera. */
    fun applyZoom(requested: Float) {
        val state = cameraController.zoomState.value
        val ratio = requested.coerceIn(state?.minZoomRatio ?: 1f, state?.maxZoomRatio ?: 1f)
        zoomRatio = ratio
        cameraController.setZoomRatio(ratio)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            kotlinx.coroutines.delay(700)
            focusPoint = null
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(2500)
            hint = null
        }
    }

    /** Focus and meter at a point in view pixels. */
    fun focusAt(point: Offset) {
        val meteringPoint = previewView.meteringPointFactory.createPoint(point.x, point.y)
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cameraController.cameraControl?.startFocusAndMetering(action)
        focusPoint = point
    }

    // A: bind the camera to this screen's lifecycle.
    DisposableEffect(cameraController, lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
        onDispose {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
        }
    }

    // B: barcode mode runs the analyzer; text mode switches to still capture instead.
    // (Preview + analysis + capture together overloads old, LEGACY-level tablets, so only one at a time.)
    DisposableEffect(cameraController, mode) {
        var disposed = false
        var scanner: BarcodeScanner? = null

        if (mode == ScanMode.BARCODE) {
            cameraController.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            cameraController.initializationFuture.addListener({
                if (disposed) return@addListener

                val zoomCallback = ZoomSuggestionOptions.ZoomCallback { suggested ->
                    mainExecutor.execute { applyZoom(suggested) }
                    true
                }
                val options = BarcodeScannerOptions.Builder()
                    // EAN-13 carries the ISBN-13; EAN-8/UPC-A/UPC-E cover short/older barcodes; Code 128/39 cover odd labels.
                    .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                    )
                    .setZoomSuggestionOptions(
                        ZoomSuggestionOptions.Builder(zoomCallback)
                            .setMaxSupportedZoomRatio(cameraController.zoomState.value?.maxZoomRatio ?: 4f)
                            .build(),
                    )
                    .build()
                val s = BarcodeScanning.getClient(options)
                scanner = s

                cameraController.setImageAnalysisAnalyzer(
                    mainExecutor,
                    MlKitAnalyzer(
                        listOf(s),
                        ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, // boxes arrive in PreviewView pixels
                        mainExecutor,
                    ) { result ->
                        val codes = result?.getValue(s) ?: return@MlKitAnalyzer
                        val now = SystemClock.elapsedRealtime()
                        val raw = codes.mapNotNull { b ->
                            val value = b.rawValue
                            val box = b.boundingBox
                            if (value == null || box == null) {
                                null
                            } else {
                                RawBarcode(
                                    value,
                                    Rect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
                                )
                            }
                        }
                        onBarcodes(raw, now)

                        if (autoZoom) {
                            val target = raw.filter { Isbn.parse(it.value) != null }.maxByOrNull { it.box.width }
                            if (target != null) {
                                val zs = cameraController.zoomState.value
                                autoZoomer.suggest(
                                    nowMs = now,
                                    current = zoomRatio,
                                    min = zs?.minZoomRatio ?: 1f,
                                    max = zs?.maxZoomRatio ?: 1f,
                                    boxWidth = target.box.width,
                                    viewWidth = viewWidthPx,
                                )?.let { applyZoom(it) }
                            }
                        }
                    },
                )
            }, mainExecutor)
        } else {
            cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }

        onDispose {
            disposed = true
            cameraController.clearImageAnalysisAnalyzer()
            scanner?.close()
        }
    }

    LaunchedEffect(torchOn, mode) { cameraController.enableTorch(torchOn) }
    LaunchedEffect(hd) { cameraController.imageAnalysisResolutionSelector = if (hd) HdAnalysis else null }

    fun capture(then: ((Bitmap) -> Unit)? = null) {
        if (capturing) return
        capturing = true
        captureError = null
        cameraController.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toUprightBitmap(MAX_STILL_SIDE)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        image.close()
                    }
                    mainExecutor.execute {
                        capturing = false
                        if (bitmap != null) (then ?: onCaptured)(bitmap) else captureError = "Couldn't read the photo. Try again."
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute {
                        capturing = false
                        captureError = exception.message ?: "Capture failed."
                    }
                }
            },
        )
    }

    /**
     * Double-tap in barcode mode, part 1: look at the square around the finger in the current preview
     * frame. The preview has far more pixels than the small frames the live scanner analyses, so a
     * small barcode that the live scanner misses is often readable here.
     */
    fun scanRegion(p: Offset) {
        val w = viewWidthPx
        val h = viewHeightPx
        val shot = previewView.bitmap
        if (shot == null || w <= 0f || h <= 0f) {
            hint = "The camera isn't ready yet."
            return
        }
        val sx = shot.width / w
        val sy = shot.height / h
        val side = minOf(w, h) * 0.5f
        val left = (p.x - side / 2f).coerceIn(0f, (w - side).coerceAtLeast(0f))
        val top = (p.y - side / 2f).coerceIn(0f, (h - side).coerceAtLeast(0f))
        val x = (left * sx).toInt().coerceIn(0, shot.width - 1)
        val y = (top * sy).toInt().coerceIn(0, shot.height - 1)
        val cw = (side * sx).toInt().coerceIn(1, shot.width - x)
        val ch = (side * sy).toInt().coerceIn(1, shot.height - y)
        val crop = try {
            Bitmap.createBitmap(shot, x, y, cw, ch)
        } catch (t: Throwable) {
            return
        }

        val client = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                )
                .build(),
        )
        client.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { codes ->
                var best: ScannedIsbn? = null
                var bestDistance = Float.MAX_VALUE
                for (code in codes) {
                    val isbn = code.rawValue?.let { Isbn.parse(it) } ?: continue
                    val box = code.boundingBox
                    val distance = if (box == null) 0f else hypot(box.centerX() - cw / 2f, box.centerY() - ch / 2f)
                    if (distance < bestDistance) {
                        best = isbn
                        bestDistance = distance
                    }
                }
                val found = best
                if (found != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRegionIsbn(found)
                } else {
                    hint = "No ISBN barcode found at that spot."
                }
            }
            .addOnCompleteListener { client.close() }
    }

    /** Double-tap in barcode mode: read the spot, zoom toward it (Settings sets how far), and focus there. */
    fun doubleTapBarcode(p: Offset) {
        scanRegion(p)
        val zs = cameraController.zoomState.value
        val plan = TapZoom.plan(
            current = zoomRatio,
            min = zs?.minZoomRatio ?: 1f,
            max = zs?.maxZoomRatio ?: 1f,
            wanted = doubleTapZoom,
            tapX = p.x,
            tapY = p.y,
            viewW = viewWidthPx,
            viewH = viewHeightPx,
        )
        if (plan.zoom != zoomRatio) applyZoom(plan.zoom)
        scope.launch {
            delay(250) // let the zoom settle, then focus where the spot has moved to
            focusAt(Offset(plan.x, plan.y))
        }
    }

    /** Double-tap in text mode: focus there, take the photo, and put the reading box around that spot. */
    fun doubleTapText(p: Offset) {
        if (capturing) return
        focusAt(p)
        scope.launch {
            delay(600) // let the focus settle
            val w = viewWidthPx
            val h = viewHeightPx
            capture { bitmap ->
                val box = TapBox.imageBox(p.x, p.y, w, h, bitmap.width.toFloat(), bitmap.height.toFloat())
                onCapturedWithBox(bitmap, Rect(box.left, box.top, box.right, box.bottom))
            }
        }
    }

    // clipToBounds: the camera preview is cropped to fill, and must never draw outside this area.
    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewWidthPx = it.width.toFloat()
                    viewHeightPx = it.height.toFloat()
                },
        )

        ScanOverlay(
            detections = if (mode == ScanMode.BARCODE) detections else emptyList(),
            selected = selected,
            labels = labels,
            focusPoint = focusPoint,
            modifier = Modifier
                .fillMaxSize()
                // Pinch to zoom: acts only while two fingers are down.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var previousDistance = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val distance = (pressed[0].position - pressed[1].position).getDistance()
                                if (previousDistance > 0f) applyZoom(zoomRatio * distance / previousDistance)
                                previousDistance = distance
                                event.changes.forEach { it.consume() }
                            } else {
                                previousDistance = 0f
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Tap: select a barcode if one is under the finger, and always focus there.
                // Double-tap: point at a spot (see doubleTapBarcode / doubleTapText).
                .pointerInput(slopPx, mode, doubleTapZoom) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (mode == ScanMode.BARCODE) doubleTapBarcode(offset) else doubleTapText(offset)
                        },
                        onTap = { offset ->
                            if (onTap(offset, slopPx)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            focusAt(offset)
                        },
                    )
                },
        )

        // Top controls: back + mode switch, then a scrollable row of camera chips.
        // They are drawn last, so they always sit on top of the preview.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                OverlayBackButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
                ModeSwitch(mode = mode, onChange = onModeChange, modifier = Modifier.align(Alignment.Center))
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CameraChip("Torch", torchOn) { torchOn = !torchOn }
                if (mode == ScanMode.BARCODE) {
                    CameraChip("Auto-zoom", autoZoom) { autoZoom = !autoZoom }
                    CameraChip("HD", hd) { hd = !hd }
                }
                if (zoomRatio > 1.05f) {
                    CameraChip("%.1f× · reset".format(zoomRatio), false) { applyZoom(1f) }
                }
            }
        }

        if (mode == ScanMode.TEXT) {
            // A camera-style shutter ring.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.White.copy(alpha = 0.4f) else Color.White)
                    .clickable(enabled = !capturing) { capture() }
                    .semantics { contentDescription = "Capture photo" },
            )
        }

        captureError?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        hint?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The Barcode | Text pill. */
@Composable
private fun ModeSwitch(mode: ScanMode, onChange: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.55f), modifier = modifier) {
        Row(Modifier.padding(4.dp)) {
            ModeOption("Barcode", mode == ScanMode.BARCODE) { onChange(ScanMode.BARCODE) }
            ModeOption("Text", mode == ScanMode.TEXT) { onChange(ScanMode.TEXT) }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = null,
    )
}

/** JPEG still -> upright bitmap, shrunk so its long side is at most [maxSide]. */
private fun ImageProxy.toUprightBitmap(maxSide: Int): Bitmap {
    val raw = toBitmap()
    val rotation = imageInfo.rotationDegrees
    val longSide = maxOf(raw.width, raw.height)
    val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
    if (rotation == 0 && scale == 1f) return raw
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        postScale(scale, scale)
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

@Composable
private fun ScanOverlay(
    detections: List<Detection>,
    selected: ScannedIsbn?,
    labels: Map<String, String>,
    focusPoint: Offset?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val accent = MaterialTheme.colorScheme.tertiary
    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )

    Canvas(modifier = modifier) {
        val corner = CornerRadius(8.dp.toPx())

        detections.forEach { d ->
            val isSelected = d.isbn == selected
            val base = if (isSelected) SelectedGreen else accent
            val color = if (d.fading) base.copy(alpha = 0.45f) else base
            val topLeft = Offset(d.box.left, d.box.top)
            val boxSize = ComposeSize(d.box.width, d.box.height)

            drawRoundRect(color.copy(alpha = if (isSelected) 0.28f else 0.12f), topLeft, boxSize, corner)
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = corner,
                style = Stroke(width = (if (isSelected) 4.dp else 2.5.dp).toPx()),
            )

            // Label above the box (below it if there is no room above): the book's title once the
            // server has answered, the ISBN until then.
            val labelText = labels[d.isbn.isbn13]?.let { if (it.length > 28) it.take(27) + "…" else it } ?: d.isbn.isbn13
            val layout = measurer.measure(labelText, labelStyle)
            val pad = 4.dp.toPx()
            val labelW = layout.size.width + 2 * pad
            val labelH = layout.size.height + pad
            val lx = d.box.left.coerceIn(0f, (size.width - labelW).coerceAtLeast(0f))
            val above = d.box.top - labelH - 2.dp.toPx()
            val ly = if (above >= 0f) above else d.box.bottom + 2.dp.toPx()
            drawRoundRect(
                Color.Black.copy(alpha = if (d.fading) 0.4f else 0.7f),
                Offset(lx, ly),
                ComposeSize(labelW, labelH),
                CornerRadius(4.dp.toPx()),
            )
            drawText(layout, topLeft = Offset(lx + pad, ly + pad / 2))
        }

        focusPoint?.let {
            drawCircle(Color.White, radius = 28.dp.toPx(), center = it, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
