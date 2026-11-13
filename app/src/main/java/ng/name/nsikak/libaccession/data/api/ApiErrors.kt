package ng.name.nsikak.libaccession.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

/** The server answered with an error status. [detail] is a sentence a librarian can read. */
class ApiException(val code: Int, val detail: String) : Exception(detail)

/** Runs a Retrofit call and turns an HTTP error into an [ApiException] carrying the server's `detail`. */
suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpException) {
        throw withContext(Dispatchers.IO) { e.toApiException() }
    }

private fun HttpException.toApiException(): ApiException {
    val raw = try {
        response()?.errorBody()?.string()
    } catch (e: Exception) {
        null
    }
    val detail = parseDetail(raw)
    val message = when (code()) {
        401 -> "The server refused the API key. Enter the right key in Settings."
        else -> detail ?: "Server replied HTTP ${code()}."
    }
    return ApiException(code(), message)
}

/** Errors are {"detail": "..."}; 422 carries a list of {"msg": ...} objects. */
private fun parseDetail(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val detail = (Json.parseToJsonElement(raw) as? JsonObject)?.get("detail")
        when (detail) {
            is JsonPrimitive -> detail.content
            is JsonArray -> detail
                .mapNotNull { (it as? JsonObject)?.get("msg") as? JsonPrimitive }
                .joinToString("; ") { it.content }
                .ifBlank { null }
            else -> null
        }
    } catch (e: Exception) {
        raw.take(200)
    }
}
