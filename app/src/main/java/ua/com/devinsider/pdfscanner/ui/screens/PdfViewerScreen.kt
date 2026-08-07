package ua.com.devinsider.pdfscanner.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.navigation.NavController
import androidx.core.net.toUri
import ua.com.devinsider.pdfscanner.utils.PdfConverter
import ua.com.devinsider.pdfscanner.utils.ConversionResult
import ua.com.devinsider.pdfscanner.ui.components.ConversionErrorDialog
import ua.com.devinsider.pdfscanner.R
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.createBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    navController: NavController
) {
    val context = LocalContext.current
    val convertingToLongImageMsg = stringResource(R.string.converting_to_long_image)
    val convertingToImagesMsg = stringResource(R.string.converting_to_images)
    val savedToPicturesMsg = stringResource(R.string.saved_to_pictures)
    val failedToConvertMsg = stringResource(R.string.failed_to_convert)

    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var conversionResultMessage by remember { mutableStateOf<String?>(null) }
    var conversionErrorDialogState by remember { mutableStateOf<ConversionResult.Error?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var isConverting by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val fd = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
                    context.contentResolver.openFileDescriptor(filePath.toUri(), "r")
                } else {
                    val file = File(filePath)
                    if (file.exists()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else {
                        context.contentResolver.openFileDescriptor(filePath.toUri(), "r")
                    }
                }
                if (fd != null) {
                    fileDescriptor = fd
                    val renderer = PdfRenderer(fd)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                } else {
                    isError = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isError = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(File(filePath).name) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isConverting) return@IconButton
                            isConverting = true
                            scope.launch {
                                snackbarHostState.showSnackbar(convertingToLongImageMsg)
                                val result = PdfConverter.convertPdfToLongImageWithResult(context, filePath)
                                isConverting = false
                                if (result is ConversionResult.Success) {
                                    conversionResultMessage = savedToPicturesMsg
                                } else if (result is ConversionResult.Error) {
                                    conversionErrorDialogState = result
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Image, stringResource(R.string.pdf_to_long_image))
                    }
                    IconButton(
                        onClick = {
                            if (isConverting) return@IconButton
                            isConverting = true
                            scope.launch {
                                snackbarHostState.showSnackbar(convertingToImagesMsg)
                                val result = PdfConverter.convertPdfToImagesWithResult(context, filePath) { _, _ -> }
                                isConverting = false
                                if (result is ConversionResult.Success) {
                                    conversionResultMessage = savedToPicturesMsg
                                } else if (result is ConversionResult.Error) {
                                    conversionErrorDialogState = result
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, stringResource(R.string.pdf_to_image))
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.invalid_pdf), color = MaterialTheme.colorScheme.error)
            }
        } else if (pageCount == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.loading_pdf))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color.LightGray),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pageCount) { index ->
                    PdfPageImage(pdfRenderer = pdfRenderer, pageIndex = index)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        
        conversionResultMessage?.let { msg ->
            LaunchedEffect(msg) {
                snackbarHostState.showSnackbar(msg)
                conversionResultMessage = null
            }
        }

        conversionErrorDialogState?.let { error ->
            ConversionErrorDialog(
                error = error,
                onDismiss = { conversionErrorDialogState = null }
            )
        }
    }
}

@Composable
fun PdfPageImage(pdfRenderer: PdfRenderer?, pageIndex: Int) {
    if (pdfRenderer == null) return
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current.density

    val scope = rememberCoroutineScope()

    DisposableEffect(pdfRenderer, pageIndex) {
        var currentBitmap: Bitmap? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                val destBitmap = synchronized(pdfRenderer) {
                    val page = pdfRenderer.openPage(pageIndex)
                    val bitmapRes = createBitmap(
                        (page.width * density).toInt(),
                        (page.height * density).toInt()
                    )
                    bitmapRes.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmapRes, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmapRes
                }
                bitmap = destBitmap
                currentBitmap = destBitmap
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            job.cancel()
            currentBitmap?.recycle()
            bitmap = null
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = stringResource(R.string.page_number, pageIndex + 1),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
