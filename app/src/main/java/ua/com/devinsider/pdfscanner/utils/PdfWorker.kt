package ua.com.devinsider.pdfscanner.utils

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import ua.com.devinsider.pdfscanner.R
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val action = inputData.getString("action")
        
        return try {
            val success = when (action) {
                "merge" -> {
                    val uris = inputData.getStringArray("uris")?.map { it.toUri() } ?: return Result.failure()
                    PdfConverter.mergePdfs(applicationContext, uris)
                }
                "split" -> {
                    val uri = inputData.getString("uri")?.toUri() ?: return Result.failure()
                    PdfConverter.splitPdf(applicationContext, uri)
                }
                "convert_images" -> {
                    val path = inputData.getString("path") ?: return Result.failure()
                    PdfConverter.convertPdfToImages(applicationContext, path) { _, _ -> }
                }
                "convert_long_image" -> {
                    val path = inputData.getString("path") ?: return Result.failure()
                    PdfConverter.convertPdfToLongImage(applicationContext, path)
                }
                else -> false
            }
            
            withContext(Dispatchers.Main) {
                val localizedContext = applicationContext.getLocalizedContext()
                val msg = if (success) localizedContext.getString(R.string.task_success) else localizedContext.getString(R.string.task_error)
                showNotification(applicationContext, localizedContext.getString(R.string.app_name), msg)
            }
            
            if (success) Result.success() else Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            AnalyticsHelper.recordException(e)
            withContext(Dispatchers.Main) {
                val localizedContext = applicationContext.getLocalizedContext()
                showNotification(applicationContext, localizedContext.getString(R.string.app_name), localizedContext.getString(R.string.task_critical_error))
            }
            Result.failure()
        }
    }

    private fun showNotification(context: Context, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "pdf_processing_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PDF Processing",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
