package ua.com.devinsider.pdfscanner.utils

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns

fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

fun getFileNameFromUri(context: Context, uri: Uri): String {
    var fileName: String? = null
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        fileName = cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    if (fileName.isNullOrBlank()) {
        fileName = uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) path.substring(cut + 1) else path
        }
    }
    if (fileName.isNullOrBlank()) {
        fileName = "Imported_${System.currentTimeMillis()}.pdf"
    } else if (!fileName.lowercase().endsWith(".pdf")) {
        fileName = "$fileName.pdf"
    }
    return fileName
}

fun Context.getLocalizedContext(): Context {
    val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    if (!locales.isEmpty) {
        val config = android.content.res.Configuration(this.resources.configuration)
        config.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
        return this.createConfigurationContext(config)
    }
    return this
}
