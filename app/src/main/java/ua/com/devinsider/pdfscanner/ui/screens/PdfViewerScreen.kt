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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ua.com.devinsider.pdfscanner.ui.viewmodels.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
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

    val billingManager = viewModel.billingManager
    val isPro by billingManager.isPro.collectAsState()
    var showSignatureCanvas by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var pendingSignatureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var signatureRelativeX by remember { androidx.compose.runtime.mutableFloatStateOf(0.1f) }
    var signatureRelativeY by remember { androidx.compose.runtime.mutableFloatStateOf(0.1f) }
    var signaturePageIndex by remember { mutableIntStateOf(0) }
    var isPlacingSignature by remember { mutableStateOf(false) }

    LaunchedEffect(isPro) {
        if (!isPro) {
            ua.com.devinsider.pdfscanner.data.repository.AdsManager.loadRewardedAd()
        }
    }

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

    val confirmSignature = {
        val bitmap = pendingSignatureBitmap
        if (bitmap != null) {
            isPlacingSignature = false
            if (isPro) {
                scope.launch {
                    val success = PdfConverter.addSignatureToPdf(
                        context, filePath, bitmap, signaturePageIndex, signatureRelativeX, signatureRelativeY
                    )
                    if (success) {
                        snackbarHostState.showSnackbar("Signature saved to Downloads/PDFScanner")
                    } else {
                        snackbarHostState.showSnackbar("Failed to add signature")
                    }
                    pendingSignatureBitmap = null
                }
            } else {
                showPaywall = true
            }
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
                    IconButton(
                        onClick = {
                            showSignatureCanvas = true
                        }
                    ) {
                        Icon(Icons.Default.Edit, stringResource(R.string.sign))
                    }
                }
            )
        },
        floatingActionButton = {
            if (isPlacingSignature && pendingSignatureBitmap != null) {
                ExtendedFloatingActionButton(
                    onClick = confirmSignature,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Confirm Signature") }
                )
            }
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
                    PdfPageImage(
                        pdfRenderer = pdfRenderer, 
                        pageIndex = index,
                        signatureBitmap = if (signaturePageIndex == index && isPlacingSignature) pendingSignatureBitmap else null,
                        isPlacing = isPlacingSignature,
                        relativeX = signatureRelativeX,
                        relativeY = signatureRelativeY,
                        onPositionChange = { x, y ->
                            signatureRelativeX = x
                            signatureRelativeY = y
                        },
                        onPageSelected = { signaturePageIndex = index }
                    )
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

        if (showSignatureCanvas) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showSignatureCanvas = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                ua.com.devinsider.pdfscanner.ui.components.SignatureCanvas(
                    onSaveSignature = { bitmap ->
                        pendingSignatureBitmap = bitmap
                        signatureRelativeX = 0.1f
                        signatureRelativeY = 0.1f
                        isPlacingSignature = true
                        showSignatureCanvas = false
                    },
                    onCancel = { showSignatureCanvas = false }
                )
            }
        }

        if (showPaywall) {
            AlertDialog(
                onDismissRequest = { 
                    showPaywall = false 
                    pendingSignatureBitmap = null
                },
                title = { Text(stringResource(R.string.signature_feature_title)) },
                text = { Text(stringResource(R.string.premium_feature_required)) },
                confirmButton = {
                    Button(onClick = { 
                        showPaywall = false
                        billingManager.purchasePremium(context as android.app.Activity)
                    }) {
                        Text(stringResource(R.string.pay_for_feature))
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        showPaywall = false
                        scope.launch {
                            val adSuccess = ua.com.devinsider.pdfscanner.data.repository.AdsManager.showRewardedAd(context as android.app.Activity)
                            if (adSuccess && pendingSignatureBitmap != null) {
                                val success = PdfConverter.addSignatureToPdf(
                                    context, filePath, pendingSignatureBitmap!!, signaturePageIndex, signatureRelativeX, signatureRelativeY
                                )
                                if (success) {
                                    snackbarHostState.showSnackbar("Signature saved to Downloads/PDFScanner")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to add signature")
                                }
                            } else {
                                snackbarHostState.showSnackbar("Ad not watched fully or failed")
                            }
                            pendingSignatureBitmap = null
                        }
                    }) {
                        Text(stringResource(R.string.watch_ad))
                    }
                }
            )
        }
    }
}

@Composable
fun PdfPageImage(
    pdfRenderer: PdfRenderer?,
    pageIndex: Int,
    signatureBitmap: Bitmap? = null,
    isPlacing: Boolean = false,
    relativeX: Float = 0.1f,
    relativeY: Float = 0.1f,
    onPositionChange: (Float, Float) -> Unit = { _, _ -> },
    onPageSelected: () -> Unit = {}
) {
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
        // Track the actual rendered size of the image in pixels so we can
        // correctly position and size the signature overlay.
        var imageWidthPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        var imageHeightPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clipToBounds()
        ) {
            Image(
                bitmap = currentBmp.asImageBitmap(),
                contentDescription = stringResource(R.string.page_number, pageIndex + 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        imageWidthPx = coords.size.width.toFloat()
                        imageHeightPx = coords.size.height.toFloat()
                        // Selecting this page when tapped during placement is handled
                        // by the signature overlay's gesture block below.
                    },
                contentScale = ContentScale.FillWidth
            )

            if (signatureBitmap != null && isPlacing && imageWidthPx > 0f && imageHeightPx > 0f) {
                val boxW = imageWidthPx
                val boxH = imageHeightPx

                // Display the signature at 25 % of the rendered image width, keeping aspect ratio.
                val sigDisplayW = boxW * 0.25f
                val aspectRatio = if (signatureBitmap.height > 0) {
                    signatureBitmap.width.toFloat() / signatureBitmap.height.toFloat()
                } else 1f
                val sigDisplayH = sigDisplayW / aspectRatio

                val localDensity = LocalDensity.current
                val sigWDp = with(localDensity) { sigDisplayW.toDp() }
                val sigHDp = with(localDensity) { sigDisplayH.toDp() }

                // rememberUpdatedState lets the gesture lambda always read the latest
                // position without restarting the pointerInput block on every recomposition.
                val currentRelativeX by rememberUpdatedState(relativeX)
                val currentRelativeY by rememberUpdatedState(relativeY)

                Image(
                    bitmap = signatureBitmap.asImageBitmap(),
                    contentDescription = "Signature Overlay",
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (relativeX * boxW).toInt(),
                                (relativeY * boxH).toInt()
                            )
                        }
                        .size(sigWDp, sigHDp)
                        .pointerInput(boxW, boxH) {
                            awaitEachGesture {
                                // Claim the down event so the LazyColumn scroll doesn't
                                // steal it and so onPageSelected fires on tap-without-drag.
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                onPageSelected()

                                var didDrag = false
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        val delta = change.positionChange()
                                        if (delta.x != 0f || delta.y != 0f) {
                                            didDrag = true
                                            change.consume()
                                            val newX = (currentRelativeX * boxW + delta.x) / boxW
                                            val newY = (currentRelativeY * boxH + delta.y) / boxH
                                            onPositionChange(
                                                newX.coerceIn(0f, 1f),
                                                newY.coerceIn(0f, 1f)
                                            )
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                )
            }
        }
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
