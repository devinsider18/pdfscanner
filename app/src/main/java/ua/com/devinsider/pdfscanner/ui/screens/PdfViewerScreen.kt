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
import ua.com.devinsider.pdfscanner.utils.PdfConverter
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
    val snackbarHostState = remember { SnackbarHostState() }
    var isConverting by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    fileDescriptor = fd
                    val renderer = PdfRenderer(fd)
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (isConverting) return@IconButton
                            isConverting = true
                            scope.launch {
                                snackbarHostState.showSnackbar(convertingToLongImageMsg)
                                val success = PdfConverter.convertPdfToLongImage(context, filePath)
                                isConverting = false
                                snackbarHostState.showSnackbar(if (success) savedToPicturesMsg else failedToConvertMsg)
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
                                val success = PdfConverter.convertPdfToImages(context, filePath) { _, _ -> }
                                isConverting = false
                                snackbarHostState.showSnackbar(if (success) savedToPicturesMsg else failedToConvertMsg)
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
    }
}

@Composable
fun PdfPageImage(pdfRenderer: PdfRenderer?, pageIndex: Int) {
    if (pdfRenderer == null) return
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current.density

    LaunchedEffect(pdfRenderer, pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val page = pdfRenderer.openPage(pageIndex)
                // Render at a higher resolution for clarity (e.g. 2x)
                val destBitmap = createBitmap(
                    (page.width * density).toInt(),
                    (page.height * density).toInt()
                )
                // White background
                destBitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(destBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap = destBitmap
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
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
