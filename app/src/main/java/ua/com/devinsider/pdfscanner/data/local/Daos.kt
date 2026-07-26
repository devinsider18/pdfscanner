package ua.com.devinsider.pdfscanner.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE filePath = :filePath")
    suspend fun removeBookmark(filePath: String)
}

@Dao
interface AppCreatedFileDao {
    @Query("SELECT * FROM app_created_files")
    fun getAllAppCreatedFiles(): Flow<List<AppCreatedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppCreatedFile(file: AppCreatedFileEntity)
}
