package ua.com.devinsider.pdfscanner.ui.screens
import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ua.com.devinsider.pdfscanner.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import android.net.Uri
import java.io.File
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import ua.com.devinsider.pdfscanner.data.model.SortOption
import ua.com.devinsider.pdfscanner.ui.components.DocumentCard
import ua.com.devinsider.pdfscanner.ui.viewmodels.MainViewModel
import ua.com.devinsider.pdfscanner.utils.PdfConverter
import ua.com.devinsider.pdfscanner.utils.findActivity
import ua.com.devinsider.pdfscanner.utils.getFileNameFromUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    viewModel: MainViewModel = hiltViewModel(),
    filter: (DocumentItem) -> Boolean,
    onNavigateToViewer: (String) -> Unit
) {
    val documents by viewModel.documents.collectAsState()
    val filteredDocs = documents.filter(filter)
    val searchQuery by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedToDownloadsMsg = stringResource(R.string.saved_to_downloads)
    val shareTitleMsg = stringResource(R.string.share)
    val taskMergeBgMsg = stringResource(R.string.task_merge_bg)
    val taskConvertBgMsg = stringResource(R.string.task_convert_bg)
    val taskLongImageBgMsg = stringResource(R.string.task_long_image_bg)
    val taskSplitBgMsg = stringResource(R.string.task_split_bg)
    val storagePermissionRequiredMsg = stringResource(R.string.storage_permission_required)
    val pdfImportedMsg = stringResource(R.string.pdf_imported)
    val failedPhotoToPdfMsg = stringResource(R.string.failed_photo_to_pdf)
    val failedProcessScannedPagesMsg = stringResource(R.string.failed_process_scanned_pages)
    val pageImageUriNullMsg = stringResource(R.string.page_image_uri_null)
    val cameraPermissionRequiredMsg = stringResource(R.string.camera_permission_required)
    val activityNotFoundMsg = stringResource(R.string.activity_not_found)
    val noAppToOpenFileMsg = stringResource(R.string.no_app_to_open_file)
    val errorImportingPdfFormat = stringResource(R.string.error_importing_pdf)
    val errorOpeningCameraFormat = stringResource(R.string.error_opening_camera)
    val errorSavingScannedPdfFormat = stringResource(R.string.error_saving_scanned_pdf)
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedDocs = remember { mutableStateListOf<DocumentItem>() }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<DocumentItem?>(null) }
    var documentToRename by remember { mutableStateOf<DocumentItem?>(null) }
    var documentForInfo by remember { mutableStateOf<DocumentItem?>(null) }
    var newFileName by remember { mutableStateOf("") }
    var conversionResultMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Storage Permission Launcher for API <= 28
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            viewModel.errorMessage.value = storagePermissionRequiredMsg
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
    // Document Import Launcher
    val importPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val originalName = getFileNameFromUri(context, it)
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val tempFile = File(context.cacheDir, originalName)
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        PdfConverter.savePdfToMediaStore(context, tempFile, originalName)
                        tempFile.delete()
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.refreshDocuments()
                        conversionResultMessage = pdfImportedMsg
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        viewModel.errorMessage.value = String.format(errorImportingPdfFormat, e.localizedMessage ?: e.message ?: e.toString())
                    }
                }
            }
        }
    }
    var shouldLaunchScanAfterPermission by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }
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
            } else {
                viewModel.errorMessage.value = failedPhotoToPdfMsg
            }
        }
    }
    val launchFallbackCamera = {
        try {
            val photoFile = File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            val photoUri = FileProvider.getUriForFile(context, "ua.com.devinsider.pdfscanner.fileprovider", photoFile)
            tempCameraImageUri = photoUri
            fallbackCameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            e.printStackTrace()
            viewModel.errorMessage.value = String.format(errorOpeningCameraFormat, e.localizedMessage ?: e.message ?: e.toString())
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
                    launchFallbackCamera()
                    return@rememberLauncherForActivityResult
                }
                val pdfUri = scanResult.pdf?.uri
                val pages = scanResult.pages
                if (pdfUri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(pdfUri)
                            if (inputStream != null) {
                                inputStream.use { stream ->
                                    val fileName = "Scanned_${System.currentTimeMillis()}.pdf"
                                    val tempFile = File(context.cacheDir, fileName)
                                    FileOutputStream(tempFile).use { outputStream ->
                                        stream.copyTo(outputStream)
                                    }
                                    
                                    PdfConverter.savePdfToMediaStore(context, tempFile, fileName)
                                    tempFile.delete()
                                    ua.com.devinsider.pdfscanner.utils.AnalyticsHelper.logEvent(context, "scan_document_success")
                                }
                                withContext(Dispatchers.Main) {
                                    conversionResultMessage = savedToDownloadsMsg
                                    viewModel.refreshDocuments()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    viewModel.errorMessage.value = String.format(errorSavingScannedPdfFormat, "InputStream is null")
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                viewModel.errorMessage.value = String.format(errorSavingScannedPdfFormat, e.localizedMessage ?: e.message ?: e.toString())
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
                    launchFallbackCamera()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launchFallbackCamera()
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
    val startScanning: () -> Unit = {
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
                            launchFallbackCamera()
                        }
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        launchFallbackCamera()
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                launchFallbackCamera()
            }
        }
    }
    val checkAndStartScan: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(shouldLaunchScanAfterPermission) {
        if (shouldLaunchScanAfterPermission) {
            shouldLaunchScanAfterPermission = false
            startScanning()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    Text(pluralStringResource(R.plurals.selected_count, selectedDocs.size, selectedDocs.size))
                } else {
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.searchQuery.value = it },
                        placeholder = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
            },
            navigationIcon = {
                if (isSelectionMode) {
                    IconButton(onClick = { 
                        isSelectionMode = false
                        selectedDocs.clear()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_selection))
                    }
                }
            },
            actions = {
                if (isSelectionMode) {
                    val allPdf = selectedDocs.all { it.type == DocumentType.PDF }
                    if (allPdf && selectedDocs.size > 1) {
                        IconButton(onClick = {
                            val uris = selectedDocs.map { it.uriString.toUri() }
                            scope.launch { snackbarHostState.showSnackbar(taskMergeBgMsg) }
                            val data = androidx.work.Data.Builder()
                                .putString("action", "merge")
                                .putStringArray("uris", uris.map { it.toString() }.toTypedArray())
                                .build()
                            val request = androidx.work.OneTimeWorkRequestBuilder<ua.com.devinsider.pdfscanner.utils.PdfWorker>()
                                .setInputData(data)
                                .build()
                            androidx.work.WorkManager.getInstance(context).enqueue(request)
                            isSelectionMode = false
                            selectedDocs.clear()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.merge_pdfs))
                        }
                    }
                    IconButton(onClick = {
                        val uris = selectedDocs.map { it.uriString.toUri() }
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareTitleMsg))
                        isSelectionMode = false
                        selectedDocs.clear()
                    }) {
                        Icon(Icons.Default.Share, stringResource(R.string.share))
                    }
                    IconButton(onClick = {
                        selectedDocs.forEach { viewModel.deleteDocument(it) }
                        isSelectionMode = false
                        selectedDocs.clear()
                    }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete))
                    }
                } else {
                    IconButton(onClick = { isSelectionMode = true }) {
                        Icon(Icons.Default.CheckBox, stringResource(R.string.select_files))
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.date_newest)) }, onClick = { viewModel.sortOption.value = SortOption.DATE_DESC; sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.date_oldest)) }, onClick = { viewModel.sortOption.value = SortOption.DATE_ASC; sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.name_a_z)) }, onClick = { viewModel.sortOption.value = SortOption.NAME_ASC; sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.name_z_a)) }, onClick = { viewModel.sortOption.value = SortOption.NAME_DESC; sortMenuExpanded = false })
                        }
                    }
                }
            }
        )
        // Scan & Import Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ElevatedCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        checkAndStartScan()
                    },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.scan_document), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.scan_document), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
                }
            }
            ElevatedCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        importPdfLauncher.launch(arrayOf("application/pdf"))
                    },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = stringResource(R.string.import_pdf), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.import_pdf), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        val isRefreshing by viewModel.isRefreshing.collectAsState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshDocuments() },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filteredDocs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.no_pdf_documents),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(filteredDocs, key = { it.path }) { doc ->
                        DocumentCard(
                            document = doc,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedDocs.contains(doc),
                            onDocumentClick = {
                                viewModel.addAppCreatedFile(doc.path)
                                if (doc.type == DocumentType.PDF) {
                                    onNavigateToViewer(doc.path)
                                } else {
                                    val uri = FileProvider.getUriForFile(context, "ua.com.devinsider.pdfscanner.fileprovider", File(doc.path))
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, doc.type.toMimeType())
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        viewModel.errorMessage.value = noAppToOpenFileMsg
                                    }
                                }
                            },
                            onDocumentLongClick = {
                                isSelectionMode = true
                                selectedDocs.add(doc)
                            },
                            onToggleBookmark = { viewModel.toggleBookmark(doc) },
                            onToggleSelection = {
                                if (selectedDocs.contains(doc)) selectedDocs.remove(doc)
                                else selectedDocs.add(doc)
                            },
                            onRenameClick = { 
                                documentToRename = doc
                                newFileName = doc.name
                            },
                            onShareClick = {
                                val uri = doc.uriString.toUri()
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = doc.type.toMimeType()
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, shareTitleMsg))
                            },
                            onDeleteClick = { documentToDelete = doc },
                            onConvertPdfClick = {
                                scope.launch { snackbarHostState.showSnackbar(taskConvertBgMsg) }
                                val data = androidx.work.Data.Builder()
                                    .putString("action", "convert_images")
                                    .putString("path", doc.path)
                                    .build()
                                val request = androidx.work.OneTimeWorkRequestBuilder<ua.com.devinsider.pdfscanner.utils.PdfWorker>()
                                    .setInputData(data)
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueue(request)
                            },
                            onConvertToLongImageClick = {
                                scope.launch { snackbarHostState.showSnackbar(taskLongImageBgMsg) }
                                val data = androidx.work.Data.Builder()
                                    .putString("action", "convert_long_image")
                                    .putString("path", doc.path)
                                    .build()
                                val request = androidx.work.OneTimeWorkRequestBuilder<ua.com.devinsider.pdfscanner.utils.PdfWorker>()
                                    .setInputData(data)
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueue(request)
                            },
                            onSplitPdfClick = {
                                scope.launch { snackbarHostState.showSnackbar(taskSplitBgMsg) }
                                val data = androidx.work.Data.Builder()
                                    .putString("action", "split")
                                    .putString("uri", doc.uriString)
                                    .build()
                                val request = androidx.work.OneTimeWorkRequestBuilder<ua.com.devinsider.pdfscanner.utils.PdfWorker>()
                                    .setInputData(data)
                                    .build()
                                androidx.work.WorkManager.getInstance(context).enqueue(request)
                            },
                            onInfoClick = { documentForInfo = doc }
                        )
                    }
                }
            }
        }
    }
    }
    // Dialogs
    documentToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = { Text(stringResource(R.string.delete_document)) },
            text = { Text(stringResource(R.string.delete_confirm_msg, doc.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDocument(doc)
                    viewModel.refreshDocuments()
                    documentToDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    documentToRename?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToRename = null },
            title = { Text(stringResource(R.string.rename_document)) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameDocument(doc, newFileName)
                    viewModel.refreshDocuments()
                    documentToRename = null
                }) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = {
                TextButton(onClick = { documentToRename = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    documentForInfo?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentForInfo = null },
            title = { Text(stringResource(R.string.file_info)) },
            text = {
                val currentLocale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", currentLocale)
                Column {
                    Text("${stringResource(R.string.name)}: ${doc.name}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stringResource(R.string.path)}: ${doc.path}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stringResource(R.string.size)}: ${doc.sizeBytes / 1024} KB")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stringResource(R.string.created)}: ${sdf.format(Date(doc.dateCreatedMillis))}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${stringResource(R.string.modified)}: ${sdf.format(Date(doc.dateModifiedMillis))}")
                }
            },
            confirmButton = {
                TextButton(onClick = { documentForInfo = null }) { Text(stringResource(R.string.ok)) }
            }
        )
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
    val viewModelError by viewModel.errorMessage.collectAsState()
    viewModelError?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearErrorMessage() }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}
@Suppress("UnusedReceiverParameter")
fun DocumentType.toMimeType(): String {
    return "application/pdf"
}