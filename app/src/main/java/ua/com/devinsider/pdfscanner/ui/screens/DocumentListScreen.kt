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
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import java.io.File
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import ua.com.devinsider.pdfscanner.data.model.SortOption
import ua.com.devinsider.pdfscanner.ui.components.DocumentCard
import ua.com.devinsider.pdfscanner.ui.viewmodels.MainViewModel
import ua.com.devinsider.pdfscanner.utils.PdfConverter
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
    val scannerLaunchErrorMsg = stringResource(R.string.scanner_launch_error)
    
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
            viewModel.errorMessage.value = "Storage permission is required on older Android versions to save files"
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
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val tempFile = File(context.cacheDir, "Imported_${System.currentTimeMillis()}.pdf")
                        FileOutputStream(tempFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    
                        PdfConverter.savePdfToMediaStore(context, tempFile, "Imported_${System.currentTimeMillis()}.pdf")
                        tempFile.delete()
                    }
                    
                    withContext(Dispatchers.Main) {
                        viewModel.refreshDocuments()
                        conversionResultMessage = "PDF Imported"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        viewModel.errorMessage.value = "Error importing PDF: ${e.message}"
                    }
                }
            }
        }
    }

    // ML Kit Scanner Launcher
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pdf?.let { pdf ->
                scope.launch(Dispatchers.IO) {
                    try {
                        try {
                            context.contentResolver.openInputStream(pdf.uri)?.use { inputStream ->
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
                                viewModel.errorMessage.value = "Error saving scanned PDF: ${e.message}"
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            viewModel.errorMessage.value = "Scanner error: ${e.message}"
                        }
                    }
                }
            }
        }
    }



    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Top Bar Area
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    Text(stringResource(R.string.selected_count, selectedDocs.size))
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
                        val options = GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(true)
                            .setResultFormats(RESULT_FORMAT_PDF)
                            .setScannerMode(SCANNER_MODE_FULL)
                            .build()
                        val scanner = GmsDocumentScanning.getClient(options)
                        scanner.getStartScanIntent(context as Activity)
                            .addOnSuccessListener { intentSender ->
                                scannerLauncher.launch(
                                    androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                            .addOnFailureListener { e ->
                                e.printStackTrace()
                                conversionResultMessage = scannerLaunchErrorMsg
                            }
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
                    Icon(Icons.Default.UploadFile, contentDescription = "Import PDF", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Import PDF", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        
        LazyColumn(modifier = Modifier.weight(1f)) {
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
                                viewModel.errorMessage.value = "No application found to open this file"
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
            title = { Text("Error") },
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


