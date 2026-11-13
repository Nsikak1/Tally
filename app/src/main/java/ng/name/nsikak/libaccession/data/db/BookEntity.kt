package ng.name.nsikak.libaccession.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A catalog book remembered on the device, keyed by its ISBN-13. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val isbn: String,
    val title: String?,
    val author: String?,
    val work: Long?,
    val editions: Int,
    val year: Int?,
    val lang: String?,
    val dewey: String?,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    /** Which catalog described the book: "Library server", "Google Books", ... */
    val source: String = "Library server",
)
