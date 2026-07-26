package ua.com.devinsider.pdfscanner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.com.devinsider.pdfscanner.data.model.DocumentItem
import ua.com.devinsider.pdfscanner.data.model.DocumentType
import ua.com.devinsider.pdfscanner.R
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentCard(
    document: DocumentItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onDocumentClick: () -> Unit,
    onDocumentLongClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleSelection: () -> Unit,
    onRenameClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onConvertPdfClick: () -> Unit,
    onConvertToLongImageClick: () -> Unit,
    onSplitPdfClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = {
                if (isSelectionMode) {
                    onToggleSelection()
                } else {
                    onDocumentClick()
                }
            }),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type Badge
            Surface(
                color = when (document.type) {
                    DocumentType.PDF -> Color.Red.copy(alpha = 0.2f)
                    DocumentType.WORD -> Color.Blue.copy(alpha = 0.2f)
                    DocumentType.EXCEL -> Color.Green.copy(alpha = 0.2f)
                    DocumentType.PPT -> Color.Unspecified // Orange equivalent later
                    else -> Color.Gray.copy(alpha = 0.2f)
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = document.type.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                    Text(
                        text = sdf.format(Date(document.dateModifiedMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatSize(document.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() }
                )
            } else {
                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (document.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Toggle Bookmark",
                        tint = if (document.isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { expandedMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                    }
                    DropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { expandedMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = {
                                expandedMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = {
                                expandedMenu = false
                                onShareClick()
                            }
                        )
                        if (document.type == DocumentType.PDF) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_to_image)) },
                                onClick = {
                                    expandedMenu = false
                                    onConvertPdfClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_to_long_image)) },
                                onClick = {
                                    expandedMenu = false
                                    onConvertToLongImageClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.split_pdf)) },
                                onClick = {
                                    expandedMenu = false
                                    onSplitPdfClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.file_info)) },
                            onClick = {
                                expandedMenu = false
                                onInfoClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                expandedMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
