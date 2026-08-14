package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue
import com.example.util.FileActivityLog
import com.example.util.FileActivityLogger

@Composable
fun FileDocumentStructureView(
    folderHeading: String,
    folderDescription: String,
    folderIcon: androidx.compose.ui.graphics.vector.ImageVector,
    folderColor: Color,
    files: List<ExplorerFile>,
    onBackClick: (() -> Unit)? = null,
    onCreateFolderClick: (() -> Unit)? = null,
    onUploadFileClick: (() -> Unit)? = null,
    onFileClick: (ExplorerFile) -> Unit,
    onOptionsClick: (ExplorerFile) -> Unit,
    onCopyClick: (ExplorerFile) -> Unit,
    onMoveClick: (ExplorerFile) -> Unit,
    onRenameClick: (ExplorerFile) -> Unit,
    onDeleteClick: (ExplorerFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp)
    ) {
        // Back Header if inside subfolder
        if (onBackClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBackClick() }
                    .padding(vertical = 6.dp)
                    .testTag("folder_back_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = WaterBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Back to Folder Structure",
                    color = WaterBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // BIG HEADING (Folder)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, folderColor.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(folderColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = folderIcon,
                            contentDescription = null,
                            tint = folderColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FOLDER: ${folderHeading.uppercase()}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$folderDescription (${files.size} files)",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                if (onCreateFolderClick != null || onUploadFileClick != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onCreateFolderClick != null) {
                            Button(
                                onClick = onCreateFolderClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E2640),
                                    contentColor = WaterBlue
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("create_folder_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Create Folder",
                                    tint = WaterBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Create Folder",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (onUploadFileClick != null) {
                            Button(
                                onClick = onUploadFileClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF261D3B),
                                    contentColor = Color(0xFFCE93D8)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .testTag("upload_file_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Upload File",
                                    tint = Color(0xFFCE93D8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Upload File",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                    Text("No files in this folder", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Create a folder (manual / Google Drive) or upload a file (device / Drive link).",
                        color = Color.Gray,
                        fontSize = 11.5.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(files, key = { it.path.ifEmpty { it.name } + it.timestamp }) { file ->
                    FileDocumentBulletCard(
                        context = context,
                        file = file,
                        folderHeading = folderHeading,
                        onFileClick = { onFileClick(file) },
                        onOptionsClick = { onOptionsClick(file) },
                        onCopyClick = { onCopyClick(file) },
                        onMoveClick = { onMoveClick(file) },
                        onRenameClick = { onRenameClick(file) },
                        onDeleteClick = { onDeleteClick(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun FileDocumentBulletCard(
    context: Context,
    file: ExplorerFile,
    folderHeading: String,
    onFileClick: () -> Unit,
    onOptionsClick: () -> Unit,
    onCopyClick: () -> Unit,
    onMoveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val fileKey = file.appFileRef?.uriString?.ifEmpty { file.path } ?: (file.path + "/" + file.name)
    val sequentialId = FileActivityLogger.allocateSequentialFileId(context, fileKey)

    val formattedSize = if (file.appFileRef != null && file.appFileRef.size > 0) {
        formatFileSize(file.appFileRef.size)
    } else {
        "1.2 MB"
    }

    val formattedDate = FileActivityLog.formatDateWithDay(file.timestamp)
    val uploaderDetails = "Bharathi Krishna (bharathikrishna9440@gmail.com)"
    val docUrl = FileActivityLogger.GOOGLE_DOC_URL

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_bullet_card_${file.name}"),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // MAIN BULLET POINT: File Name with File ID
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFileClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "•",
                        color = WaterBlue,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(WaterBlue.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = sequentialId,
                            color = WaterBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = file.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onOptionsClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.Gray)
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

            // SUB-BULLET POINTS (File Details)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SubBulletItem(label = "Sequential File ID", value = sequentialId, valueColor = WaterBlue)
                SubBulletItem(label = "Format", value = file.fileMime.ifEmpty { file.type.uppercase() })
                SubBulletItem(label = "File Size", value = formattedSize)
                SubBulletItem(label = "Uploader", value = uploaderDetails)
                SubBulletItem(label = "Uploaded Date & Time", value = formattedDate)
                SubBulletItem(label = "Permissions", value = "Anyone with link can view & download", valueColor = Color(0xFF81C784))

                // Google Drive Sync Link sub-bullet point
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clickable { openDocLink(context, docUrl) }
                        .padding(vertical = 2.dp)
                ) {
                    Text(text = "◦", color = Color.Gray, fontSize = 12.sp)
                    Text(text = "Google Drive Sync Doc:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Open Shared Google Doc 🔗",
                        color = WaterBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Quick Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onFileClick,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onCopyClick,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF60A5FA).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onMoveClick,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC084FC)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC084FC).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Move", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onRenameClick,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rename", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SubBulletItem(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "◦", color = Color.Gray, fontSize = 12.sp)
        Text(text = "$label:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(text = value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    val formatted = String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    return formatted
}

private fun openDocLink(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Google Doc Link", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
    }
}
