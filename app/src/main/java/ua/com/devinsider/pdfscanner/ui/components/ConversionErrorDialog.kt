package ua.com.devinsider.pdfscanner.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.com.devinsider.pdfscanner.R
import ua.com.devinsider.pdfscanner.utils.ConversionResult

@Composable
fun ConversionErrorDialog(
    error: ConversionResult.Error,
    onDismiss: () -> Unit
) {
    val messageRes = when (error.reason) {
        ConversionResult.ErrorReason.PASSWORD_PROTECTED -> R.string.error_pdf_password_protected
        ConversionResult.ErrorReason.FILE_CORRUPTED -> R.string.error_pdf_file_corrupted
        ConversionResult.ErrorReason.FILE_NOT_FOUND -> R.string.error_pdf_file_not_found
        ConversionResult.ErrorReason.MEMORY_LIMIT_EXCEEDED -> R.string.error_pdf_memory_limit
        ConversionResult.ErrorReason.EMPTY_DOCUMENT -> R.string.error_pdf_empty_document
        ConversionResult.ErrorReason.UNKNOWN -> R.string.error_pdf_unknown
    }

    val displayMessage = error.customMessage ?: stringResource(messageRes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.error_conversion_dialog_title))
        },
        text = {
            Text(text = displayMessage)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_ok))
            }
        }
    )
}
