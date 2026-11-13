package ng.name.nsikak.libaccession.ui.addcopy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.domain.AccessionTemplate
import ng.name.nsikak.libaccession.ui.AppViewModelProvider
import ng.name.nsikak.libaccession.ui.common.BookPreviewCard
import ng.name.nsikak.libaccession.ui.common.Preview
import ng.name.nsikak.libaccession.ui.common.appContainer

/**
 * Adding one copy after its ISBN is known: (not found? confirm and maybe type the details) -> the book's details
 * -> pick a prefix, type the number on a digits-only keypad -> Save -> back to the scanner for the next book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCopyScreen(
    isbn: String,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    /** Open the full copy form (shelf, status, note ...) with what is known so far. */
    onEditAllFields: (isbn: String, title: String?, author: String?, accession: String?) -> Unit,
    vm: AddCopyViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val covers = appContainer().covers

    LaunchedEffect(isbn) { vm.start(isbn) }
    LaunchedEffect(ui.savedAccession) { ui.savedAccession?.let(onSaved) }
    BackHandler { if (!vm.back()) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a copy") },
                navigationIcon = {
                    IconButton(onClick = { if (!vm.back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (ui.stage) {
                AddCopyStage.LOOKING -> Looking(ui, onRetry = vm::lookup)
                AddCopyStage.NOT_FOUND -> NotFound(ui, onFillByHand = vm::fillByHand, onSkip = vm::skipDetails)
                AddCopyStage.MANUAL -> Manual(ui, vm)
                AddCopyStage.ACCESSION -> AccessionStep(
                    ui = ui,
                    vm = vm,
                    covers = covers,
                    onEditAllFields = {
                        val title = if (ui.useManual) ui.manual.title.trim().ifBlank { null } else ui.book?.title
                        val author = if (ui.useManual) ui.manual.author.trim().ifBlank { null } else ui.book?.author
                        onEditAllFields(ui.isbn, title, author, ui.accession)
                    },
                )
            }
        }
    }
}

@Composable
private fun Looking(ui: AddCopyUi, onRetry: () -> Unit) {
    if (ui.lookupError != null) {
        Text("Couldn't ask the library server", style = MaterialTheme.typography.titleMedium)
        Text(ui.lookupError, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Try again") }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking up ${ui.isbn}…")
        }
    }
}

@Composable
private fun NotFound(ui: AddCopyUi, onFillByHand: () -> Unit, onSkip: () -> Unit) {
    val lookup = (ui.preview as? Preview.Done)?.lookup as? Lookup.NotInCatalog
    Text("This book couldn't be found", style = MaterialTheme.typography.titleLarge)
    val asked = listOf("the library server") + (lookup?.tried ?: emptyList())
    Text(
        "ISBN ${ui.isbn} isn't known to ${asked.joinToString(", ")}.",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (!lookup?.unreachable.isNullOrEmpty()) {
        Text(
            "No answer from ${lookup?.unreachable?.joinToString(", ")} (no internet, or its limit is used up), " +
                "so it may exist there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text("Do you want to fill in the book's details by hand before the accession number?", style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onFillByHand, modifier = Modifier.fillMaxWidth()) { Text("Yes, fill in the details") }
    OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("No, go to the accession number") }
}

@Composable
private fun Manual(ui: AddCopyUi, vm: AddCopyViewModel) {
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { titleFocus.requestFocus() }
    }
    Text("Book details", style = MaterialTheme.typography.titleLarge)
    Text("ISBN ${ui.isbn}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    OutlinedTextField(
        value = ui.manual.title,
        onValueChange = vm::onTitle,
        label = { Text("Title") },
        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = ui.manual.author,
        onValueChange = vm::onAuthor,
        label = { Text("Author") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = ui.manual.shelf,
        onValueChange = vm::onShelf,
        label = { Text("Shelf (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = ui.manual.note,
        onValueChange = vm::onNote,
        label = { Text("Note (optional)") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
    )
    Button(onClick = vm::detailsDone, modifier = Modifier.fillMaxWidth()) { Text("Next: accession number") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessionStep(
    ui: AddCopyUi,
    vm: AddCopyViewModel,
    covers: ng.name.nsikak.libaccession.data.cover.CoverLoader,
    onEditAllFields: () -> Unit,
) {
    val template = ui.template ?: return
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Ready to type the moment the page opens.
    LaunchedEffect(Unit) {
        delay(80) // let the field attach before asking for focus
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    // 1. what the book is
    if (ui.useManual) {
        Text(ui.manual.title.ifBlank { "(no title)" }, style = MaterialTheme.typography.titleMedium)
        if (ui.manual.author.isNotBlank()) Text(ui.manual.author, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                "ISBN ${ui.isbn}",
                ui.manual.shelf.trim().takeIf { it.isNotEmpty() }?.let { "shelf $it" },
                "details typed by hand",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        BookPreviewCard(isbn = ui.isbn, preview = ui.preview, covers = covers, onRetry = vm::lookup)
    }

    // 2. which prefix
    Text("Accession number", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.templates.forEach { t ->
            FilterChip(
                selected = t.pattern == template.pattern,
                onClick = {
                    vm.choosePattern(t.pattern)
                    focus.requestFocus()
                },
                label = { Text(t.prefix, fontFamily = FontFamily.Monospace) },
            )
        }
    }

    // 3. the digits (digits-only keypad; the prefix is already there)
    val transformation = remember(template.pattern) { AccessionDigitsTransformation(template) }
    OutlinedTextField(
        value = ui.digits,
        onValueChange = vm::onDigits,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        prefix = { Text(template.prefix, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        label = { Text("Number") },
        supportingText = { Text(template.hint) },
        isError = ui.error != null,
        visualTransformation = transformation,
        // NumberPassword = digits only on the keypad (no dash, comma, dot or letters); onDigits also strips pasted text.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.save() }),
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
    )
    ui.accession?.let {
        Text("Will be saved as $it", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

    Button(onClick = vm::save, enabled = ui.canSave, modifier = Modifier.fillMaxWidth()) {
        if (ui.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save copy")
    }
    TextButton(onClick = onEditAllFields, enabled = !ui.saving) { Text("Edit all fields (shelf, status, note)…") }
}

/** Shows the typed digits laid out as the format wants ("05123" as "05-123") and keeps the cursor on the digits. */
private class AccessionDigitsTransformation(private val template: AccessionTemplate) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val (shown, offsets) = template.layoutWithOffsets(text.text)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offsets[offset.coerceIn(0, offsets.lastIndex)]
            override fun transformedToOriginal(offset: Int): Int =
                offsets.indexOfLast { it <= offset }.coerceAtLeast(0)
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}
