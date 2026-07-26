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

object PdfConverter {

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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun saveBitmapToMediaStore(context: Context, bitmap: Bitmap, displayName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDFScanner")
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
            val newPdf = PdfDocument()
            
            for (uri in uris) {
                val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: continue
                val renderer = PdfRenderer(fd)
                val pageCount = renderer.pageCount
                
                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    val width = page.width * 2
                    val height = page.height * 2
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    
                    val pageInfo = PdfDocument.PageInfo.Builder(width, height, newPdf.pages.size + 1).create()
                    val pdfPage = newPdf.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    newPdf.finishPage(pdfPage)
                    bitmap.recycle()
                }
                renderer.close()
                fd.close()
            }
            
            savePdfToMediaStore(context, newPdf, "Merged_Document_${System.currentTimeMillis()}.pdf")
            newPdf.close()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun splitPdf(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext false
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount
            
            if (pageCount <= 1) {
                renderer.close()
                fd.close()
                return@withContext false // Cannot split a 1-page PDF
            }
            
            val half = pageCount / 2
            
            val pdf1 = PdfDocument()
            val pdf2 = PdfDocument()
            
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                val targetPdf = if (i < half) pdf1 else pdf2
                val pageInfo = PdfDocument.PageInfo.Builder(width, height, targetPdf.pages.size + 1).create()
                val pdfPage = targetPdf.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                targetPdf.finishPage(pdfPage)
                bitmap.recycle()
            }
            
            savePdfToMediaStore(context, pdf1, "Split_Part1_${System.currentTimeMillis()}.pdf")
            savePdfToMediaStore(context, pdf2, "Split_Part2_${System.currentTimeMillis()}.pdf")
            
            pdf1.close()
            pdf2.close()
            renderer.close()
            fd.close()
            
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun savePdfToMediaStore(context: Context, document: PdfDocument, displayName: String) {
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
            // For older API fallback, though minSdk is 31
            resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        }
        
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                document.writeTo(outputStream)
            }
        }
    }
}
