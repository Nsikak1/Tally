package ng.name.nsikak.libaccession.ui.addcopy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.data.api.ApiException
import ng.name.nsikak.libaccession.data.api.Book
import ng.name.nsikak.libaccession.data.api.NewHolding
import ng.name.nsikak.libaccession.data.api.describeError
import ng.name.nsikak.libaccession.data.repo.BookRepository
import ng.name.nsikak.libaccession.data.repo.HoldingsRepository
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.data.settings.SettingsStore
import ng.name.nsikak.libaccession.domain.AccessionFormat
import ng.name.nsikak.libaccession.domain.AccessionTemplate
import ng.name.nsikak.libaccession.ui.common.Preview

/** The steps of adding one copy after its ISBN is known. No camera text reading here: the number is typed. */
enum class AddCopyStage {
    /** Asking the library server and the online catalogs about the ISBN (or: could not reach the server). */
    LOOKING,

    /** Nobody knows the ISBN: confirm that, and ask whether to type the book's details by hand. */
    NOT_FOUND,

    /** Typing the details of a book no catalog knows. */
    MANUAL,

    /** The book's details are on screen; pick the prefix, type the number, save. */
    ACCESSION,
}

/** Details typed by hand for a book no catalog knows. */
data class ManualDetails(val title: String = "", val author: String = "", val shelf: String = "", val note: String = "")

data class AddCopyUi(
    val isbn: String = "",
    val stage: AddCopyStage = AddCopyStage.LOOKING,
    /** What the catalogs said; null until the lookup starts. */
    val preview: Preview? = null,
    /** Set when the library server could not be reached (the lookup gave no answer at all). */
    val lookupError: String? = null,
    val manual: ManualDetails = ManualDetails(),
    /** True when the book's details came from the keyboard, not from a catalog. */
    val useManual: Boolean = false,
    val templates: List<AccessionTemplate> = emptyList(),
    /** The chosen format's pattern (one of [templates]). */
    val pattern: String = "",
    /** ONLY the digits typed after the prefix. */
    val digits: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val savedAccession: String? = null,
) {
    val template: AccessionTemplate? get() = templates.firstOrNull { it.pattern == pattern } ?: templates.firstOrNull()
    val book: Book? get() = ((preview as? Preview.Done)?.lookup as? Lookup.Found)?.book
    val canSave: Boolean get() = !saving && template?.isComplete(digits) == true
    val accession: String? get() = template?.takeIf { digits.isNotEmpty() }?.full(digits)
}

class AddCopyViewModel(
    private val holdings: HoldingsRepository,
    private val books: BookRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(AddCopyUi())
    val ui: StateFlow<AddCopyUi> = _ui.asStateFlow()

    private var started = false

    fun start(isbn13: String) {
        if (started) return
        started = true
        val templates = AccessionFormat.patterns.mapNotNull(AccessionTemplate::parse)
            .ifEmpty { SettingsStore.DEFAULT_ACCESSION_FORMATS.mapNotNull(AccessionTemplate::parse) }
        _ui.update { it.copy(isbn = isbn13, templates = templates, pattern = templates.first().pattern) }
        viewModelScope.launch {
            val last = settings.lastAccessionPattern.first()
            if (last != null && templates.any { it.pattern == last }) _ui.update { it.copy(pattern = last) }
        }
        lookup()
    }

    fun lookup() {
        val isbn = _ui.value.isbn
        viewModelScope.launch {
            _ui.update { it.copy(stage = AddCopyStage.LOOKING, preview = Preview.Loading, lookupError = null) }
            val result = books.lookup(isbn)
            _ui.update {
                when (result) {
                    is Lookup.Found -> it.copy(stage = AddCopyStage.ACCESSION, preview = Preview.Done(result), useManual = false)
                    is Lookup.NotInCatalog -> it.copy(stage = AddCopyStage.NOT_FOUND, preview = Preview.Done(result))
                    is Lookup.Failed -> it.copy(stage = AddCopyStage.LOOKING, preview = null, lookupError = result.message)
                }
            }
        }
    }

    // ---- not found ----
    fun fillByHand() = _ui.update { it.copy(stage = AddCopyStage.MANUAL, useManual = true, error = null) }

    /** "No": add the copy with just the ISBN and the accession number. */
    fun skipDetails() = _ui.update { it.copy(stage = AddCopyStage.ACCESSION, useManual = false, error = null) }

    // ---- manual details ----
    fun onTitle(v: String) = _ui.update { it.copy(manual = it.manual.copy(title = v)) }
    fun onAuthor(v: String) = _ui.update { it.copy(manual = it.manual.copy(author = v)) }
    fun onShelf(v: String) = _ui.update { it.copy(manual = it.manual.copy(shelf = v)) }
    fun onNote(v: String) = _ui.update { it.copy(manual = it.manual.copy(note = v)) }
    fun detailsDone() = _ui.update { it.copy(stage = AddCopyStage.ACCESSION, error = null) }

    // ---- accession number ----
    fun choosePattern(pattern: String) {
        _ui.update { s ->
            val t = s.templates.firstOrNull { it.pattern == pattern } ?: return@update s
            s.copy(pattern = pattern, digits = t.clean(s.digits), error = null)
        }
        viewModelScope.launch { settings.saveLastAccessionPattern(pattern) }
    }

    fun onDigits(raw: String) = _ui.update { s ->
        val t = s.template ?: return@update s
        s.copy(digits = t.clean(raw), error = null) // digits only, however they got in (typing, paste)
    }

    /** System Back: one step back inside this screen. false = nothing left to go back to, leave the screen. */
    fun back(): Boolean {
        val s = _ui.value
        if (s.saving) return true
        val previous: AddCopyStage? = when {
            s.stage == AddCopyStage.MANUAL -> AddCopyStage.NOT_FOUND
            s.stage == AddCopyStage.ACCESSION && s.useManual -> AddCopyStage.MANUAL
            s.stage == AddCopyStage.ACCESSION && s.preview.isNotInCatalog() -> AddCopyStage.NOT_FOUND
            else -> null
        }
        if (previous == null) return false
        _ui.update { it.copy(stage = previous, error = null) }
        return true
    }

    fun save() {
        val s = _ui.value
        val accession = s.accession ?: return
        if (!s.canSave) return
        val found = s.book
        val m = s.manual
        val item = NewHolding(
            accession = accession,
            isbn = s.isbn,
            work = if (s.useManual) null else found?.work,
            title = if (s.useManual) m.title.trim().ifBlank { null } else found?.title,
            author = if (s.useManual) m.author.trim().ifBlank { null } else found?.author,
            shelf = if (s.useManual) m.shelf.trim().ifBlank { null } else null,
            note = if (s.useManual) m.note.trim().ifBlank { null } else null,
        )
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                holdings.create(item)
                _ui.update { it.copy(saving = false, savedAccession = accession) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is ApiException && e.code == 409) {
                    "$accession is already in the library. Type a different number."
                } else {
                    describeError(e)
                }
                _ui.update { it.copy(saving = false, error = message) }
            }
        }
    }
}

private fun Preview?.isNotInCatalog(): Boolean = (this as? Preview.Done)?.lookup is Lookup.NotInCatalog
