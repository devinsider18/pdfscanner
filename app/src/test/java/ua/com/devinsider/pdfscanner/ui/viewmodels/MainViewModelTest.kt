package ua.com.devinsider.pdfscanner.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import ua.com.devinsider.pdfscanner.data.model.SortOption
import java.lang.reflect.Method

class MainViewModelTest {

    @Test
    fun testFilterAndSortDocuments() {
        val docs = listOf(
            createDoc("doc3", 3000),
            createDoc("doc1", 1000),
            createDoc("doc2", 2000)
        )

        // Access the private method via reflection for testing
        val method: Method = MainViewModel::class.java.getDeclaredMethod(
            "filterAndSortDocuments",
            List::class.java,
            String::class.java,
            SortOption::class.java
        )
        method.isAccessible = true

        // Test filtering
        var result = (method.invoke(null, docs, "doc1", SortOption.DATE_DESC) as? List<*>)?.filterIsInstance<DocumentItem>().orEmpty()
        assertEquals(1, result.size)
        assertEquals("doc1", result[0].name)

        // Test sorting by Date Descending
        result = (method.invoke(null, docs, "", SortOption.DATE_DESC) as? List<*>)?.filterIsInstance<DocumentItem>().orEmpty()
        assertEquals(3, result.size)
        assertEquals("doc3", result[0].name)
        assertEquals("doc2", result[1].name)
        assertEquals("doc1", result[2].name)

        // Test sorting by Name Ascending
        result = (method.invoke(null, docs, "", SortOption.NAME_ASC) as? List<*>)?.filterIsInstance<DocumentItem>().orEmpty()
        assertEquals("doc1", result[0].name)
        assertEquals("doc2", result[1].name)
        assertEquals("doc3", result[2].name)
    }

    private fun createDoc(name: String, dateModified: Long): DocumentItem {
        return DocumentItem(
            id = name,
            name = name,
            path = "/path/$name",
            uriString = "uri://$name",
            type = DocumentType.PDF,
            sizeBytes = 1024,
            dateCreatedMillis = dateModified,
            dateModifiedMillis = dateModified
        )
    }
}
