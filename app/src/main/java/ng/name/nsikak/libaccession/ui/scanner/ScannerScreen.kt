package ng.name.nsikak.libaccession.ui.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.domain.ScannedIsbn
import ng.name.nsikak.libaccession.ui.AppViewModelProvider
import ng.name.nsikak.libaccession.data.cover.CoverLoader
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.ui.common.Preview
import ng.name.nsikak.libaccession.ui.common.BookPreviewCard
import ng.name.nsikak.libaccession.ui.common.OverlayBackButton
import ng.name.nsikak.libaccession.ui.common.appContainer
import ng.name.nsikak.libaccession.ui.common.titleOrNull
import ng.name.nsikak.libaccession.ui.common.PanelContainer
import ng.name.nsikak.libaccession.ui.ocr.OcrReviewPane
import ng.name.nsikak.libaccession.ui.ocr.OcrUiState
import ng.name.nsikak.libaccession.ui.ocr.OcrViewModel
import ng.name.nsikak.libaccession.ui.ocr.shareFile

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The scanner. Portrait: camera on top, panel underneath. Landscape: camera on the left, panel on the right.
 * Back button and the Barcode | Text switch live on the camera itself (see CameraPane).
 */
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onAddCopy: (String) -> Unit,
    onOpenHolding: (String) -> Unit,
    fillSteps: List<FillStep>?,
    onGuidedDone: (Map<String, String>) -> Unit,
    savedAccession: String?,
    onSavedHandled: () -> Unit,
    vm: ScannerViewModel = viewModel(factory = AppViewModelProvider.Factory),
    ocrVm: OcrViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ocr by ocrVm.ui.collectAsStateWithLifecycle()
    val doubleTapZoom by vm.doubleTapZoom.collectAsStateWithLifecycle()
    val autoAccession by vm.autoAccession.collectAsStateWithLifecycle()
    val covers = appContainer().covers
    val labels = ui.detections.mapNotNull { d ->
        ui.previews[d.isbn.isbn13].titleOrNull()?.let { d.isbn.isbn13 to it }
    }.toMap()

    var mode by rememberSaveable { mutableStateOf(ScanMode.BARCODE) }
    var showManualIsbn by remember { mutableStateOf(false) }

    var hasCamera by remember { mutableStateOf(hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Coming back from the system settings screen: re-check.
    LifecycleResumeEffect(Unit) {
        hasCamera = hasCameraPermission(context)
        vm.refreshFocus() // coming back from adding a copy: the list of copies may have changed
        onPauseOrDispose { }
    }

    val reviewing = ocr.image != null
    BackHandler(enabled = reviewing) { ocrVm.retake() }

    // ---- guided fill: the camera asks for the ISBN, title, author and accession number in turn ----
    val guided = ui.guided
    LaunchedEffect(fillSteps) { if (fillSteps != null) vm.startGuided(fillSteps) }
    LaunchedEffect(guided?.current) {
        val step = guided?.current
        ocrVm.setTarget(step?.label)
        if (step != null) {
            ocrVm.setMode(step.ocrMode)
            mode = if (step == FillStep.ISBN) ScanMode.BARCODE else ScanMode.TEXT
        }
    }
    LaunchedEffect(Unit) { ocrVm.confirmed.collect { vm.acceptGuided(it) } }
    LaunchedEffect(guided?.finished) {
        val run = guided
        if (run != null && run.finished) {
            onGuidedDone(run.values.entries.associate { it.key.key to it.value })
            vm.clearGuided()
        }
    }

    // ---- add-copies mode: ISBN known -> read the accession number -> form filled -> save -> next book ----
    val focusIsbn = ui.focus?.isbn13
    val focusAnswered = focusIsbn != null && ui.previews[focusIsbn] is Preview.Done
    LaunchedEffect(focusIsbn, focusAnswered, autoAccession, guided == null, mode, reviewing, ui.detections.size, ui.selected) {
        if (focusIsbn == null) {
            vm.releaseAuto()
        } else if (focusAnswered && guided == null && mode == ScanMode.BARCODE && !reviewing) {
            delay(AUTO_ADVANCE_DELAY_MS) // a moment to see the book, and for the barcode to settle
            vm.autoAdvance()?.let(onAddCopy)
        }
    }
    // Back from a saved copy: clear the screen for the next book.
    LaunchedEffect(savedAccession) {
        if (savedAccession != null) {
            vm.resetForNextBook(savedAccession)
            mode = ScanMode.BARCODE
            onSavedHandled()
        }
    }

    val shareSamples: () -> Unit = {
        scope.launch {
            try {
                shareFile(context, ocrVm.exportSamples())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nothing to share yet
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight

            if (reviewing) {
                OcrReviewPane(vm = ocrVm, ui = ocr, landscape = landscape)
            } else {
                val camera: @Composable (Modifier) -> Unit = { m ->
                    // clipToBounds: the preview is cropped to fill, so it must not draw outside this area.
                    Box(m.clipToBounds()) {
                        if (hasCamera) {
                            CameraPane(
                                mode = mode,
                                detections = ui.detections,
                                selected = ui.selected,
                                labels = labels,
                                doubleTapZoom = doubleTapZoom,
                                onBarcodes = vm::onBarcodes,
                                onTap = vm::onTap,
                                onRegionIsbn = vm::selectManual,
                                onCaptured = ocrVm::onCaptured,
                                onCapturedWithBox = { photo, box ->
                                    ocrVm.onCaptured(photo)
                                    ocrVm.setBox(box)
                                },
                                onBack = onBack,
                                onModeChange = { mode = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            PermissionPrompt(
                                onBack = onBack,
                                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                onOpenSettings = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                val panel: @Composable (Modifier) -> Unit = { m ->
                    if (mode == ScanMode.BARCODE) {
                        BarcodePanel(
                            ui = ui,
                            covers = covers,
                            landscape = landscape,
                            modifier = m,
                            onAddCopy = onAddCopy,
                            onOpenCopy = onOpenHolding,
                            onRetry = vm::retry,
                            onClear = vm::clearSelection,
                            onTypeIsbn = { showManualIsbn = true },
                            autoAccession = autoAccession,
                            onAutoAccessionChange = vm::setAutoAccession,
                            guided = guided,
                            onUseIsbn = vm::acceptGuided,
                            onStartFill = { isbn13 ->
                                vm.startGuided(
                                    listOf(FillStep.TITLE, FillStep.AUTHOR, FillStep.ACCESSION),
                                    preset = mapOf(FillStep.ISBN to isbn13),
                                )
                            },
                            onSkip = vm::skipGuided,
                            onGuidedBack = vm::backGuided,
                            onGuidedDone = vm::finishGuided,
                        )
                    } else {
                        TextModePanel(
                            ocr = ocr,
                            landscape = landscape,
                            modifier = m,
                            onShare = shareSamples,
                            guided = guided,
                            onSkip = vm::skipGuided,
                            onGuidedBack = vm::backGuided,
                            onGuidedDone = vm::finishGuided,
                        )
                    }
                }

                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        camera(Modifier.weight(1f).fillMaxHeight())
                        panel(Modifier.width(340.dp).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        camera(Modifier.weight(1f).fillMaxWidth())
                        panel(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (showManualIsbn) {
        ManualIsbnDialog(
            onDismiss = { showManualIsbn = false },
            onSubmit = {
                vm.selectManual(it)
                showManualIsbn = false
            },
        )
    }
}

@Composable
private fun BarcodePanel(
    ui: ScannerUiState,
    covers: CoverLoader,
    landscape: Boolean,
    modifier: Modifier,
    onAddCopy: (String) -> Unit,
    onOpenCopy: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClear: () -> Unit,
    onTypeIsbn: () -> Unit,
    autoAccession: Boolean,
    onAutoAccessionChange: (Boolean) -> Unit,
    guided: GuidedState?,
    onUseIsbn: (String) -> Unit,
    onStartFill: (String) -> Unit,
    onSkip: () -> Unit,
    onGuidedBack: () -> Unit,
    onGuidedDone: () -> Unit,
) {
    PanelContainer(landscape = landscape, modifier = modifier) {
        if (guided != null) GuidedHeader(guided, onSkip, onGuidedBack, onGuidedDone)
        val focus = ui.focus
        if (focus == null) {
            ui.lastSaved?.let {
                Text("Saved $it. Next book:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("Scan a book", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.detections.isEmpty()) {
                    "Point the camera at a book's barcode and its details appear here. " +
                        "Pinch to zoom. Double-tap a spot to zoom in and read just that spot."
                } else {
                    "Looking up the book…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            BookPreviewCard(
                isbn = focus.isbn13,
                preview = ui.previews[focus.isbn13],
                covers = covers,
                onRetry = { onRetry(focus.isbn13) },
                onOpenCopy = onOpenCopy,
            )
            if (focus.wasIsbn10) {
                Text("Printed as ISBN-10 ${focus.original}", style = MaterialTheme.typography.bodySmall)
            }
            if (ui.selected == null && ui.detections.size > 1) {
                Text(
                    "${ui.detections.size} barcodes in view: showing the biggest. Tap one to choose it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val notFound = ((ui.previews[focus.isbn13] as? Preview.Done)?.lookup) is Lookup.NotInCatalog
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (guided?.current == FillStep.ISBN) {
                    Button(onClick = { onUseIsbn(focus.isbn13) }) { Text("Use this ISBN") }
                } else if (guided == null) {
                    Button(onClick = { onAddCopy(focus.isbn13) }) { Text("Add a copy") }
                }
                OutlinedButton(onClick = onClear, enabled = ui.selected != null) { Text("Clear") }
            }
            if (notFound && guided == null) {
                Button(onClick = { onStartFill(focus.isbn13) }) { Text("Fill the details with the camera") }
            }
        }
        TextButton(onClick = onTypeIsbn) { Text("Type an ISBN instead") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "After the ISBN, go straight to the accession number",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoAccession, onCheckedChange = onAutoAccessionChange)
        }
    }
}

private const val AUTO_ADVANCE_DELAY_MS = 700L

@Composable
private fun TextModePanel(
    ocr: OcrUiState,
    landscape: Boolean,
    modifier: Modifier,
    onShare: () -> Unit,
    guided: GuidedState?,
    onSkip: () -> Unit,
    onGuidedBack: () -> Unit,
    onGuidedDone: () -> Unit,
) {
    PanelContainer(landscape = landscape, modifier = modifier) {
        if (guided != null) GuidedHeader(guided, onSkip, onGuidedBack, onGuidedDone)
        Text("Read handwriting or any text", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fill the screen with the writing, tap to focus, then press the white button, " +
                "then draw a box around the text. Or double-tap the writing: it focuses, takes the " +
                "photo and puts the box around that spot for you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ocr.lastSaved?.let {
            Text("Last saved: $it", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Reader: ${ocr.engine}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ocr.savedCount} training samples saved",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShare, enabled = ocr.savedCount > 0) { Text("Share samples") }
        }
    }
}

/** The "Step 2 of 4: ..." block at the top of the panel while the camera is filling a form. */
@Composable
private fun GuidedHeader(guided: GuidedState, onSkip: () -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    val step = guided.current ?: return
    Text(
        "Step ${guided.index + 1} of ${guided.steps.size}: ${stepPrompt(step)}",
        style = MaterialTheme.typography.titleMedium,
    )
    val known = guided.values.entries.joinToString(" · ") { "${it.key.label}: ${it.value.take(24)}" }
    if (known.isNotEmpty()) {
        Text(known, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, enabled = guided.index > 0) { Text("Back") }
        OutlinedButton(onClick = onSkip) { Text("Skip") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}

private fun stepPrompt(step: FillStep): String = when (step) {
    FillStep.ISBN -> "scan the barcode (or switch to Text and read the printed ISBN)"
    FillStep.TITLE -> "point at the TITLE and double-tap it"
    FillStep.AUTHOR -> "point at the AUTHOR's name and double-tap it"
    FillStep.ACCESSION -> "point at the ACCESSION number and double-tap it"
}

private fun sanitizeIsbnInput(raw: String): String {
    val filtered = raw.uppercase().filter { it.isDigit() || it == 'X' }

    val cleaned = if ('X' !in filtered) {
        filtered
    } else {
        val digits = filtered.filter { it.isDigit() }
        if (filtered.last() == 'X') "${digits}X" else digits
    }

    return cleaned.take(13)
}

@Composable
private fun ManualIsbnDialog(
    onDismiss: () -> Unit,
    onSubmit: (ScannedIsbn) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val parsed = Isbn.parse(text)
    val canAppendX = text.length == 9 && text.all { it.isDigit() }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Focus + show keyboard once the dialog window is ready
    LaunchedEffect(Unit) {
        delay(100) // let the dialog window attach before asking for focus
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type an ISBN") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = sanitizeIsbnInput(it) },
                modifier = Modifier.focusRequester(focusRequester),
                singleLine = true,
                label = { Text("ISBN-10 or ISBN-13") },
                isError = text.isNotBlank() && parsed == null,
                supportingText = {
                    if (text.isNotBlank() && parsed == null) {
                        Text("Not a valid ISBN (check the digits).")
                    } else {
                        Text("ISBN-10 check digit can be 'X'.")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { parsed?.let(onSubmit) },
                ),
                trailingIcon = {
                    if (canAppendX) {
                        TextButton(onClick = { text += "X" }) { Text("X") }
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSubmit) },
                enabled = parsed != null,
            ) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PermissionPrompt(onBack: () -> Unit, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        OverlayBackButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Tally needs the camera to read barcodes and text.",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onRequest) { Text("Allow camera") }
            OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}
