package ng.name.nsikak.libaccession.ui.holdings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.name.nsikak.libaccession.data.api.Holding
import ng.name.nsikak.libaccession.domain.Period
import ng.name.nsikak.libaccession.domain.formatServerTime
import ng.name.nsikak.libaccession.ui.AppViewModelProvider
import java.util.TimeZone

/** What the server has saved: every copy added in the chosen period (default: today), searchable, tap to edit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    vm: HoldingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    // Load now, and again whenever we come back from the editor.
    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library list") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add a copy")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search title, author or accession") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Period.entries.forEach { period ->
                    FilterChip(
                        selected = ui.period == period,
                        onClick = { vm.onPeriod(period) },
                        label = { Text(period.label) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = ui.status == null, onClick = { vm.onStatus(null) }, label = { Text("Any status") })
                HOLDING_STATUSES.forEach { status ->
                    FilterChip(
                        selected = ui.status == status,
                        onClick = { vm.onStatus(status) },
                        label = { Text(status) },
                    )
                }
            }

            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val summary = buildString {
                append(ui.items.size)
                append(if (ui.items.size == 1) " copy" else " copies")
                append(" · ")
                append(ui.period.label.lowercase())
                if (ui.truncated) append(" · showing the newest ones; narrow the period or search to see the rest")
            }
            if (ui.loaded || ui.items.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val error = ui.error
            if (error != null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.reload() }) { Text("Try again") }
                }
            } else if (ui.loaded && ui.items.isEmpty() && !ui.loading) {
                Text(
                    if (ui.period == Period.ALL && ui.query.isBlank() && ui.status == null) {
                        "Nothing has been saved on the server yet."
                    } else {
                        "No copies match. Try a longer period or clear the search."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val now = System.currentTimeMillis()
            val zone = TimeZone.getDefault()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.items, key = { it.accession }) { holding ->
                    HoldingRow(holding, now, zone, onClick = { onOpen(holding.accession) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldingRow(holding: Holding, now: Long, zone: TimeZone, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    holding.accession,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(holding.status)
            }
            Text(
                holding.title ?: "(no title)",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            holding.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val details = listOfNotNull(
                holding.shelf?.takeIf { it.isNotBlank() }?.let { "Shelf $it" },
                holding.isbn,
                if (holding.addedAt.isNotBlank()) "added ${formatServerTime(holding.addedAt, now, zone)}" else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusTag(status: String) {
    val scheme = MaterialTheme.colorScheme
    val color = when (status) {
        "in" -> scheme.secondaryContainer
        "out" -> scheme.tertiaryContainer
        "lost" -> scheme.errorContainer
        else -> scheme.surfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
