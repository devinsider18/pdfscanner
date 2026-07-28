package ua.com.devinsider.pdfscanner.data.model

enum class DocumentType { PDF }

enum class SortOption { DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC }

data class DocumentItem(
    val id: String,
    val name: String,
    val path: String,
    val uriString: String,
    val type: DocumentType,
    val sizeBytes: Long,
    val dateCreatedMillis: Long,
    val dateModifiedMillis: Long,
    val isBookmarked: Boolean = false,
    val isCreatedByApp: Boolean = false
)
