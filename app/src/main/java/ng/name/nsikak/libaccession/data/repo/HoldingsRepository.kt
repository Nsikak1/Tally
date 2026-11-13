package ng.name.nsikak.libaccession.data.repo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ng.name.nsikak.libaccession.data.api.ApiProvider
import ng.name.nsikak.libaccession.data.api.Holding
import ng.name.nsikak.libaccession.data.api.NewHolding
import ng.name.nsikak.libaccession.data.api.apiCall

/** [truncated] = there may be more items than [items]: narrow the period or the search. */
data class HoldingsWindow(val items: List<Holding>, val truncated: Boolean)

/** The library's own copies. Every method throws ApiException (server said no) or an IO exception (unreachable). */
interface HoldingsRepository {
    suspend fun list(q: String?, status: String?, shelf: String?, limit: Int, offset: Int): List<Holding>

    /** Newest first, only items added at or after [cutoffUtc] ("YYYY-MM-DD HH:MM:SS" UTC; null = no limit). */
    suspend fun listSince(cutoffUtc: String?, q: String?, status: String?, maxItems: Int): HoldingsWindow

    suspend fun get(accession: String): Holding
    suspend fun create(item: NewHolding): Holding

    /** [changes] holds ONLY the fields to change; a null value clears that field (not allowed for "status"). */
    suspend fun update(accession: String, changes: Map<String, Any?>): Holding

    suspend fun delete(accession: String)
}

/**
 * Splits one newest-first page at [cutoffUtc]: the items added at or after the cutoff, and whether an
 * older item was seen (then no further page can contain anything newer). Timestamps compare as text.
 */
fun splitSince(page: List<Holding>, cutoffUtc: String?): Pair<List<Holding>, Boolean> {
    if (cutoffUtc == null) return page to false
    val kept = ArrayList<Holding>()
    for (item in page) {
        if (item.addedAt < cutoffUtc) return kept to true
        kept += item
    }
    return kept to false
}

class ServerHoldingsRepository(private val api: ApiProvider) : HoldingsRepository {

    override suspend fun list(q: String?, status: String?, shelf: String?, limit: Int, offset: Int): List<Holding> =
        apiCall { api.get().holdings(q, status, shelf, limit, offset) }.results

    override suspend fun listSince(cutoffUtc: String?, q: String?, status: String?, maxItems: Int): HoldingsWindow {
        val out = ArrayList<Holding>()
        var offset = 0
        while (true) {
            val page = list(q, status, null, PAGE, offset)
            val (kept, reachedOlder) = splitSince(page, cutoffUtc)
            out += kept
            if (reachedOlder || page.size < PAGE) return HoldingsWindow(out, truncated = false)
            if (out.size >= maxItems) return HoldingsWindow(out.take(maxItems), truncated = true)
            offset += page.size
        }
    }

    override suspend fun get(accession: String): Holding = apiCall { api.get().holding(accession) }

    override suspend fun create(item: NewHolding): Holding = apiCall { api.get().createHolding(item) }

    override suspend fun update(accession: String, changes: Map<String, Any?>): Holding {
        val body = JsonObject(changes.mapValues { (_, v) -> v.toJson() })
        return apiCall { api.get().updateHolding(accession, body) }
    }

    override suspend fun delete(accession: String) {
        apiCall {
            val response = api.get().deleteHolding(accession)
            if (!response.isSuccessful) throw retrofit2.HttpException(response)
            Unit
        }
    }

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private companion object {
        const val PAGE = 200
    }
}
