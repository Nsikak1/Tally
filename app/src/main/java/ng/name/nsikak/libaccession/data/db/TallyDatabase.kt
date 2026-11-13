package ng.name.nsikak.libaccession.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class], version = 2, exportSchema = false)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun books(): BookDao
}
