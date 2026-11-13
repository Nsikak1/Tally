package ng.name.nsikak.libaccession.ui.holdings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.data.api.Holding
import ng.name.nsikak.libaccession.data.api.NewHolding
import ng.name.nsikak.libaccession.data.api.describeError
import ng.name.nsikak.libaccession.data.repo.BookRepository
import ng.name.nsikak.libaccession.data.repo.HoldingsRepository
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.domain.accessionProblem
import ng.name.nsikak.libaccession.domain.correctAccessionInput
import ng.name.nsikak.libaccession.domain.normalizeAccessionInput
import ng.name.nsikak.libaccession.ui.common.Preview

/** What the form fields currently say. Empty text means "no value". */
data class EditForm(
    val accession: String = "",
    val isbn: String = "",
    val title: String = "",
    val author: String = "",
    val shelf: String = "",
    val status: String = "in",
    val note: String = "",
)

data class EditUiState(
    val creating: Boolean = true,
    val loading: Boolean = false,
    val form: EditForm = EditForm(),
    /** The copy as the server has it (editing only); the Save button sends only what differs from this. */
    val original: Holding? = null,
    val previewIsbn: String? = null,
    val preview: Preview? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

/** Values to start a new copy with (from the scanner or the guided camera fill). */
data class Prefill(
    val isbn: String? = null,
    val title: String? = null,
    val author: String? = null,
    val accession: String? = null,
)

/** Add a copy, or edit / delete an existing one. The book preview follows the ISBN field. */
class HoldingEditViewModel(
    private val holdings: HoldingsRepository,
    private val books: BookRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(EditUiState())
    val ui: StateFlow<EditUiState> = _ui.asStateFlow()

    private var started = false
    private var lookupJob: Job? = null

    /** Called by the screen; only the first call counts. [accession] = edit that copy, else add a new one starting from [prefill]. */
    fun start(accession: String?, prefill: Prefill) {
        if (started) return
        started = true
        if (accession == null) {
            _ui.update {
                it.copy(
                    creating = true,
                    form = it.form.copy(
                        isbn = prefill.isbn.orEmpty(),
                        title = prefill.title.orEmpty(),
                        author = prefill.author.orEmpty(),
                        accession = correctAccessionInput(prefill.accession.orEmpty()),
                    ),
                )
            }
            Isbn.parse(prefill.isbn.orEmpty())?.let { lookupIsbn(it.isbn13, fillFields = true) }
        } else {
            load(accession)
        }
    }

    /** Values read by the camera ("isbn", "title", "author", "accession") are put into the form. */
    fun applyFilled(values: Map<String, String>) {
        values["isbn"]?.let { onIsbnChange(it) }
        values["title"]?.let { onTitleChange(it) }
        values["author"]?.let { onAuthorChange(it) }
        // The accession number of an existing copy can't change.
        if (_ui.value.creating) values["accession"]?.let { onAccessionChange(it) }
    }

    private fun load(accession: String) {
        viewModelScope.launch {
            _ui.update { it.copy(creating = false, loading = true, form = it.form.copy(accession = accession)) }
            try {
                val holding = holdings.get(accession)
                _ui.update { it.copy(loading = false, original = holding, form = holding.toForm()) }
                holding.isbn?.let { lookupIsbn(it, fillFields = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = describeError(e)) }
            }
        }
    }

    // ---- field changes ----

    fun onAccessionChange(text: String) = edit { it.copy(accession = correctAccessionInput(text)) }
    fun onTitleChange(text: String) = edit { it.copy(title = text) }
    fun onAuthorChange(text: String) = edit { it.copy(author = text) }
    fun onShelfChange(text: String) = edit { it.copy(shelf = text) }
    fun onNoteChange(text: String) = edit { it.copy(note = text) }
    fun onStatusChange(status: String) = edit { it.copy(status = status) }

    private fun edit(change: (EditForm) -> EditForm) =
        _ui.update { it.copy(form = change(it.form), error = null) }

    fun onIsbnChange(text: String) {
        edit { it.copy(isbn = text) }
        val parsed = Isbn.parse(text)
        if (parsed == null) {
            lookupJob?.cancel()
            _ui.update { it.copy(previewIsbn = null, preview = null) }
        } else if (_ui.value.previewIsbn != parsed.isbn13) {
            lookupIsbn(parsed.isbn13, fillFields = _ui.value.creating)
        }
    }

    fun retryPreview() {
        _ui.value.previewIsbn?.let { lookupIsbn(it, fillFields = false) }
    }

    /** The "Use catalog title & author" button. */
    fun fillFromCatalog() {
        val found = ((_ui.value.preview as? Preview.Done)?.lookup as? Lookup.Found)?.book ?: return
        edit { it.copy(title = found.title.orEmpty(), author = found.author.orEmpty()) }
    }

    private fun lookupIsbn(isbn13: String, fillFields: Boolean) {
        lookupJob?.cancel()
        _ui.update { it.copy(previewIsbn = isbn13, preview = Preview.Loading) }
        lookupJob = viewModelScope.launch {
            val result = books.lookup(isbn13)
            _ui.update { state ->
                if (state.previewIsbn != isbn13) {
                    state
                } else {
                    var form = state.form
                    if (fillFields && result is Lookup.Found) {
                        // Adding a copy: start from the catalog's title and author (still editable).
                        if (form.title.isBlank()) form = form.copy(title = result.book.title.orEmpty())
                        if (form.author.isBlank()) form = form.copy(author = result.book.author.orEmpty())
                    }
                    state.copy(preview = Preview.Done(result), form = form)
                }
            }
        }
    }

    // ---- save / delete ----

    fun save() {
        val state = _ui.value
        if (state.saving || state.loading) return
        val form = state.form

        val isbnText = form.isbn.trim()
        val parsed = if (isbnText.isEmpty()) null else Isbn.parse(isbnText)
        if (isbnText.isNotEmpty() && parsed == null) {
            fail("That ISBN isn't valid. Check the digits, or clear the field.")
            return
        }
        val isbn13 = parsed?.isbn13
        val found = ((state.preview as? Preview.Done)?.lookup as? Lookup.Found)?.book?.takeIf { it.isbn == isbn13 }

        if (state.creating) {
            val accession = correctAccessionInput(form.accession)
            accessionProblem(accession)?.let {
                fail(it)
                return
            }
            launchSave {
                holdings.create(
                    NewHolding(
                        accession = accession,
                        isbn = isbn13,
                        work = found?.work,
                        title = form.title.trim().ifBlank { null },
                        author = form.author.trim().ifBlank { null },
                        shelf = form.shelf.trim().ifBlank { null },
                        status = form.status,
                        note = form.note.trim().ifBlank { null },
                    ),
                )
            }
        } else {
            val original = state.original ?: return
            val changes = LinkedHashMap<String, Any?>()
            fun diff(key: String, new: String, old: String?) {
                val value = new.trim().ifBlank { null }
                if (value != old) changes[key] = value
            }
            diff("title", form.title, original.title)
            diff("author", form.author, original.author)
            diff("shelf", form.shelf, original.shelf)
            diff("note", form.note, original.note)
            if (isbn13 != original.isbn) {
                changes["isbn"] = isbn13
                changes["work"] = found?.work // the server does not fill this in on PATCH
            }
            if (form.status != original.status) changes["status"] = form.status
            if (changes.isEmpty()) {
                fail("Nothing has changed.")
                return
            }
            launchSave { holdings.update(original.accession, changes) }
        }
    }

    fun delete() {
        val accession = _ui.value.original?.accession ?: return
        launchSave { holdings.delete(accession) }
    }

    private fun launchSave(call: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            try {
                call()
                _ui.update { it.copy(saving = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = describeError(e)) }
            }
        }
    }

    private fun fail(message: String) = _ui.update { it.copy(error = message) }
}

private fun Holding.toForm() = EditForm(
    accession = accession,
    isbn = isbn.orEmpty(),
    title = title.orEmpty(),
    author = author.orEmpty(),
    shelf = shelf.orEmpty(),
    status = status,
    note = note.orEmpty(),
)
