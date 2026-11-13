package ng.name.nsikak.libaccession.ui.scanner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ng.name.nsikak.libaccession.data.repo.BookRepository
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.data.settings.SettingsStore
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.domain.ScannedIsbn
import ng.name.nsikak.libaccession.ui.common.Preview
import kotlin.math.abs

/** A barcode as reported by ML Kit: its text and its box in PreviewView pixel coordinates. */
data class RawBarcode(val value: String, val box: Rect)

/** A barcode that passed ISBN validation and has been stable for a couple of frames. */
data class Detection(val isbn: ScannedIsbn, val box: Rect, val fading: Boolean = false)

data class ScannerUiState(
    val detections: List<Detection> = emptyList(),
    val selected: ScannedIsbn? = null,
    /** Lookup state per ISBN-13, filled in automatically as soon as a valid ISBN is seen. */
    val previews: Map<String, Preview> = emptyMap(),
    /** Non-null while the camera is filling a form field by field (see GuidedFill). */
    val guided: GuidedState? = null,
    /** The accession number of the copy that was just saved (shown until the next book is scanned). */
    val lastSaved: String? = null,
) {
    /** The book the panel shows: the one tapped, else the biggest barcode in view. */
    val focus: ScannedIsbn?
        get() = selected ?: detections.maxByOrNull { it.box.width * it.box.height }?.isbn
}

class ScannerViewModel(
    private val books: BookRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(ScannerUiState())
    val ui: StateFlow<ScannerUiState> = _ui.asStateFlow()

    /** Zoom ratio a double-tap zooms to (Settings). */
    val doubleTapZoom: StateFlow<Float> = settings.doubleTapZoom.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsStore.DEFAULT_DOUBLE_TAP_ZOOM,
    )

    /** Add-copies mode: after an ISBN, go straight to reading the accession number (switch on the scanner panel). */
    val autoAccession: StateFlow<Boolean> = settings.autoAccession.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoAccession(on: Boolean) {
        viewModelScope.launch { settings.saveAutoAccession(on) }
    }

    // The book for which the accession step was already started automatically (so Back from the form doesn't loop).
    private var autoStartedFor: String? = null

    private data class Track(
        val isbn: ScannedIsbn,
        val box: Rect,
        val lastSeenMs: Long,
        val hits: Int,
    )

    // One track per ISBN. Two copies of the same book in one frame share a track (known limitation).
    private val tracks = LinkedHashMap<String, Track>()

    // ISBN-13s already sent to the server this session (so a code staying in view is looked up once).
    private val requested = HashSet<String>()

    // The server is small: at most two lookups at a time.
    private val gate = Semaphore(2)

    /**
     * Called for every analysed frame with ALL barcodes in view. Hand shake is tamed three ways:
     *  - a code must be seen in [MIN_HITS] frames before it is shown (no one-frame ghosts),
     *  - a box only moves when it has moved more than a dead zone, and then eases toward the new spot,
     *  - a code that drops out of detection stays on screen, dimmed, for [GRACE_MS].
     * Every code that is shown is also looked up on the server.
     */
    fun onBarcodes(raw: List<RawBarcode>, nowMs: Long) {
        for (r in raw) {
            val isbn = Isbn.parse(r.value) ?: continue
            val key = isbn.isbn13
            val old = tracks[key]
            tracks[key] = if (old == null) {
                Track(isbn, r.box, nowMs, 1)
            } else {
                old.copy(box = smooth(old.box, r.box), lastSeenMs = nowMs, hits = old.hits + 1)
            }
        }
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > GRACE_MS }

        val visible = tracks.values
            .filter { it.hits >= MIN_HITS }
            .map { Detection(it.isbn, it.box, fading = it.lastSeenMs != nowMs) }

        if (visible != _ui.value.detections) _ui.update { it.copy(detections = visible) }
        visible.forEach { lookup(it.isbn.isbn13, force = false) }
    }

    private fun smooth(old: Rect, new: Rect): Rect {
        val delta = maxOf(
            abs(old.left - new.left),
            abs(old.top - new.top),
            abs(old.right - new.right),
            abs(old.bottom - new.bottom),
        )
        val width = maxOf(new.width, 1f)
        val deadZone = maxOf(4f, 0.04f * width)
        return when {
            delta < deadZone -> old                      // shake: ignore
            delta > 0.8f * width + 40f -> new            // jumped: a different code, snap
            else -> Rect(                                // real movement: ease toward it
                old.left + (new.left - old.left) * EASE,
                old.top + (new.top - old.top) * EASE,
                old.right + (new.right - old.right) * EASE,
                old.bottom + (new.bottom - old.bottom) * EASE,
            )
        }
    }

    /** Asks the server about [isbn13] unless that was already done this session (or [force]). */
    private fun lookup(isbn13: String, force: Boolean) {
        val first = requested.add(isbn13)
        if (!first && !force) return
        setPreview(isbn13, Preview.Loading)
        viewModelScope.launch {
            val result = gate.withPermit { books.lookup(isbn13) }
            setPreview(isbn13, Preview.Done(result))
        }
    }

    private fun setPreview(isbn13: String, preview: Preview) =
        _ui.update { it.copy(previews = it.previews + (isbn13 to preview)) }

    /** The Retry button, and a refresh after a copy was added. */
    fun retry(isbn13: String) = lookup(isbn13, force = true)

    /** Looks the current book up again, but only if it already had an answer (not on first launch). */
    fun refreshFocus() {
        val focus = _ui.value.focus ?: return
        if (_ui.value.previews[focus.isbn13] is Preview.Done) retry(focus.isbn13)
    }

    /**
     * A tap at [point] (view pixels). Selects the barcode under it, allowing [slopPx] of fuzz
     * around the box; the smallest box wins when several overlap. Returns true if one was hit.
     */
    fun onTap(point: Offset, slopPx: Float): Boolean {
        val hit = _ui.value.detections
            .filter { it.box.inflate(slopPx).contains(point) }
            .minByOrNull { it.box.width * it.box.height }
            ?: return false
        _ui.update { it.copy(selected = hit.isbn) }
        lookup(hit.isbn.isbn13, force = false)
        return true
    }

    /** For an ISBN typed by hand (damaged barcode) or found by a double-tap on a spot. */
    fun selectManual(isbn: ScannedIsbn) {
        _ui.update { it.copy(selected = isbn) }
        lookup(isbn.isbn13, force = false)
    }

    fun clearSelection() = _ui.update { it.copy(selected = null) }

    // ---- guided fill: the camera asks for ISBN, title, author, accession one after the other ----

    /**
     * Add-copies mode. Called a moment after the book on screen has its lookup answer. Returns the ISBN-13 whose
     * "add a copy" page should open now (details, then the typed accession number), or null: when the mode is off,
     * when a guided camera run is going, when several barcodes are in view and none was tapped, or when this book
     * already opened it (so Back from that page does not bounce straight back into it).
     */
    fun autoAdvance(): String? {
        val s = _ui.value
        val focus = s.focus ?: return null
        if (!autoAccession.value || s.guided != null) return null
        if (s.selected == null && s.detections.size > 1) return null
        if (autoStartedFor == focus.isbn13) return null
        if (s.previews[focus.isbn13] !is Preview.Done) return null
        autoStartedFor = focus.isbn13
        return focus.isbn13
    }

    /** No book in view any more: the next one (or the same one again) may start automatically. */
    fun releaseAuto() {
        autoStartedFor = null
    }

    /** A copy was saved: forget the book on screen and get ready for the next one. */
    fun resetForNextBook(savedAccession: String?) {
        tracks.clear()
        requested.clear()
        autoStartedFor = null
        _ui.value = ScannerUiState(lastSaved = savedAccession)
    }

    /** Starts a guided run over [steps]. [preset] are values already known (e.g. the scanned ISBN). */
    fun startGuided(steps: List<FillStep>, preset: Map<FillStep, String> = emptyMap()) {
        if (steps.isEmpty() || _ui.value.guided != null) return
        _ui.update { it.copy(guided = GuidedState(steps, 0, preset)) }
    }

    /** The user confirmed [text] for the current step: keep it and ask for the next field. */
    fun acceptGuided(text: String) {
        val value = text.trim()
        val g = _ui.value.guided ?: return
        val step = g.current ?: return
        if (value.isEmpty()) return
        _ui.update { it.copy(guided = g.copy(index = g.index + 1, values = g.values + (step to value))) }
    }

    fun skipGuided() {
        val g = _ui.value.guided ?: return
        _ui.update { it.copy(guided = g.copy(index = g.index + 1)) }
    }

    fun backGuided() {
        val g = _ui.value.guided ?: return
        _ui.update { it.copy(guided = g.copy(index = (g.index - 1).coerceAtLeast(0))) }
    }

    /** "Done": stop asking, keep what has been read. */
    fun finishGuided() {
        val g = _ui.value.guided ?: return
        _ui.update { it.copy(guided = g.copy(index = g.steps.size)) }
    }

    /** Called once the finished values have been handed on. */
    fun clearGuided() = _ui.update { it.copy(guided = null) }

    private companion object {
        const val MIN_HITS = 2
        const val GRACE_MS = 450L
        const val EASE = 0.35f
    }
}
