package ua.com.devinsider.pdfscanner.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
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
            emit(fetchDocumentsFromMediaStore())
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchDocumentsFromMediaStore(): List<DocumentItem> {
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
        
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf'"
        val selectionArgs = arrayOf("application/pdf")
        
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
            // Log exception appropriately instead of printStackTrace
            // For now, silently return whatever we managed to read
        }
        return documents
    }
    
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
        try {
            val uri = document.uriString.toUri()
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            val updated = contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }
    
    suspend fun deleteDocument(document: DocumentItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = document.uriString.toUri()
            val deleted = contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                bookmarkDao.removeBookmark(document.path)
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    private fun determineType(name: String): DocumentType {
        return DocumentType.PDF
    }

    companion object {
        private const val MIME_TYPE_PDF = "application/pdf"
    }
}
