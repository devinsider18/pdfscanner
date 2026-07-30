package ua.com.devinsider.pdfscanner.ui.screens

import androidx.compose.ui.res.stringResource
import ua.com.devinsider.pdfscanner.R
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ua.com.devinsider.pdfscanner.utils.PdfConverter
import ua.com.devinsider.pdfscanner.utils.findActivity
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import ua.com.devinsider.pdfscanner.ui.viewmodels.MainViewModel
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider

@Composable
fun ToolsScreen(viewModel: MainViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val scope = rememberCoroutineScope()
    var isConverting by remember { mutableStateOf(false) }
    var conversionResultMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val documents by viewModel.documents.collectAsState()
    val pdfDocuments = documents.filter { it.type == DocumentType.PDF }
    
    val convertingToLongImageMsg = stringResource(R.string.converting_to_long_image)
    val convertingToImagesMsg = stringResource(R.string.converting_to_images)
    val mergingPdfsMsg = stringResource(R.string.merging_pdfs)
    val splittingPdfMsg = stringResource(R.string.splitting_pdf)
    val savedToPicturesMsg = stringResource(R.string.saved_to_pictures)
    val savedToDownloadsMsg = stringResource(R.string.saved_to_downloads)
    val failedToConvertMsg = stringResource(R.string.failed_to_convert)
    val failedPhotoToPdfMsg = stringResource(R.string.failed_photo_to_pdf)
    val scannerReturnedNullMsg = stringResource(R.string.scanner_returned_null)
    val failedProcessScannedPagesMsg = stringResource(R.string.failed_process_scanned_pages)
    val pageImageUriNullMsg = stringResource(R.string.page_image_uri_null)
    val noScannedPdfReturnedMsg = stringResource(R.string.no_scanned_pdf_returned)
    val cameraPermissionRequiredMsg = stringResource(R.string.camera_permission_required)
    val activityNotFoundMsg = stringResource(R.string.activity_not_found)
    val scannerIntentNullMsg = stringResource(R.string.scanner_intent_null)

    var showMergePicker by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    var shouldLaunchScanAfterPermission by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // Helper to copy URI to a temp file
    suspend fun uriToTempFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File(context.cacheDir, "temp_doc_${System.currentTimeMillis()}.pdf")
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            return@withContext tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    val pdfToLongImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isConverting = true
                snackbarHostState.showSnackbar(convertingToLongImageMsg)
                val path = uriToTempFile(it)
                if (path != null) {
                    val success = PdfConverter.convertPdfToLongImage(context, path)
                    conversionResultMessage = if (success) savedToPicturesMsg else failedToConvertMsg
                }
                isConverting = false
            }
        }
    }

    val pdfToImagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isConverting = true
                snackbarHostState.showSnackbar(convertingToImagesMsg)
                val path = uriToTempFile(it)
                if (path != null) {
                    val success = PdfConverter.convertPdfToImages(context, path) { _, _ -> }
                    conversionResultMessage = if (success) savedToPicturesMsg else failedToConvertMsg
                }
                isConverting = false
            }
        }
    }

    // Fallback Camera Launcher if MLKit Scanner is unavailable
    val fallbackCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val uri = tempCameraImageUri
            if (uri != null) {
                scope.launch {
                    val convertSuccess = PdfConverter.convertImageToPdf(context, uri)
                    if (convertSuccess) {
                        conversionResultMessage = savedToDownloadsMsg
                        viewModel.refreshDocuments()
                    } else {
                        viewModel.errorMessage.value = failedPhotoToPdfMsg
                    }
                }
            }
        }
    }

    val launchFallbackCameraTools = {
        try {
            val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            val photoUri = FileProvider.getUriForFile(context, "ua.com.devinsider.pdfscanner.fileprovider", photoFile)
            tempCameraImageUri = photoUri
            fallbackCameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            e.printStackTrace()
            viewModel.errorMessage.value = context.getString(R.string.error_opening_camera, e.localizedMessage ?: e.message ?: e.toString())
        }
    }

    // ML Kit Scanner Launcher
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val scanResult = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                if (scanResult == null) {
                    viewModel.errorMessage.value = scannerReturnedNullMsg
                    return@rememberLauncherForActivityResult
                }
                val pdfUri = scanResult.pdf?.uri
                val pages = scanResult.pages
                if (pdfUri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                                val fileName = "Scanned_${System.currentTimeMillis()}.pdf"
                                val tempFile = File(context.cacheDir, fileName)
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                                
                                PdfConverter.savePdfToMediaStore(context, tempFile, fileName)
                                tempFile.delete()
                            }
                            
                            withContext(Dispatchers.Main) {
                                conversionResultMessage = savedToDownloadsMsg
                                viewModel.refreshDocuments()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                viewModel.errorMessage.value = context.getString(R.string.error_saving_scanned_pdf, e.localizedMessage ?: e.message ?: e.toString())
                            }
                        }
                    }
                } else if (!pages.isNullOrEmpty()) {
                    val firstPageUri = pages.firstOrNull()?.imageUri
                    if (firstPageUri != null) {
                        scope.launch {
                            val success = PdfConverter.convertImageToPdf(context, firstPageUri)
                            if (success) {
                                conversionResultMessage = savedToDownloadsMsg
                                viewModel.refreshDocuments()
                            } else {
                                viewModel.errorMessage.value = failedProcessScannedPagesMsg
                            }
                        }
                    } else {
                        viewModel.errorMessage.value = pageImageUriNullMsg
                    }
                } else {
                    viewModel.errorMessage.value = noScannedPdfReturnedMsg
                }
            } catch (e: Exception) {
                e.printStackTrace()
                viewModel.errorMessage.value = context.getString(R.string.scanner_error, e.localizedMessage ?: e.message ?: e.toString())
            }
        }
    }

    // Camera Permission Launcher for Scanner
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            shouldLaunchScanAfterPermission = true
        } else {
            viewModel.errorMessage.value = cameraPermissionRequiredMsg
        }
    }

    val startScanningTools: () -> Unit = {
        val activity = context.findActivity()
        if (activity == null) {
            viewModel.errorMessage.value = activityNotFoundMsg
        } else {
            try {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(100)
                    .setResultFormats(
                        GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                        GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
                    )
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                val scanner = GmsDocumentScanning.getClient(options)
                scanner.getStartScanIntent(activity)
                    .addOnSuccessListener { intentSender ->
                        if (intentSender != null) {
                            scannerLauncher.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                            )
                        } else {
                            viewModel.errorMessage.value = scannerIntentNullMsg
                        }
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        val err = e.localizedMessage ?: e.message ?: e.toString()
                        viewModel.errorMessage.value = context.getString(R.string.scanner_error, err)
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                val err = e.localizedMessage ?: e.message ?: e.toString()
                viewModel.errorMessage.value = context.getString(R.string.scanner_error, err)
            }
        }
    }

    LaunchedEffect(shouldLaunchScanAfterPermission) {
        if (shouldLaunchScanAfterPermission) {
            shouldLaunchScanAfterPermission = false
            startScanningTools()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
        Text(stringResource(R.string.pdf_tools), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        ToolButton(icon = Icons.Default.CameraAlt, text = stringResource(R.string.scan_document), onClick = {
            startScanningTools()
        })
        ToolButton(icon = Icons.Default.Image, text = stringResource(R.string.pdf_to_long_image), onClick = { pdfToLongImageLauncher.launch("application/pdf") })
        ToolButton(icon = Icons.Default.PhotoLibrary, text = stringResource(R.string.pdf_to_image), onClick = { pdfToImagesLauncher.launch("application/pdf") })
        ToolButton(icon = Icons.AutoMirrored.Filled.CallMerge, text = stringResource(R.string.merge_pdfs), onClick = { showMergePicker = true })
        ToolButton(icon = Icons.AutoMirrored.Filled.CallSplit, text = stringResource(R.string.split_pdf), onClick = { showSplitPicker = true })

        if (showMergePicker) {
            DocumentPickerDialog(
                documents = pdfDocuments,
                isMultipleSelection = true,
                onDismiss = { showMergePicker = false },
                onConfirm = { selectedDocs ->
                    showMergePicker = false
                    val uris = selectedDocs.map { it.uriString.toUri() }
                    scope.launch {
                        isConverting = true
                        snackbarHostState.showSnackbar(mergingPdfsMsg)
                        val success = PdfConverter.mergePdfs(context, uris)
                        conversionResultMessage = if (success) savedToDownloadsMsg else failedToConvertMsg
                        isConverting = false
                    }
                }
            )
        }

        if (showSplitPicker) {
            DocumentPickerDialog(
                documents = pdfDocuments,
                isMultipleSelection = false,
                onDismiss = { showSplitPicker = false },
                onConfirm = { selectedDocs ->
                    showSplitPicker = false
                    selectedDocs.firstOrNull()?.let { doc ->
                        val uri = doc.uriString.toUri()
                        scope.launch {
                            isConverting = true
                            snackbarHostState.showSnackbar(splittingPdfMsg)
                            val success = PdfConverter.splitPdf(context, uri)
                            conversionResultMessage = if (success) savedToDownloadsMsg else failedToConvertMsg
                            isConverting = false
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.dark_mode), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = isDarkMode ?: false,
                onCheckedChange = { viewModel.toggleDarkMode(it) }
            )
        }

        if (showLanguagePicker) {
            AlertDialog(
                onDismissRequest = { showLanguagePicker = false },
                title = { Text(stringResource(R.string.select_language)) },
                text = {
                    Column {
                        val languages = listOf("en" to "English", "ru" to "Русский", "uk" to "Українська")
                        languages.forEach { (tag, name) ->
                            Text(
                                text = name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                                        )
                                        showLanguagePicker = false
                                    }
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguagePicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        OutlinedButton(
            onClick = { showLanguagePicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Language, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.select_language))
        }

        conversionResultMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { conversionResultMessage = null },
                title = { Text(stringResource(R.string.conversion_result)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { conversionResultMessage = null }) { Text(stringResource(R.string.ok)) }
                }
            )
        }
    }
    }
}

@Composable
fun ToolButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun DocumentPickerDialog(
    documents: List<DocumentItem>,
    isMultipleSelection: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<DocumentItem>) -> Unit
) {
    var selectedDocs by remember { mutableStateOf(emptySet<DocumentItem>()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isMultipleSelection) stringResource(R.string.select_pdfs_to_merge) else stringResource(R.string.select_pdf_to_split)) },
        text = {
            if (documents.isEmpty()) {
                Text(stringResource(R.string.no_pdf_documents))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(documents, key = { it.id }) { doc ->
                        val isSelected = selectedDocs.contains(doc)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isMultipleSelection) {
                                        selectedDocs = if (isSelected) selectedDocs - doc else selectedDocs + doc
                                    } else {
                                        selectedDocs = setOf(doc)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isMultipleSelection) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                RadioButton(selected = isSelected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(doc.name, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedDocs.toList()) },
                enabled = if (isMultipleSelection) selectedDocs.size > 1 else selectedDocs.size == 1
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}


