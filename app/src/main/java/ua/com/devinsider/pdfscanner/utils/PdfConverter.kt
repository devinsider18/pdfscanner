package ua.com.devinsider.pdfscanner.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument

object PdfConverter {
    private const val MAX_HEIGHT_FOR_LONG_IMAGE = 15000
    private const val MAX_PAGES_FOR_LONG_IMAGE = 5

    suspend fun convertPdfToImages(context: Context, filePath: String, onProgress: (Int, Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext false
            
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount
            
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                saveBitmapToMediaStore(context, bitmap, "${file.nameWithoutExtension}_page_${i+1}.png")
                bitmap.recycle()
                withContext(Dispatchers.Main) {
                    onProgress(i + 1, pageCount)
                }
            }
            renderer.close()
            fd.close()
            return@withContext true
        } catch (e: Throwable) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun convertPdfToLongImage(context: Context, filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext false
            
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                renderer.close()
                fd.close()
                return@withContext false
            }

            var totalHeight = 0
            var maxWidth = 0
            
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                totalHeight += page.height * 2
                if (page.width * 2 > maxWidth) maxWidth = page.width * 2
                page.close()
            }
            
            // Предотвращение OutOfMemoryError: если высота слишком большая, отказываемся клеить
            if (totalHeight > MAX_HEIGHT_FOR_LONG_IMAGE || pageCount > MAX_PAGES_FOR_LONG_IMAGE) {
                renderer.close()
                fd.close()
                return@withContext false
            }
            
            val longBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(longBitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            
            var currentY = 0f
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val pageBitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                pageBitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                canvas.drawBitmap(pageBitmap, 0f, currentY, null)
                currentY += pageBitmap.height
                pageBitmap.recycle()
            }
            
            saveBitmapToMediaStore(context, longBitmap, "${file.nameWithoutExtension}_long.png")
            longBitmap.recycle()
            
            renderer.close()
            fd.close()
            return@withContext true
        } catch (e: Throwable) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, displayName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDFScanner")
            }
        }
        
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        }
    }

    suspend fun mergePdfs(context: Context, uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        try {
            if (uris.isEmpty()) return@withContext false
            val merger = PDFMergerUtility()
            val tempOutputFile = File(context.cacheDir, "merged_temp_${System.currentTimeMillis()}.pdf")
            merger.destinationFileName = tempOutputFile.absolutePath

            for (uri in uris) {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    merger.addSource(inputStream)
                }
            }
            
            merger.mergeDocuments(null)
            
            savePdfToMediaStore(context, tempOutputFile, "Merged_Document_${System.currentTimeMillis()}.pdf")
            tempOutputFile.delete()
            return@withContext true
        } catch (e: Throwable) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun splitPdf(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext false
            val document = PDDocument.load(inputStream)
            
            if (document.numberOfPages <= 1) {
                document.close()
                return@withContext false // Cannot split a 1-page PDF
            }
            
            val splitter = Splitter()
            splitter.setSplitAtPage(document.numberOfPages / 2)
            
            val splitDocuments = splitter.split(document)
            
            if (splitDocuments.size >= 2) {
                val tempFile1 = File(context.cacheDir, "split_1_${System.currentTimeMillis()}.pdf")
                val tempFile2 = File(context.cacheDir, "split_2_${System.currentTimeMillis()}.pdf")
                
                splitDocuments[0].save(tempFile1)
                splitDocuments[1].save(tempFile2)
                
                savePdfToMediaStore(context, tempFile1, "Split_Part1_${System.currentTimeMillis()}.pdf")
                savePdfToMediaStore(context, tempFile2, "Split_Part2_${System.currentTimeMillis()}.pdf")
                
                tempFile1.delete()
                tempFile2.delete()
            }
            
            for (doc in splitDocuments) {
                doc.close()
            }
            document.close()
            
            return@withContext true
        } catch (e: Throwable) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun savePdfToMediaStore(context: Context, documentFile: File, displayName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDFScanner")
            }
        }
        
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        }
        
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                documentFile.inputStream().use { input ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}
