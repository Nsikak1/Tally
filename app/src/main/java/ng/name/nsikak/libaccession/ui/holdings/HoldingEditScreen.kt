package ng.name.nsikak.libaccession.ui.holdings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.domain.AccessionFormat
import ng.name.nsikak.libaccession.domain.Isbn
import ng.name.nsikak.libaccession.domain.accessionProblem
import ng.name.nsikak.libaccession.domain.formatServerTime
import ng.name.nsikak.libaccession.ui.AppViewModelProvider
import ng.name.nsikak.libaccession.ui.common.BookPreviewCard
import ng.name.nsikak.libaccession.ui.common.Preview
import ng.name.nsikak.libaccession.ui.common.appContainer
import ng.name.nsikak.libaccession.ui.scanner.FillStep
import java.util.TimeZone

/** Add a copy ([accession] null) or edit / delete one. [isbn] pre-fills a new copy (coming from the scanner). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingEditScreen(
    accession: String?,
    prefill: Prefill,
    savedState: SavedStateHandle,
    onBack: () -> Unit,
    onFillWithCamera: (String) -> Unit,
    onSaved: (String) -> Unit,
    vm: HoldingEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val covers = appContainer().covers
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(accession, prefill) { vm.start(accession, prefill) }
    // Back from the camera: put what it read into the form.
    LaunchedEffect(Unit) {
        val filled = FillStep.entries.mapNotNull { step ->
            savedState.get<String>("fill_${step.key}")?.let { step.key to it }
        }.toMap()
        if (filled.isNotEmpty()) {
            FillStep.entries.forEach { savedState.remove<String>("fill_${it.key}") }
            vm.applyFilled(filled)
        }
    }
    // A new copy was saved: on to the next book. An edit or a delete just goes back.
    LaunchedEffect(ui.done) { if (ui.done) { if (ui.creating) onSaved(ui.form.accession) else onBack() } }

    val form = ui.form
    val isbnText = form.isbn.trim()
    val isbnInvalid = isbnText.isNotEmpty() && Isbn.parse(isbnText) == null
    val canFill = ((ui.preview as? Preview.Done)?.lookup as? Lookup.Found) != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.creating) "Add a copy" else form.accession) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            if (ui.loading || ui.saving) LinearProgressIndicator(Modifier.fillMaxWidth())

            val previewIsbn = ui.previewIsbn
            if (previewIsbn != null) {
                BookPreviewCard(
                    isbn = previewIsbn,
                    preview = ui.preview,
                    covers = covers,
                    onRetry = vm::retryPreview,
                    hideAccession = ui.original?.accession,
                )
            }

            OutlinedTextField(
                value = form.accession,
                onValueChange = vm::onAccessionChange,
                enabled = ui.creating,
                singleLine = true,
                label = { Text("Accession number") },
                isError = ui.creating && form.accession.isNotEmpty() && accessionProblem(form.accession) != null,
                supportingText = {
                    val problem = accessionProblem(form.accession)
                    when {
                        !ui.creating -> Text("The accession number can't be changed.")
                        form.accession.isEmpty() -> Text("For example ${AccessionFormat.examples.joinToString(" or ")}")
                        problem != null -> Text(problem)
                        !AccessionFormat.isValid(form.accession) ->
                            Text("Usual format: ${AccessionFormat.describe()}. The server accepts this anyway.")
                        else -> Unit
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.isbn,
                onValueChange = vm::onIsbnChange,
                singleLine = true,
                label = { Text("ISBN (optional)") },
                isError = isbnInvalid,
                supportingText = { if (isbnInvalid) Text("Not a valid ISBN (check the digits).") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )

            // The camera can fill whatever is still empty, one field at a time.
            val blankSteps = buildList {
                if (form.isbn.isBlank()) add(FillStep.ISBN)
                if (form.title.isBlank()) add(FillStep.TITLE)
                if (form.author.isBlank()) add(FillStep.AUTHOR)
                if (ui.creating && form.accession.isBlank()) add(FillStep.ACCESSION)
            }
            if (blankSteps.isNotEmpty() && !ui.loading) {
                OutlinedButton(onClick = { onFillWithCamera(FillStep.toKeys(blankSteps)) }) {
                    Text("Fill the empty fields with the camera")
                }
            }

            if (canFill) {
                OutlinedButton(onClick = vm::fillFromCatalog) { Text("Use the catalog's title and author") }
            }

            OutlinedTextField(
                value = form.title,
                onValueChange = vm::onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.author,
                onValueChange = vm::onAuthorChange,
                label = { Text("Author") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.shelf,
                onValueChange = vm::onShelfChange,
                singleLine = true,
                label = { Text("Shelf") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Status", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HOLDING_STATUSES.forEach { status ->
                    FilterChip(
                        selected = form.status == status,
                        onClick = { vm.onStatusChange(status) },
                        label = { Text(status) },
                    )
                }
            }

            OutlinedTextField(
                value = form.note,
                onValueChange = vm::onNoteChange,
                minLines = 2,
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )

            ui.original?.let { original ->
                val now = System.currentTimeMillis()
                val zone = TimeZone.getDefault()
                Text(
                    "Added ${formatServerTime(original.addedAt, now, zone)} · updated ${formatServerTime(original.updatedAt, now, zone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save, enabled = !ui.saving && !ui.loading) {
                    Text(if (ui.creating) "Save copy" else "Save changes")
                }
                if (!ui.creating && ui.original != null) {
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !ui.saving) { Text("Delete") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${form.accession}?") },
            text = { Text("This removes the copy from the library list on the server. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
