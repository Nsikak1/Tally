package ng.name.nsikak.libaccession.ui.ocr

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ng.name.nsikak.libaccession.data.ocr.Alphabet
import ng.name.nsikak.libaccession.data.ocr.ImagePreprocess
import ng.name.nsikak.libaccession.data.ocr.OcrSampleStore
import ng.name.nsikak.libaccession.data.ocr.PaddleRecognizer
import ng.name.nsikak.libaccession.data.ocr.Reading
import ng.name.nsikak.libaccession.data.ocr.TextRecognizerService
import ng.name.nsikak.libaccession.domain.AccessionFormat
import ng.name.nsikak.libaccession.domain.Isbn
import java.io.File
import kotlin.math.roundToInt

enum class OcrMode { ACCESSION, ISBN, ANY_TEXT }

/** A text line the layout detector found by itself in the photo, in photo pixels. */
data class DetectedLine(val text: String, val box: Rect)

data class OcrUiState(
    val image: Bitmap? = null,               // the frozen photo; null = back to the live camera
    val lines: List<DetectedLine> = emptyList(),
    val box: Rect? = null,                   // the box around the text, in photo pixels
    val mode: OcrMode = OcrMode.ACCESSION,
    val busy: Boolean = false,
    val text: String = "",                   // the editable field
    val alternatives: List<String> = emptyList(),
    val notice: String? = null,
    val engine: String = "",                 // which recogniser reads the box
    val savedCount: Int = 0,
    val lastSaved: String? = null,
    /** Guided fill: the field the confirmed text goes into ("Title", ...). Null = the plain reader. */
    val target: String? = null,
)

class OcrViewModel(
    private val paddle: PaddleRecognizer,
    private val mlKit: TextRecognizerService,
    private val samples: OcrSampleStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(OcrUiState())
    val ui: StateFlow<OcrUiState> = _ui.asStateFlow()

    private val _confirmed = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Emits the text each time the user confirms a reading (the guided fill listens to this). */
    val confirmed: SharedFlow<String> = _confirmed.asSharedFlow()

    private var rawReadings: List<Reading> = emptyList()
    private var proposed: String = ""            // what the OCR first suggested, kept for the CSV
    private var detectJob: Job? = null
    private var readJob: Job? = null

    init {
        viewModelScope.launch {
            val count = samples.count()
            _ui.update { it.copy(savedCount = count, engine = engineLabel()) }
            // Load the model now so the first capture isn't slow.
            if (paddle.isInstalled()) {
                paddle.warmUp()
                _ui.update { it.copy(engine = engineLabel()) }
            }
        }
    }

    private fun engineLabel(): String = when {
        !paddle.isInstalled() -> "ML Kit (PaddleOCR model not installed: run scripts/fetch_models.sh)"
        paddle.lastError != null -> "ML Kit (PaddleOCR failed: ${paddle.lastError})"
        else -> "PaddleOCR ${paddle.modelName()}"
    }

    /** A photo was taken: show it and look for text lines in it. */
    fun onCaptured(photo: Bitmap) {
        cancelJobs()
        rawReadings = emptyList()
        proposed = ""
        _ui.update {
            it.copy(
                image = photo, lines = emptyList(), box = null, busy = false,
                text = "", alternatives = emptyList(), notice = null,
            )
        }
        detectJob = viewModelScope.launch {
            val found = try {
                mlKit.recognizeLines(photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val lines = found.mapNotNull { line ->
                val b = line.box ?: return@mapNotNull null
                DetectedLine(line.text, Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
            }
            _ui.update { it.copy(lines = lines) }
        }
    }

    /** The user drew, moved or resized the box (photo pixels). Reads it after a short pause. */
    fun setBox(box: Rect) {
        _ui.update { it.copy(box = box) }
        readJob?.cancel()
        readJob = viewModelScope.launch {
            delay(300) // wait for the finger to settle
            readBox()
        }
    }

    /** The user tapped one of the auto-detected lines. */
    fun selectLine(line: DetectedLine) {
        val image = _ui.value.image ?: return
        val padX = line.box.width * 0.04f
        val padY = line.box.height * 0.20f
        setBox(
            Rect(
                (line.box.left - padX).coerceAtLeast(0f),
                (line.box.top - padY).coerceAtLeast(0f),
                (line.box.right + padX).coerceAtMost(image.width.toFloat()),
                (line.box.bottom + padY).coerceAtMost(image.height.toFloat()),
            ),
        )
    }

    private suspend fun readBox() {
        val state = _ui.value
        val image = state.image ?: return
        val box = state.box ?: return
        _ui.update { it.copy(busy = true, notice = null) }
        val readings = try {
            withContext(Dispatchers.Default) { readCrop(image, box, state.mode) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList<Reading>()
        }
        rawReadings = readings
        applyReadings(noticeIfEmpty = true)
        _ui.update { it.copy(busy = false, engine = engineLabel()) }
    }

    /** Crops the box out of the photo and reads it with Paddle, or with ML Kit if Paddle isn't available. */
    private suspend fun readCrop(image: Bitmap, box: Rect, mode: OcrMode): List<Reading> {
        val crop = ImagePreprocess.crop(image, box.toAndroidRect()) ?: return emptyList()
        if (paddle.isInstalled()) {
            readWithPaddle(crop, mode)?.let { return it }
        }
        return readWithMlKit(crop)
    }

    private suspend fun readWithPaddle(crop: Bitmap, mode: OcrMode): List<Reading>? {
        val lines = if (mode == OcrMode.ANY_TEXT) splitLines(crop) else listOf(crop)
        val alphabets = when (mode) {
            OcrMode.ACCESSION -> listOf(Alphabet.ACCESSION, Alphabet.ANY)
            OcrMode.ISBN -> listOf(Alphabet.ISBN, Alphabet.ANY)
            OcrMode.ANY_TEXT -> listOf(Alphabet.ANY)
        }

        val perLine = lines.map { line ->
            // Each line is read as it is and with the contrast stretched; the more confident one wins.
            val candidates = mutableListOf<Reading>()
            for (image in listOf(line, ImagePreprocess.contrastOnly(line))) {
                candidates += paddle.read(image, alphabets) ?: return null
            }
            candidates
        }

        if (perLine.size == 1) return perLine[0]
        // Several lines: keep the best reading of each and join them.
        val best = perLine.map { candidates -> candidates.maxByOrNull { it.confidence } ?: return null }
        return listOf(
            Reading(
                text = best.joinToString("\n") { it.text },
                confidence = best.map { it.confidence }.average().toFloat(),
            ),
        )
    }

    /** ML Kit is only used to find where the lines are in a multi-line box. */
    private suspend fun splitLines(crop: Bitmap): List<Bitmap> {
        val found = try {
            mlKit.recognizeLines(crop)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(crop)
        }
        val boxes = found.mapNotNull { it.box }.sortedBy { it.top }
        if (boxes.size < 2) return listOf(crop)
        return boxes.mapNotNull { ImagePreprocess.crop(crop, it, padFraction = 0.10f) }.ifEmpty { listOf(crop) }
    }

    private suspend fun readWithMlKit(crop: Bitmap): List<Reading> {
        val out = mutableListOf<Reading>()
        for (variant in ImagePreprocess.variants(crop)) {
            out += Reading(mlKit.recognizeText(variant.bitmap), 0.5f)
        }
        return out
    }

    private fun postProcess(raw: String, mode: OcrMode): String = when (mode) {
        OcrMode.ACCESSION -> AccessionFormat.normalize(raw.replace('\n', ' '))
        // The printed ISBN is cut out of the line ("ISBN 978-0-..."); if it isn't valid yet, keep the digits so it can be edited.
        OcrMode.ISBN -> {
            val flat = raw.replace('\n', ' ')
            Isbn.findIn(flat)?.isbn13 ?: flat.filter { it.isDigit() || it == 'X' || it == 'x' || it == '-' }
        }
        OcrMode.ANY_TEXT -> raw.trim()
    }

    /** Re-derives the field from the stored raw readings for the current mode. */
    private fun applyReadings(noticeIfEmpty: Boolean) {
        val mode = _ui.value.mode
        val processed = rawReadings
            .map { Reading(postProcess(it.text, mode), it.confidence) }
            .filter { it.text.isNotBlank() }
        val bestReading: Reading? = when (mode) {
            OcrMode.ACCESSION ->
                processed.filter { AccessionFormat.isValid(it.text) }.maxByOrNull { it.confidence }
                    ?: processed.maxByOrNull { it.confidence }
            OcrMode.ISBN ->
                processed.filter { Isbn.parse(it.text) != null }.maxByOrNull { it.confidence }
                    ?: processed.maxByOrNull { it.confidence }
            OcrMode.ANY_TEXT -> processed.maxByOrNull { it.confidence }
        }
        val best = bestReading?.text.orEmpty()
        proposed = best
        _ui.update {
            it.copy(
                text = best,
                alternatives = processed.sortedByDescending { r -> r.confidence }
                    .map { r -> r.text }.distinct().filter { t -> t != best }.take(4),
                notice = if (best.isBlank() && noticeIfEmpty) {
                    "Nothing readable in that box. Try a tighter box, or retake with better light."
                } else {
                    null
                },
            )
        }
    }

    fun setMode(mode: OcrMode) {
        _ui.update { it.copy(mode = mode) }
        if (rawReadings.isNotEmpty()) {
            // Accession mode reads with a restricted alphabet, so a mode change needs a fresh read.
            readJob?.cancel()
            readJob = viewModelScope.launch { readBox() }
        }
    }

    /** Guided fill: names the field the next confirmed text is for (null = none). */
    fun setTarget(label: String?) = _ui.update { it.copy(target = label) }

    fun onTextEdited(text: String) = _ui.update {
        // Accession box: fix the case and the fixed part of the format while typing (ils0123 -> ILS-0123).
        it.copy(text = if (it.mode == OcrMode.ACCESSION) AccessionFormat.autocorrect(text) else text)
    }

    fun useAlternative(text: String) = _ui.update { it.copy(text = text) }

    /** Saves the crop + corrected label, then returns to the live camera. */
    fun confirm() {
        val state = _ui.value
        val image = state.image ?: return
        val label = state.text.trim().let { if (state.mode == OcrMode.ACCESSION) AccessionFormat.autocorrect(it) else it }
        if (label.isEmpty()) return
        cancelJobs()
        viewModelScope.launch {
            var count = state.savedCount
            val box = state.box
            if (box != null) {
                val crop = withContext(Dispatchers.Default) {
                    ImagePreprocess.crop(image, box.toAndroidRect(), padFraction = 0.04f)
                }
                if (crop != null) {
                    count = samples.save(crop, label, proposed, state.mode.name.lowercase())
                }
            }
            rawReadings = emptyList()
            proposed = ""
            _ui.update { OcrUiState(mode = it.mode, engine = it.engine, savedCount = count, lastSaved = label, target = it.target) }
            _confirmed.tryEmit(label)
        }
    }

    /** Throw the photo away and go back to the live camera. */
    fun retake() {
        cancelJobs()
        rawReadings = emptyList()
        proposed = ""
        _ui.update {
            OcrUiState(
                mode = it.mode, engine = it.engine, savedCount = it.savedCount,
                lastSaved = it.lastSaved, target = it.target,
            )
        }
    }

    suspend fun exportSamples(): File = samples.exportZip()

    private fun cancelJobs() {
        detectJob?.cancel()
        readJob?.cancel()
    }
}

private fun Rect.toAndroidRect(): android.graphics.Rect =
    android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
