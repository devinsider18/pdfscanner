package ua.com.devinsider.pdfscanner.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import ua.com.devinsider.pdfscanner.R

@Composable
fun SignatureCanvas(
    modifier: Modifier = Modifier,
    onSaveSignature: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var paths by remember { mutableStateOf(listOf<List<Offset>>()) }
    var currentPath by remember { mutableStateOf(listOf<Offset>()) }
    // Track the actual Compose canvas size so we can reproduce it in a Bitmap
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPath = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPath = currentPath + change.position
                        },
                        onDragEnd = {
                            if (currentPath.isNotEmpty()) {
                                paths = paths + listOf(currentPath)
                                currentPath = emptyList()
                            }
                        },
                        onDragCancel = {
                            if (currentPath.isNotEmpty()) {
                                paths = paths + listOf(currentPath)
                                currentPath = emptyList()
                            }
                        }
                    )
                }
        ) {
            // Capture the canvas size (in px) every frame
            canvasSize = size

            // White background so the signature is visible
            drawRect(color = Color.White, size = size)

            val allPathsToDraw = if (currentPath.isNotEmpty()) paths + listOf(currentPath) else paths

            allPathsToDraw.forEach { points ->
                if (points.isNotEmpty()) {
                    val composePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    drawPath(
                        path = composePath,
                        color = Color.Black,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { paths = listOf(); currentPath = emptyList() }) {
                Text(stringResource(R.string.clear))
            }
            Button(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Button(onClick = {
                // Use the real canvas pixel size; fall back to 800×400 if not yet measured
                val width = if (canvasSize.width > 0f) canvasSize.width.toInt() else 800
                val height = if (canvasSize.height > 0f) canvasSize.height.toInt() else 400

                val bitmap = androidx.core.graphics.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                // Transparent background so signature doesn't cover PDF content underneath; drawing canvas still has white background for visibility.
                canvas.drawColor(android.graphics.Color.TRANSPARENT)

                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 5 * (width / 800f) * 3f  // scale stroke to bitmap size
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    isAntiAlias = true
                }

                val allPaths = if (currentPath.isNotEmpty()) paths + listOf(currentPath) else paths
                allPaths.forEach { points ->
                    if (points.isNotEmpty()) {
                        val p = android.graphics.Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                        canvas.drawPath(p, paint)
                    }
                }
                onSaveSignature(bitmap)
            }) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
