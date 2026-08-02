package ua.com.devinsider.pdfscanner.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument

object PdfConverter {
    private const val MAX_HEIGHT_FOR_LONG_IMAGE = 15000
    private const val MAX_PAGES_FOR_LONG_IMAGE = 10

    private fun openFileDescriptor(context: Context, pathOrUri: String): ParcelFileDescriptor? {
        return try {
            if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                context.contentResolver.openFileDescriptor(pathOrUri.toUri(), "r")
            } else {
                val file = File(pathOrUri)
                if (file.exists()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    context.contentResolver.openFileDescriptor(pathOrUri.toUri(), "r")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun convertPdfToImages(context: Context, filePath: String, onProgress: (Int, Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val fd = openFileDescriptor(context, filePath) ?: return@withContext false
            val renderer = PdfRenderer(fd)
            val pageCount = renderer.pageCount
            
            val baseName = try { File(filePath).nameWithoutExtension } catch (_: Exception) { "Document" }
            
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val bitmap = createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                saveBitmapToMediaStore(context, bitmap, "${baseName}_page_${i+1}.png")
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
            val fd = openFileDescriptor(context, filePath) ?: return@withContext false
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
            
            if (totalHeight > MAX_HEIGHT_FOR_LONG_IMAGE || pageCount > MAX_PAGES_FOR_LONG_IMAGE) {
                renderer.close()
                fd.close()
                return@withContext false
            }
            
            val longBitmap = createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(longBitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            
            var currentY = 0f
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val pageBitmap = createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                pageBitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                
                canvas.drawBitmap(pageBitmap, 0f, currentY, null)
                currentY += pageBitmap.height
                pageBitmap.recycle()
            }
            
            val baseName = try { File(filePath).nameWithoutExtension } catch (_: Exception) { "Document" }
            saveBitmapToMediaStore(context, longBitmap, "${baseName}_long.png")
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
        try {
            val resolver = context.contentResolver
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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
            } else {
                val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PDFScanner")
                if (!picturesDir.exists()) {
                    picturesDir.mkdirs()
                }
                val outFile = File(picturesDir, displayName)
                FileOutputStream(outFile).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                return@withContext false
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

    suspend fun convertImageToPdf(context: Context, imageUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: return@withContext false
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return@withContext false

            val document = PDDocument()
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                com.tom_roush.pdfbox.pdmodel.common.PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat())
            )
            document.addPage(page)

            val pdImage = try {
                com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, bitmap)
            } catch (_: Exception) {
                com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, bitmap)
            }
            val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
            contentStream.drawImage(pdImage, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            contentStream.close()

            val fileName = "Scanned_${System.currentTimeMillis()}.pdf"
            val tempFile = File(context.cacheDir, fileName)
            document.save(tempFile)
            document.close()
            bitmap.recycle()

            savePdfToMediaStore(context, tempFile, fileName)
            tempFile.delete()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun savePdfToMediaStore(context: Context, documentFile: File, displayName: String) {
        try {
            val resolver = context.contentResolver
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PDFScanner")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        documentFile.inputStream().use { input ->
                            input.copyTo(outputStream)
                        }
                    }
                }
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PDFScanner")
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val outFile = File(downloadsDir, displayName)
                documentFile.copyTo(outFile, overwrite = true)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                }
                resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
