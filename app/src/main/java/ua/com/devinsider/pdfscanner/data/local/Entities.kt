package ua.com.devinsider.pdfscanner.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val filePath: String,
    val isBookmarked: Boolean,
    val bookmarkedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_created_files")
data class AppCreatedFileEntity(
    @PrimaryKey val filePath: String,
    val createdAt: Long = System.currentTimeMillis()
)
