package ua.com.devinsider.pdfscanner.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.SortOption
import ua.com.devinsider.pdfscanner.data.repository.AppPreferencesRepository
import ua.com.devinsider.pdfscanner.data.repository.DocumentRepository
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val appPreferencesRepository: AppPreferencesRepository
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow(SortOption.DATE_DESC)
    
    val documents: StateFlow<List<DocumentItem>> = combine(
        documentRepository.allDocumentsFlow,
        searchQuery,
        sortOption
    ) { docs, query, sort ->
        var filtered = docs
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        }
        when (sort) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.dateModifiedMillis }
            SortOption.DATE_ASC -> filtered.sortedBy { it.dateModifiedMillis }
            SortOption.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val isDarkMode = appPreferencesRepository.isDarkMode.stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun refreshDocuments() {
        documentRepository.refreshDocuments()
    }

    fun toggleBookmark(document: DocumentItem) {
        viewModelScope.launch {
            documentRepository.toggleBookmark(document)
        }
    }
    
    fun addAppCreatedFile(path: String) {
        viewModelScope.launch {
            documentRepository.addAppCreatedFile(path)
        }
    }
    
    fun deleteDocument(document: DocumentItem) {
        viewModelScope.launch {
            documentRepository.deleteDocument(document)
        }
    }
    
    fun renameDocument(document: DocumentItem, newName: String) {
        viewModelScope.launch {
            documentRepository.renameDocument(document, newName)
        }
    }
    
    fun toggleDarkMode(isDark: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.setDarkMode(isDark)
        }
    }
}
