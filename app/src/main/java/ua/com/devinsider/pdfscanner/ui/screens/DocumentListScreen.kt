package ua.com.devinsider.pdfscanner.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Environment
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import android.widget.Toast
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
    
    val mergingPdfsMsg = stringResource(R.string.merging_pdfs)
    val savedToDownloadsMsg = stringResource(R.string.saved_to_downloads)
    val failedToMergeMsg = stringResource(R.string.failed_to_merge)
    val shareTitleMsg = stringResource(R.string.share)
    val convertingToImagesMsg = stringResource(R.string.converting_to_images)
    val savedToPicturesMsg = stringResource(R.string.saved_to_pictures)
    val failedToConvertMsg = stringResource(R.string.failed_to_convert)
    val convertingToLongImageMsg = stringResource(R.string.converting_to_long_image)
    val splittingPdfMsg = stringResource(R.string.splitting_pdf)
    val failedToSplitMsg = stringResource(R.string.failed_to_split)
    
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedDocs = remember { mutableStateListOf<DocumentItem>() }
    
    var sortMenuExpanded by remember { mutableStateOf(false) }
    
    var documentToDelete by remember { mutableStateOf<DocumentItem?>(null) }
    var documentToRename by remember { mutableStateOf<DocumentItem?>(null) }
    var documentForInfo by remember { mutableStateOf<DocumentItem?>(null) }
    var newFileName by remember { mutableStateOf("") }
    var conversionResultMessage by remember { mutableStateOf<String?>(null) }
    
    // Permission Handling for Android 11+ (minSdk 31)
    var hasPermission by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { 
            hasPermission = Environment.isExternalStorageManager()
            if (hasPermission) {
                viewModel.refreshDocuments()
            }
        }
    )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.refreshDocuments()
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
                        val inputStream = context.contentResolver.openInputStream(pdf.uri)
                        val fileName = "Scanned_${System.currentTimeMillis()}.pdf"
                        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        val appDir = File(documentsDir, "PDFScanner")
                        if (!appDir.exists()) {
                            appDir.mkdirs()
                        }
                        val outputFile = File(appDir, fileName)
                        val outputStream = FileOutputStream(outputFile)
                        inputStream?.copyTo(outputStream)
                        inputStream?.close()
                        outputStream.close()
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Saved to Documents/PDFScanner", Toast.LENGTH_SHORT).show()
                            viewModel.addAppCreatedFile(outputFile.absolutePath)
                            viewModel.refreshDocuments()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.all_files_access_required), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.all_files_access_desc), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                manageStorageLauncher.launch(intent)
            }) {
                Text(stringResource(R.string.grant_permission))
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                            val uris = selectedDocs.map { Uri.fromFile(File(it.path)) }
                            scope.launch {
                                Toast.makeText(context, mergingPdfsMsg, Toast.LENGTH_SHORT).show()
                                val success = PdfConverter.mergePdfs(context, uris)
                                conversionResultMessage = if (success) savedToDownloadsMsg else failedToMergeMsg
                                isSelectionMode = false
                                selectedDocs.clear()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.CallMerge, stringResource(R.string.merge_pdfs))
                        }
                    }
                    
                    IconButton(onClick = {
                        val uris = selectedDocs.map { 
                            FileProvider.getUriForFile(context, "ua.com.devinsider.pdfscanner.fileprovider", File(it.path))
                        }
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
        
        // Scan Button Card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                        }
                },
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = stringResource(R.string.scan_document), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.scan_document), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
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
                        val uri = FileProvider.getUriForFile(context, "ua.com.devinsider.pdfscanner.fileprovider", File(doc.path))
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = doc.type.toMimeType()
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareTitleMsg))
                    },
                    onDeleteClick = { documentToDelete = doc },
                    onConvertPdfClick = {
                        scope.launch {
                            Toast.makeText(context, convertingToImagesMsg, Toast.LENGTH_SHORT).show()
                            val success = PdfConverter.convertPdfToImages(context, doc.path) { _, _ -> }
                            conversionResultMessage = if (success) savedToPicturesMsg else failedToConvertMsg
                        }
                    },
                    onConvertToLongImageClick = {
                        scope.launch {
                            Toast.makeText(context, convertingToLongImageMsg, Toast.LENGTH_SHORT).show()
                            val success = PdfConverter.convertPdfToLongImage(context, doc.path)
                            conversionResultMessage = if (success) savedToPicturesMsg else failedToConvertMsg
                        }
                    },
                    onSplitPdfClick = {
                        scope.launch {
                            Toast.makeText(context, splittingPdfMsg, Toast.LENGTH_SHORT).show()
                            val success = PdfConverter.splitPdf(context, Uri.fromFile(File(doc.path)))
                            conversionResultMessage = if (success) savedToDownloadsMsg else failedToSplitMsg
                        }
                    },
                    onInfoClick = { documentForInfo = doc }
                )
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
                val currentLocale = LocalConfiguration.current.locales[0]
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
}

fun DocumentType.toMimeType(): String {
    return when(this) {
        DocumentType.PDF -> "application/pdf"
        DocumentType.WORD -> "application/msword"
        DocumentType.EXCEL -> "application/vnd.ms-excel"
        DocumentType.PPT -> "application/vnd.ms-powerpoint"
        else -> "*/*"
    }
}


