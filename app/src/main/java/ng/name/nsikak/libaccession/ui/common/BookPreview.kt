package ng.name.nsikak.libaccession.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ng.name.nsikak.libaccession.data.api.Book
import ng.name.nsikak.libaccession.data.api.Copy
import ng.name.nsikak.libaccession.data.cover.CoverLoader
import ng.name.nsikak.libaccession.data.repo.Lookup
import ng.name.nsikak.libaccession.data.repo.SOURCE_SERVER

/** Where a lookup stands: running, or finished with some [Lookup] result. */
sealed interface Preview {
    data object Loading : Preview
    data class Done(val lookup: Lookup) : Preview
}

/** The title a finished lookup found, if any (used as the label on the camera boxes). */
fun Preview?.titleOrNull(): String? =
    ((this as? Preview.Done)?.lookup as? Lookup.Found)?.book?.title

/**
 * The book preview: cover, title, author, year/language/Dewey/editions, and which copies the library
 * already has. Used by the scanner panel and the copy editor.
 *
 * [hideAccession] leaves one copy out of the list (the one being edited).
 */
@Composable
fun BookPreviewCard(
    isbn: String,
    preview: Preview?,
    covers: CoverLoader,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    hideAccession: String? = null,
    onOpenCopy: ((String) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (preview) {
            null, Preview.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Looking up $isbn…", style = MaterialTheme.typography.bodyMedium)
            }
            is Preview.Done -> when (val lookup = preview.lookup) {
                is Lookup.Found -> {
                    BookRow(lookup.book, covers)
                    if (lookup.source != SOURCE_SERVER) {
                        Text(
                            "Details from ${lookup.source}: the library server doesn't have this book.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lookup.offline) {
                        Text(
                            "The server couldn't be reached: this is the copy saved on this device, " +
                                "and the list of copies below is unknown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        CopiesBlock(lookup.copies, hideAccession, onOpenCopy)
                    }
                }
                is Lookup.NotInCatalog -> {
                    Text("Not found in any catalog", style = MaterialTheme.typography.titleMedium)
                    val asked = listOf("the library server") + lookup.tried.map { it }
                    Text(
                        "ISBN $isbn isn't known to ${asked.joinToString(", ")}. " +
                            "You can still add a copy and fill in the details yourself (the camera can read them from the book).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (lookup.unreachable.isNotEmpty()) {
                        Text(
                            "No answer from ${lookup.unreachable.joinToString(", ")} (no internet, or its limit is used up).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    CopiesBlock(lookup.copies, hideAccession, onOpenCopy)
                }
                is Lookup.Failed -> {
                    Text(lookup.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, covers: CoverLoader) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverImage(book.coverUrl, covers, Modifier.size(72.dp, 104.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                book.title ?: "(no title in the catalog)",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val details = listOfNotNull(
                book.year?.toString(),
                book.lang,
                book.dewey?.let { "Dewey $it" },
                if (book.editions > 0) "${book.editions} editions" else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(book.isbn, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CopiesBlock(copies: List<Copy>, hideAccession: String?, onOpenCopy: ((String) -> Unit)?) {
    val shown = copies.filter { it.accession != hideAccession }
    if (shown.isEmpty()) {
        Text(
            if (hideAccession == null) "No copy of this book is in the library list yet." else "No other copies in the library list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "In the library: ${shown.size} ${if (shown.size == 1) "copy" else "copies"}",
        style = MaterialTheme.typography.labelLarge,
    )
    shown.take(5).forEach { copy ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onOpenCopy != null) it.clickable { onOpenCopy(copy.accession) } else it }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(copy.accession, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            val extra = listOfNotNull(
                copy.status,
                copy.shelf?.takeIf { it.isNotBlank() },
                if (copy.sameEdition) null else "other edition",
            ).joinToString(" · ")
            Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (shown.size > 5) Text("+ ${shown.size - 5} more", style = MaterialTheme.typography.bodySmall)
}

/** A book cover from the internet; a plain placeholder while loading, and when there is no cover or no internet. */
@Composable
fun CoverImage(url: String?, loader: CoverLoader, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(url == null) }

    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val bitmap = loader.load(url)
        if (bitmap != null) image = bitmap.asImageBitmap() else failed = true
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        when {
            loaded != null -> Image(
                bitmap = loaded,
                contentDescription = "Book cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Text(
                "No cover",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
