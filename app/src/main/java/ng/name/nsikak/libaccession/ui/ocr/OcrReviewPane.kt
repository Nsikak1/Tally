package ng.name.nsikak.libaccession.ui.ocr

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.domain.AccessionFormat
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.ui.common.OverlayBackButton
import ng.name.nsikak.libaccession.ui.common.PanelContainer

private val BoxGreen = Color(0xFF3DDC84)
private val LineAmber = Color(0xFFF2B134)

private enum class Grab { NEW, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * The photo review screen: draw a box (or tap an outlined line), the text is read, you edit it
 * in the field and confirm. Drag inside the box to move it; drag a corner to resize it.
 * Portrait: photo above, panel below. Landscape: photo on the left, panel on the right.
 */
@Composable
fun OcrReviewPane(vm: OcrViewModel, ui: OcrUiState, landscape: Boolean, modifier: Modifier = Modifier) {
    val image = ui.image ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageBitmap = remember(image) { image.asImageBitmap() }

    val photo: @Composable (Modifier) -> Unit = { m ->
        PhotoArea(
            image = image,
            imageBitmap = imageBitmap,
            ui = ui,
            onBox = vm::setBox,
            onLine = vm::selectLine,
            onClose = vm::retake,
            modifier = m,
        )
    }
    val results: @Composable (Modifier) -> Unit = { m ->
        ResultPanel(
            ui = ui,
            landscape = landscape,
            modifier = m,
            onMode = vm::setMode,
            onText = vm::onTextEdited,
            onAlternative = vm::useAlternative,
            onConfirm = vm::confirm,
            onRetake = vm::retake,
            onShare = {
                scope.launch {
                    try {
                        shareFile(context, vm.exportSamples())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // nothing to share yet, or the share sheet failed: ignore
                    }
                }
            },
        )
    }

    if (landscape) {
        Row(modifier.fillMaxSize().imePadding()) {
            photo(Modifier.weight(1f).fillMaxHeight())
            results(Modifier.width(360.dp).fillMaxHeight())
        }
    } else {
        Column(modifier.fillMaxSize().imePadding()) {
            photo(Modifier.weight(1f).fillMaxWidth())
            results(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PhotoArea(
    image: Bitmap,
    imageBitmap: ImageBitmap,
    ui: OcrUiState,
    onBox: (Rect) -> Unit,
    onLine: (DetectedLine) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds().background(Color.Black)) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }

        // The photo is drawn "fit" inside this area; these convert between photo pixels and screen pixels.
        val scale = minOf(viewW / image.width, viewH / image.height)
        val dx = (viewW - image.width * scale) / 2f
        val dy = (viewH - image.height * scale) / 2f
        val bounds = Rect(dx, dy, dx + image.width * scale, dy + image.height * scale)

        fun toView(r: Rect) = Rect(r.left * scale + dx, r.top * scale + dy, r.right * scale + dx, r.bottom * scale + dy)
        fun toImage(r: Rect) = Rect((r.left - dx) / scale, (r.top - dy) / scale, (r.right - dx) / scale, (r.bottom - dy) / scale)

        val latest by rememberUpdatedState(ui)
        var dragRect by remember { mutableStateOf<Rect?>(null) }

        Image(
            bitmap = imageBitmap,
            contentDescription = "Captured photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scale, dx, dy) {
                    detectTapGestures { tap ->
                        val p = Offset((tap.x - dx) / scale, (tap.y - dy) / scale)
                        latest.lines
                            .filter { it.box.inflate(10f / scale).contains(p) }
                            .minByOrNull { it.box.width * it.box.height }
                            ?.let(onLine)
                    }
                }
                .pointerInput(scale, dx, dy) {
                    var grab = Grab.NEW
                    var anchor = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start ->
                            val current = latest.box?.let { toView(it) }
                            val reach = 28.dp.toPx()
                            grab = when {
                                current == null -> Grab.NEW
                                (start - current.topLeft).getDistance() < reach -> Grab.TOP_LEFT
                                (start - current.topRight).getDistance() < reach -> Grab.TOP_RIGHT
                                (start - current.bottomLeft).getDistance() < reach -> Grab.BOTTOM_LEFT
                                (start - current.bottomRight).getDistance() < reach -> Grab.BOTTOM_RIGHT
                                current.contains(start) -> Grab.MOVE
                                else -> Grab.NEW
                            }
                            anchor = start.clampTo(bounds)
                            dragRect = if (grab == Grab.NEW) Rect(anchor, anchor) else current
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            val r = dragRect ?: return@detectDragGestures
                            val p = change.position.clampTo(bounds)
                            dragRect = when (grab) {
                                Grab.NEW -> rectOf(anchor, p)
                                Grab.MOVE -> r.translate(delta).shiftedInto(bounds)
                                Grab.TOP_LEFT -> rectOf(p, r.bottomRight)
                                Grab.TOP_RIGHT -> rectOf(p, r.bottomLeft)
                                Grab.BOTTOM_LEFT -> rectOf(p, r.topRight)
                                Grab.BOTTOM_RIGHT -> rectOf(p, r.topLeft)
                            }
                        },
                        onDragEnd = {
                            dragRect?.let { r ->
                                if (r.width > 24.dp.toPx() && r.height > 12.dp.toPx()) onBox(toImage(r))
                            }
                            dragRect = null
                        },
                        onDragCancel = { dragRect = null },
                    )
                },
        ) {
            // Lines the layout detector found: tap one to use it.
            ui.lines.forEach { line ->
                val v = toView(line.box)
                drawRoundRect(
                    color = LineAmber.copy(alpha = 0.85f),
                    topLeft = v.topLeft,
                    size = v.size,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            val shown = dragRect ?: ui.box?.let { toView(it) }
            if (shown != null) {
                // Dim everything outside the box so the chosen text stands out.
                val dim = Color.Black.copy(alpha = 0.45f)
                drawRect(dim, Offset.Zero, Size(size.width, shown.top))
                drawRect(dim, Offset(0f, shown.bottom), Size(size.width, size.height - shown.bottom))
                drawRect(dim, Offset(0f, shown.top), Size(shown.left, shown.height))
                drawRect(dim, Offset(shown.right, shown.top), Size(size.width - shown.right, shown.height))

                drawRect(BoxGreen, shown.topLeft, shown.size, style = Stroke(width = 3.dp.toPx()))
                listOf(shown.topLeft, shown.topRight, shown.bottomLeft, shown.bottomRight).forEach { corner ->
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = corner)
                    drawCircle(BoxGreen, radius = 9.dp.toPx(), center = corner, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }

        // Controls drawn last, so they stay on top of the photo.
        OverlayBackButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(8.dp),
        )

        if (ui.box == null && dragRect == null) {
            Text(
                if (ui.lines.isEmpty()) {
                    "Drag to draw a box around the text"
                } else {
                    "Drag to draw a box, or tap an outlined line"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultPanel(
    ui: OcrUiState,
    landscape: Boolean,
    modifier: Modifier,
    onMode: (OcrMode) -> Unit,
    onText: (String) -> Unit,
    onAlternative: (String) -> Unit,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onShare: () -> Unit,
) {
    val accession = ui.mode == OcrMode.ACCESSION
    val isbnMode = ui.mode == OcrMode.ISBN

    PanelContainer(landscape = landscape, modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = accession,
                onClick = { onMode(OcrMode.ACCESSION) },
                label = { Text("Accession no.") },
            )
            FilterChip(
                selected = isbnMode,
                onClick = { onMode(OcrMode.ISBN) },
                label = { Text("ISBN") },
            )
            FilterChip(
                selected = !accession && !isbnMode,
                onClick = { onMode(OcrMode.ANY_TEXT) },
                label = { Text("Any text") },
            )
        }

        if (ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Reading…")
            }
        }

        OutlinedTextField(
            value = ui.text,
            onValueChange = onText,
            label = {
                Text(
                    when {
                        ui.target != null -> "${ui.target} (edit if needed)"
                        accession -> "Accession number (edit if needed)"
                        isbnMode -> "ISBN (edit if needed)"
                        else -> "Recognised text (edit if needed)"
                    },
                )
            },
            singleLine = accession || isbnMode,
            minLines = if (accession) 1 else 2,
            keyboardOptions = KeyboardOptions(
                capitalization = if (accession) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (accession && ui.text.isNotBlank()) {
            val valid = AccessionFormat.isValid(ui.text.trim().uppercase())
            Text(
                if (valid) {
                    "Matches the accession format."
                } else {
                    "Doesn't match ${AccessionFormat.describe()}. Edit it, or confirm anyway."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (isbnMode && ui.text.isNotBlank()) {
            val parsed = Isbn.parse(ui.text)
            Text(
                if (parsed != null) {
                    if (parsed.wasIsbn10) "Valid ISBN-10 (saved as ${parsed.isbn13})." else "Valid ISBN-13."
                } else {
                    "Not a valid ISBN yet (a digit may be misread). Edit it, or confirm anyway."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (parsed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        ui.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (ui.alternatives.isNotEmpty()) {
            Text("Other readings (tap to use)", style = MaterialTheme.typography.labelSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.alternatives.forEach { alt ->
                    AssistChip(onClick = { onAlternative(alt) }, label = { Text(alt) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, enabled = ui.text.isNotBlank() && !ui.busy) { Text(if (ui.target != null) "Use as ${ui.target}" else "Confirm & save") }
            OutlinedButton(onClick = onRetake) { Text("Retake") }
        }

        Text(
            "Reader: ${ui.engine}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ui.savedCount} training samples saved",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShare, enabled = ui.savedCount > 0) { Text("Share samples") }
        }
    }
}

private fun rectOf(a: Offset, b: Offset) = Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))

private fun Offset.clampTo(bounds: Rect) = Offset(x.coerceIn(bounds.left, bounds.right), y.coerceIn(bounds.top, bounds.bottom))

/** Slides the rectangle (without resizing it) so it stays inside [bounds]. */
private fun Rect.shiftedInto(bounds: Rect): Rect {
    val shiftX = when {
        left < bounds.left -> bounds.left - left
        right > bounds.right -> bounds.right - right
        else -> 0f
    }
    val shiftY = when {
        top < bounds.top -> bounds.top - top
        bottom > bounds.bottom -> bounds.bottom - bottom
        else -> 0f
    }
    return translate(shiftX, shiftY)
}
