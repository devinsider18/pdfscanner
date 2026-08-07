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

sealed class ConversionResult {
    object Success : ConversionResult()
    data class Error(val reason: ErrorReason, val customMessage: String? = null) : ConversionResult()

    enum class ErrorReason {
        PASSWORD_PROTECTED,
        FILE_CORRUPTED,
        FILE_NOT_FOUND,
        MEMORY_LIMIT_EXCEEDED,
        EMPTY_DOCUMENT,
        UNKNOWN
    }
}

data class MemoryConfig(
    val maxPageWidth: Int,
    val pagesPerChunk: Int,
    val maxLongImageHeight: Int,
    val maxSingleBitmapBytes: Long
)

object PdfConverter {

    private fun getDeviceMemoryConfig(context: Context): MemoryConfig {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val freeHeap = (runtime.maxMemory() - usedHeap).coerceAtLeast(16 * 1024 * 1024)
        val safeBitmapBytes = freeHeap / 3

        val hardwareMaxTexture = try {
            val canvas = Canvas()
            val maxCanvas = minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
            if (maxCanvas in 1024..16384) maxCanvas else 8192
        } catch (_: Throwable) {
            8192
        }

        val isSystemLowMem = memoryInfo?.lowMemory == true
        val totalRamMb = memoryInfo?.totalMem?.div(1024 * 1024) ?: 2048

        val maxPageWidth: Int
        val pagesPerChunk: Int
        val maxLongImageHeight: Int

        when {
            isSystemLowMem || totalRamMb < 2500 || freeHeap < 96 * 1024 * 1024 -> {
                maxPageWidth = minOf(1200, hardwareMaxTexture)
                pagesPerChunk = 5
                maxLongImageHeight = minOf(6000, hardwareMaxTexture)
            }
            totalRamMb < 4500 || freeHeap < 256 * 1024 * 1024 -> {
                maxPageWidth = minOf(1800, hardwareMaxTexture)
                pagesPerChunk = 10
                maxLongImageHeight = minOf(10000, hardwareMaxTexture)
            }
            else -> {
                maxPageWidth = minOf(2400, hardwareMaxTexture)
                pagesPerChunk = 15
                maxLongImageHeight = minOf(14000, hardwareMaxTexture)
            }
        }

        return MemoryConfig(
            maxPageWidth = maxPageWidth,
            pagesPerChunk = pagesPerChunk,
            maxLongImageHeight = maxLongImageHeight,
            maxSingleBitmapBytes = safeBitmapBytes
        )
    }

    private fun classifyThrowable(e: Throwable): ConversionResult.Error {
        val msg = e.message?.lowercase() ?: ""
        return when {
            e is SecurityException || msg.contains("password") || msg.contains("encrypted") -> {
                ConversionResult.Error(ConversionResult.ErrorReason.PASSWORD_PROTECTED)
            }
            e is OutOfMemoryError || msg.contains("memory") || msg.contains("allocation") -> {
                ConversionResult.Error(ConversionResult.ErrorReason.MEMORY_LIMIT_EXCEEDED)
            }
            e is java.io.FileNotFoundException || msg.contains("file not found") || msg.contains("permission") -> {
                ConversionResult.Error(ConversionResult.ErrorReason.FILE_NOT_FOUND)
            }
            msg.contains("corrupt") || msg.contains("header") || msg.contains("invalid") -> {
                ConversionResult.Error(ConversionResult.ErrorReason.FILE_CORRUPTED)
            }
            else -> {
                ConversionResult.Error(ConversionResult.ErrorReason.UNKNOWN)
            }
        }
    }

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

    private fun cleanBaseName(filePath: String): String {
        return try {
            val file = File(filePath)
            val name = file.nameWithoutExtension
            if (name.startsWith("temp_doc_")) "Document" else name
        } catch (_: Exception) {
            "Document"
        }
    }

    suspend fun convertPdfToImagesWithResult(
        context: Context,
        filePath: String,
        onProgress: (Int, Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        val memConfig = getDeviceMemoryConfig(context)
        try {
            val fd = openFileDescriptor(context, filePath)
                ?: return@withContext ConversionResult.Error(ConversionResult.ErrorReason.FILE_NOT_FOUND)
            
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Throwable) {
                fd.close()
                AnalyticsHelper.recordException(e)
                return@withContext classifyThrowable(e)
            }

            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                renderer.close()
                fd.close()
                return@withContext ConversionResult.Error(ConversionResult.ErrorReason.EMPTY_DOCUMENT)
            }

            val baseName = cleanBaseName(filePath)
            var successCount = 0

            for (i in 0 until pageCount) {
                try {
                    val page = renderer.openPage(i)
                    var scale = minOf(2.0f, memConfig.maxPageWidth.toFloat() / page.width.toFloat()).coerceAtLeast(0.3f)
                    var targetW = (page.width * scale).toInt().coerceAtLeast(1)
                    var targetH = (page.height * scale).toInt().coerceAtLeast(1)

                    val reqBytes = targetW.toLong() * targetH.toLong() * 4L
                    if (reqBytes > memConfig.maxSingleBitmapBytes && reqBytes > 0) {
                        val capScale = kotlin.math.sqrt(memConfig.maxSingleBitmapBytes.toDouble() / reqBytes.toDouble()).toFloat()
                        targetW = (targetW * capScale).toInt().coerceAtLeast(1)
                        targetH = (targetH * capScale).toInt().coerceAtLeast(1)
                    }

                    val bitmap = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    saveBitmapToMediaStore(context, bitmap, "${baseName}_page_${i + 1}.png")
                    bitmap.recycle()
                    successCount++
                } catch (e: Throwable) {
                    e.printStackTrace()
                    AnalyticsHelper.recordException(e)
                }

                withContext(Dispatchers.Main) {
                    onProgress(i + 1, pageCount)
                }
            }

            renderer.close()
            fd.close()

            if (successCount > 0) {
                AnalyticsHelper.logEvent(context, "pdf_to_images_success")
                ConversionResult.Success
            } else {
                ConversionResult.Error(ConversionResult.ErrorReason.FILE_CORRUPTED)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            AnalyticsHelper.recordException(e)
            classifyThrowable(e)
        }
    }

    suspend fun convertPdfToLongImageWithResult(
        context: Context,
        filePath: String
    ): ConversionResult = withContext(Dispatchers.IO) {
        val memConfig = getDeviceMemoryConfig(context)
        try {
            val fd = openFileDescriptor(context, filePath)
                ?: return@withContext ConversionResult.Error(ConversionResult.ErrorReason.FILE_NOT_FOUND)

            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Throwable) {
                fd.close()
                AnalyticsHelper.recordException(e)
                return@withContext classifyThrowable(e)
            }

            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                renderer.close()
                fd.close()
                return@withContext ConversionResult.Error(ConversionResult.ErrorReason.EMPTY_DOCUMENT)
            }

            val baseName = cleanBaseName(filePath)
            val totalParts = (pageCount + memConfig.pagesPerChunk - 1) / memConfig.pagesPerChunk
            var savedPartsCount = 0

            for (partIndex in 0 until totalParts) {
                val startPage = partIndex * memConfig.pagesPerChunk
                val endPage = minOf(startPage + memConfig.pagesPerChunk, pageCount)

                var totalHeight = 0
                var maxWidth = 0

                for (i in startPage until endPage) {
                    val page = renderer.openPage(i)
                    val scale = minOf(2.0f, memConfig.maxPageWidth.toFloat() / page.width.toFloat()).coerceAtLeast(0.4f)
                    val w = (page.width * scale).toInt()
                    val h = (page.height * scale).toInt()
                    totalHeight += h
                    if (w > maxWidth) maxWidth = w
                    page.close()
                }

                if (maxWidth <= 0 || totalHeight <= 0) continue

                val heightScale = if (totalHeight > memConfig.maxLongImageHeight) {
                    memConfig.maxLongImageHeight.toFloat() / totalHeight.toFloat()
                } else 1.0f

                var finalWidth = (maxWidth * heightScale).toInt().coerceAtLeast(1)
                var finalHeight = (totalHeight * heightScale).toInt().coerceAtLeast(1)

                val reqBytes = finalWidth.toLong() * finalHeight.toLong() * 4L
                var capScale = 1.0f
                if (reqBytes > memConfig.maxSingleBitmapBytes && reqBytes > 0) {
                    capScale = kotlin.math.sqrt(memConfig.maxSingleBitmapBytes.toDouble() / reqBytes.toDouble()).toFloat()
                    finalWidth = (finalWidth * capScale).toInt().coerceAtLeast(1)
                    finalHeight = (finalHeight * capScale).toInt().coerceAtLeast(1)
                }

                try {
                    val longBitmap = createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(longBitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)

                    var currentY = 0f
                    for (i in startPage until endPage) {
                        val page = renderer.openPage(i)
                        val scale = minOf(2.0f, memConfig.maxPageWidth.toFloat() / page.width.toFloat()).coerceAtLeast(0.4f)
                        val pageW = (page.width * scale * heightScale * capScale).toInt().coerceAtLeast(1)
                        val pageH = (page.height * scale * heightScale * capScale).toInt().coerceAtLeast(1)

                        val pageBitmap = createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
                        pageBitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        canvas.drawBitmap(pageBitmap, 0f, currentY, null)
                        currentY += pageH
                        pageBitmap.recycle()
                    }

                    val fileName = if (totalParts > 1) {
                        "${baseName}_long_part${partIndex + 1}.png"
                    } else {
                        "${baseName}_long.png"
                    }

                    saveBitmapToMediaStore(context, longBitmap, fileName)
                    longBitmap.recycle()
                    savedPartsCount++
                } catch (e: Throwable) {
                    e.printStackTrace()
                    AnalyticsHelper.recordException(e)
                }
            }

            renderer.close()
            fd.close()

            if (savedPartsCount > 0) {
                AnalyticsHelper.logEvent(context, "pdf_to_long_image_success")
                ConversionResult.Success
            } else {
                ConversionResult.Error(ConversionResult.ErrorReason.FILE_CORRUPTED)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            AnalyticsHelper.recordException(e)
            classifyThrowable(e)
        }
    }

    suspend fun convertPdfToImages(context: Context, filePath: String, onProgress: (Int, Int) -> Unit): Boolean {
        return convertPdfToImagesWithResult(context, filePath, onProgress) is ConversionResult.Success
    }

    suspend fun convertPdfToLongImage(context: Context, filePath: String): Boolean {
        return convertPdfToLongImageWithResult(context, filePath) is ConversionResult.Success
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
            AnalyticsHelper.logEvent(context, "image_to_pdf_success")
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
