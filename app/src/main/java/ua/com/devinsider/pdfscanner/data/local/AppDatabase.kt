package ua.com.devinsider.pdfscanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BookmarkEntity::class, AppCreatedFileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun appCreatedFileDao(): AppCreatedFileDao
}
