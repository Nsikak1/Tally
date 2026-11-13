package ng.name.nsikak.libaccession.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE isbn = :isbn LIMIT 1")
    suspend fun find(isbn: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("DELETE FROM books WHERE fetched_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
