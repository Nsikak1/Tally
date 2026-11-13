package ng.name.nsikak.libaccession.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class HealthResponse(val ok: Boolean)

/** A catalog book (GET /isbn, and the `book` of GET /scan). Almost every field can be null on the server. */
@Serializable
data class Book(
    val isbn: String,
    val title: String? = null,
    val author: String? = null,
    val work: Long? = null,
    val editions: Int = 0,
    val year: Int? = null,
    val lang: String? = null,
    val dewey: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
)

/** One physical copy owned by the library. Timestamps are UTC "YYYY-MM-DD HH:MM:SS". */
@Serializable
data class Holding(
    val id: Long = 0,
    val accession: String,
    val isbn: String? = null,
    val work: Long? = null,
    val title: String? = null,
    val author: String? = null,
    val shelf: String? = null,
    val status: String = "in",
    val note: String? = null,
    @SerialName("added_at") val addedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

/** A holding as returned inside GET /scan: same fields plus whether it is the very same edition. */
@Serializable
data class Copy(
    val id: Long = 0,
    val accession: String,
    val isbn: String? = null,
    val work: Long? = null,
    val title: String? = null,
    val author: String? = null,
    val shelf: String? = null,
    val status: String = "in",
    val note: String? = null,
    @SerialName("added_at") val addedAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("same_edition") val sameEdition: Boolean = false,
)

@Serializable
data class ScanResponse(
    val book: Book? = null,
    val copies: List<Copy> = emptyList(),
)

/**
 * GET /lookup/{isbn}: the SERVER asks Google Books, Library of Congress and other free catalogs for a book
 * its own catalog doesn't have (server/online_lookup.py). [tried] answered; [unreachable] gave no answer.
 */
@Serializable
data class LookupResponse(
    val book: Book? = null,
    val source: String? = null,
    val tried: List<String> = emptyList(),
    val unreachable: List<String> = emptyList(),
)

@Serializable
data class HoldingsPage(
    val offset: Int = 0,
    val results: List<Holding> = emptyList(),
)

/** Body of POST /holdings. Null fields are left out of the JSON (see ApiFactory: explicitNulls = false). */
@Serializable
data class NewHolding(
    val accession: String,
    val isbn: String? = null,
    val work: Long? = null,
    val title: String? = null,
    val author: String? = null,
    val shelf: String? = null,
    val status: String = "in",
    val note: String? = null,
)

/** Everything the app uses from the library server. See docs/SERVER_BRIEFING.md. */
interface LibraryApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @GET("scan/{isbn}")
    suspend fun scan(@Path("isbn") isbn: String): ScanResponse

    @GET("lookup/{isbn}")
    suspend fun lookup(@Path("isbn") isbn: String): LookupResponse

    @GET("isbn/{isbn}")
    suspend fun isbn(@Path("isbn") isbn: String): Book

    @GET("holdings")
    suspend fun holdings(
        @Query("q") q: String?,
        @Query("status") status: String?,
        @Query("shelf") shelf: String?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): HoldingsPage

    @GET("holdings/{accession}")
    suspend fun holding(@Path("accession") accession: String): Holding

    @POST("holdings")
    suspend fun createHolding(@Body body: NewHolding): Holding

    /** A JsonObject, not a data class: PATCH needs explicit nulls ("null clears a field"). */
    @PATCH("holdings/{accession}")
    suspend fun updateHolding(@Path("accession") accession: String, @Body changes: JsonObject): Holding

    @DELETE("holdings/{accession}")
    suspend fun deleteHolding(@Path("accession") accession: String): Response<Unit>
}
