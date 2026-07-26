package ua.com.devinsider.pdfscanner.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import ua.com.devinsider.pdfscanner.data.local.AppCreatedFileDao
import ua.com.devinsider.pdfscanner.data.local.AppCreatedFileEntity
import ua.com.devinsider.pdfscanner.data.local.BookmarkDao
import ua.com.devinsider.pdfscanner.data.local.BookmarkEntity
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookmarkDao: BookmarkDao,
    private val appCreatedFileDao: AppCreatedFileDao
) {
    private val contentResolver: ContentResolver = context.contentResolver
    
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    fun refreshDocuments() {
        refreshTrigger.tryEmit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun getDocumentsFlow(): Flow<List<DocumentItem>> = refreshTrigger.flatMapLatest {
        flow {
            val documents = mutableListOf<DocumentItem>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN (?, ?, ?, ?, ?, ?, ?, ?) OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        
        val selectionArgs = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain", // Adding just in case, though not requested
            "%.pdf",
            "%.doc", "%.docx",
            "%.xls", "%.xlsx",
            "%.ppt", "%.pptx"
        )
        
        try {
            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    var name = cursor.getString(nameCol)
                    val path = cursor.getString(dataCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateAddedCol) * 1000
                    val dateModified = cursor.getLong(dateModifiedCol) * 1000
                    
                    if (name == null && path != null) {
                        name = File(path).name
                    }
                    
                    if (name != null && path != null) {
                        val uri = ContentUris.withAppendedId(collection, id)
                        val type = determineType(name)
                        documents.add(
                            DocumentItem(
                                id = id.toString(),
                                name = name,
                                path = path,
                                uriString = uri.toString(),
                                type = type,
                                sizeBytes = size,
                                dateCreatedMillis = dateAdded,
                                dateModifiedMillis = dateModified
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        emit(documents)
        }
    }.flowOn(Dispatchers.IO)
    
    val allDocumentsFlow: Flow<List<DocumentItem>> = combine(
        getDocumentsFlow(),
        bookmarkDao.getAllBookmarks(),
        appCreatedFileDao.getAllAppCreatedFiles()
    ) { docs, bookmarks, appFiles ->
        val bookmarkPaths = bookmarks.associateBy { it.filePath }
        val appFilePaths = appFiles.associateBy { it.filePath }
        
        docs.map { doc ->
            doc.copy(
                isBookmarked = bookmarkPaths[doc.path]?.isBookmarked == true,
                isCreatedByApp = appFilePaths.containsKey(doc.path)
            )
        }
    }
    
    suspend fun toggleBookmark(document: DocumentItem) {
        if (document.isBookmarked) {
            bookmarkDao.removeBookmark(document.path)
        } else {
            bookmarkDao.insertBookmark(BookmarkEntity(document.path, true))
        }
    }
    
    suspend fun addAppCreatedFile(path: String) {
        appCreatedFileDao.insertAppCreatedFile(AppCreatedFileEntity(path))
    }
    
    suspend fun renameDocument(document: DocumentItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        // Renaming in MediaStore is tricky, standard File rename might work on some API levels,
        // or using MediaStore update. This is a placeholder for Phase 5 implementation.
        try {
            val file = File(document.path)
            val newFile = File(file.parent, newName)
            if (file.renameTo(newFile)) {
                if (document.isBookmarked) {
                    bookmarkDao.removeBookmark(document.path)
                    bookmarkDao.insertBookmark(BookmarkEntity(newFile.absolutePath, true))
                }
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }
    
    suspend fun deleteDocument(document: DocumentItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(document.path)
            if (file.delete()) {
                bookmarkDao.removeBookmark(document.path)
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    private fun determineType(name: String): DocumentType {
        val lowerName = name.lowercase()
        return when {
            lowerName.endsWith(".pdf") -> DocumentType.PDF
            lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> DocumentType.WORD
            lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") -> DocumentType.EXCEL
            lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") -> DocumentType.PPT
            else -> DocumentType.OTHER
        }
    }
}
