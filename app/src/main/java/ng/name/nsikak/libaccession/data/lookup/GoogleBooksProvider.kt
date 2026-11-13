package ng.name.nsikak.libaccession.data.lookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import ng.name.nsikak.libaccession.data.api.Book
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Google Books (https://www.googleapis.com/books/v1/volumes?q=isbn:...). Works without a key; a key raises the daily quota. */
class GoogleBooksProvider(
    private val client: OkHttpClient,
    private val apiKey: suspend () -> String,
) : BookProvider {
    override val name = "Google Books"

    override suspend fun find(isbn13: String): ProviderResult {
        val key = apiKey().trim()
        return withContext(Dispatchers.IO) {
            try {
                fetch(isbn13, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderResult.Unavailable(e.message ?: e::class.java.simpleName)
            }
        }
    }

    private fun fetch(isbn13: String, key: String): ProviderResult {
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", "isbn:$isbn13")
            .addQueryParameter("maxResults", "5")
            .apply { if (key.isNotEmpty()) addQueryParameter("key", key) }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) return ProviderResult.Unavailable("daily limit reached")
            if (!response.isSuccessful) return ProviderResult.Unavailable("HTTP ${response.code}")
            val body = response.body?.string() ?: return ProviderResult.Unavailable("empty answer")
            return parseGoogleBooks(body, isbn13)
        }
    }
}

internal const val USER_AGENT = "Tally/0.6 (school library app)"

/** Pure parsing, kept separate so it can be checked without a network. */
internal fun parseGoogleBooks(body: String, isbn13: String): ProviderResult {
    val root = Json.parseToJsonElement(body).jsonObject
    val volumes = (root["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    if (volumes.isEmpty()) return ProviderResult.NotFound

    fun info(v: JsonObject) = v["volumeInfo"] as? JsonObject
    fun hasIsbn(v: JsonObject): Boolean =
        (info(v)?.get("industryIdentifiers") as? JsonArray).orEmpty().any {
            ((it as? JsonObject)?.get("identifier") as? JsonPrimitive)?.contentOrNull == isbn13
        }

    val pick = volumes.firstOrNull { hasIsbn(it) } ?: volumes.first()
    val volume = info(pick) ?: return ProviderResult.NotFound
    val title = volume.text("title") ?: return ProviderResult.NotFound
    val subtitle = volume.text("subtitle")
    val authors = (volume["authors"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .joinToString("; ")
        .ifBlank { null }
    val cover = ((volume["imageLinks"] as? JsonObject)?.text("thumbnail")
        ?: (volume["imageLinks"] as? JsonObject)?.text("smallThumbnail"))
        ?.replaceFirst("http://", "https://")

    return ProviderResult.Found(
        Book(
            isbn = isbn13,
            title = if (subtitle != null) "$title: $subtitle" else title,
            author = authors,
            year = volume.text("publishedDate")?.take(4)?.toIntOrNull(),
            lang = volume.text("language"),
            coverUrl = cover,
        ),
    )
}

internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
