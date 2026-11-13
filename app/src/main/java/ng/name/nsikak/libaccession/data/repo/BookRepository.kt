package ng.name.nsikak.libaccession.data.repo

import kotlinx.coroutines.CancellationException
import ng.name.nsikak.libaccession.data.api.ApiException
import ng.name.nsikak.libaccession.data.api.ApiProvider
import ng.name.nsikak.libaccession.data.api.Book
import ng.name.nsikak.libaccession.data.api.Copy
import ng.name.nsikak.libaccession.data.api.apiCall
import ng.name.nsikak.libaccession.data.api.describeError
import ng.name.nsikak.libaccession.data.db.BookDao
import ng.name.nsikak.libaccession.data.db.BookEntity

const val SOURCE_SERVER = "Library server"

/** The outcome of looking an ISBN up. */
sealed interface Lookup {
    /**
     * [source] says which catalog described the book. [offline] = the library server could not be reached,
     * so [copies] (what the library already owns) is unknown.
     */
    data class Found(
        val book: Book,
        val copies: List<Copy>,
        val offline: Boolean = false,
        val source: String = SOURCE_SERVER,
    ) : Lookup

    /** Nobody knows this ISBN. [tried] = catalogs asked online; [unreachable] = those that gave no answer. */
    data class NotInCatalog(
        val isbn: String,
        val copies: List<Copy>,
        val tried: List<String> = emptyList(),
        val unreachable: List<String> = emptyList(),
    ) : Lookup

    data class Failed(val isbn: String, val message: String) : Lookup
}

/**
 * Where a book's details come from, in this order:
 *  1. the library server's own catalog (GET /scan: the book AND the library's own copies in one call),
 *  2. the copy saved on this device (Room),
 *  3. the library server's online lookup (GET /lookup: the SERVER asks Google Books, Library of Congress ...;
 *     the phone never talks to those services).
 * Anything found is saved on the device, with where it came from.
 */
class BookRepository(
    private val api: ApiProvider,
    private val dao: BookDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun lookup(isbn13: String): Lookup {
        var serverError: Exception? = null
        var copies: List<Copy> = emptyList()
        var serverAnswered = false

        try {
            val response = apiCall { api.get().scan(isbn13) }
            serverAnswered = true
            copies = response.copies
            val book = response.book
            if (book != null) {
                save(book, SOURCE_SERVER)
                return Lookup.Found(book, copies)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverError = e
        }

        // Not in the server's catalog (or the server can't be reached): the saved copy first.
        val saved = try {
            dao.find(isbn13)
        } catch (e: Exception) {
            null
        }
        if (saved != null) {
            return Lookup.Found(saved.toBook(), copies, offline = !serverAnswered, source = saved.source)
        }
        if (!serverAnswered) {
            return Lookup.Failed(isbn13, describeError(serverError ?: IllegalStateException("No answer from the server.")))
        }

        // The server answered "not in my catalog": let it ask the online catalogs.
        return try {
            val online = apiCall { api.get().lookup(isbn13) }
            val book = online.book
            if (book != null) {
                val source = online.source ?: "online catalog"
                save(book, source)
                Lookup.Found(book, copies, source = source)
            } else {
                Lookup.NotInCatalog(isbn13, copies, online.tried, online.unreachable)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 404 = this server has no online lookup installed yet; other codes are shown as they are.
            val why = if (e.code == 404) "online lookup (not installed on the server)" else "online lookup (${e.detail})"
            Lookup.NotInCatalog(isbn13, copies, emptyList(), listOf(why))
        } catch (e: Exception) {
            Lookup.NotInCatalog(isbn13, copies, emptyList(), listOf("online lookup (${describeError(e)})"))
        }
    }

    private suspend fun save(book: Book, source: String) {
        try {
            dao.upsert(book.toEntity(clock(), source))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the cache is a convenience; a failed save must never break a lookup
        }
    }

    /** Forget books saved more than [days] days ago. */
    suspend fun prune(days: Int = 365) {
        dao.deleteOlderThan(clock() - days * 24L * 60 * 60 * 1000)
    }
}

private fun Book.toEntity(now: Long, source: String) = BookEntity(
    isbn = isbn, title = title, author = author, work = work, editions = editions,
    year = year, lang = lang, dewey = dewey, coverUrl = coverUrl, fetchedAt = now, source = source,
)

private fun BookEntity.toBook() = Book(
    isbn = isbn, title = title, author = author, work = work, editions = editions,
    year = year, lang = lang, dewey = dewey, coverUrl = coverUrl,
)
