package ng.name.nsikak.libaccession.data.lookup

import ng.name.nsikak.libaccession.data.api.Book

sealed interface ProviderResult {
    data class Found(val book: Book) : ProviderResult
    data object NotFound : ProviderResult

    /** No answer (no internet, quota used up, server error): the next provider is tried. */
    data class Unavailable(val reason: String) : ProviderResult
}

/** An online catalog that can describe a book from its ISBN-13. */
interface BookProvider {
    val name: String
    suspend fun find(isbn13: String): ProviderResult
}

/** What the whole chain found. [book] is null if nobody knew the ISBN. */
data class FinderResult(
    val book: Book?,
    val source: String?,
    val tried: List<String>,
    val unreachable: List<String>,
)

/** Asks each provider in order and stops at the first that knows the book. */
class OnlineBookFinder(private val providers: List<BookProvider>) {
    suspend fun find(isbn13: String): FinderResult {
        val tried = ArrayList<String>()
        val unreachable = ArrayList<String>()
        for (provider in providers) {
            tried += provider.name
            when (val result = provider.find(isbn13)) {
                is ProviderResult.Found -> return FinderResult(result.book, provider.name, tried, unreachable)
                ProviderResult.NotFound -> Unit
                is ProviderResult.Unavailable -> unreachable += provider.name
            }
        }
        return FinderResult(null, null, tried, unreachable)
    }
}
