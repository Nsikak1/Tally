package ng.name.nsikak.libaccession.data.lookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ng.name.nsikak.libaccession.data.api.Book
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Open Library's live Books API (https://openlibrary.org/api/books?bibkeys=ISBN:...). Free, no key.
 * Note: the library server's catalog is an Open Library DUMP (2026-09-30), so this only adds books
 * that were added to Open Library after that date. It is a different service from Google Books.
 */
class OpenLibraryProvider(private val client: OkHttpClient) : BookProvider {
    override val name = "Open Library (online)"

    override suspend fun find(isbn13: String): ProviderResult =
        withContext(Dispatchers.IO) {
            try {
                fetch(isbn13)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderResult.Unavailable(e.message ?: e::class.java.simpleName)
            }
        }

    private fun fetch(isbn13: String): ProviderResult {
        val url = "https://openlibrary.org/api/books".toHttpUrl().newBuilder()
            .addQueryParameter("bibkeys", "ISBN:$isbn13")
            .addQueryParameter("format", "json")
            .addQueryParameter("jscmd", "data")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ProviderResult.Unavailable("HTTP ${response.code}")
            val body = response.body?.string() ?: return ProviderResult.Unavailable("empty answer")
            return parseOpenLibrary(body, isbn13)
        }
    }
}

internal fun parseOpenLibrary(body: String, isbn13: String): ProviderResult {
    val root = Json.parseToJsonElement(body).jsonObject
    val data = root["ISBN:$isbn13"] as? JsonObject ?: return ProviderResult.NotFound // {} when unknown
    val title = data.text("title") ?: return ProviderResult.NotFound
    val subtitle = data.text("subtitle")
    val authors = (data["authors"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.text("name") }
        .joinToString("; ")
        .ifBlank { null }
    val cover = (data["cover"] as? JsonObject)?.let { it.text("medium") ?: it.text("large") ?: it.text("small") }
    val year = data.text("publish_date")?.let { Regex("\\d{4}").find(it)?.value }?.toIntOrNull()

    return ProviderResult.Found(
        Book(
            isbn = isbn13,
            title = if (subtitle != null) "$title: $subtitle" else title,
            author = authors,
            year = year,
            coverUrl = cover,
        ),
    )
}
