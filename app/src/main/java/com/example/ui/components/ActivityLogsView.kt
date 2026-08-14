package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
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
fun ActivityLogsView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(FileActivityLogger.getLogs(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, UPLOAD, DELETE, COPY, MOVE, RENAME
    var showClearDialog by remember { mutableStateOf(false) }

    fun refreshLogs() {
        logs = FileActivityLogger.getLogs(context)
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        logs.filter { log ->
            val matchesFilter = when (selectedFilter) {
                "UPLOAD" -> log.actionType.equals("UPLOAD", ignoreCase = true)
                "DELETE" -> log.actionType.equals("DELETE", ignoreCase = true)
                "COPY" -> log.actionType.equals("COPY", ignoreCase = true)
                "MOVE" -> log.actionType.equals("MOVE", ignoreCase = true)
                "RENAME" -> log.actionType.equals("RENAME", ignoreCase = true)
                else -> true
            }
            val query = searchQuery.trim().lowercase()
            val matchesSearch = query.isBlank() ||
                    log.fileName.lowercase().contains(query) ||
                    log.fileId.lowercase().contains(query) ||
                    log.userEmail.lowercase().contains(query) ||
                    log.sourceFolder.lowercase().contains(query) ||
                    log.destinationFolder.lowercase().contains(query) ||
                    log.dateDayString.lowercase().contains(query)
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Info & Title Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = WaterBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Detailed Activity Logs",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Track uploads, deletions, moves, and copies with sequential File IDs",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            IconButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.testTag("clear_logs_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear Logs",
                    tint = Color(0xFFEF5350)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by File ID, name, email, date...", color = Color.Gray, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WaterBlue) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("logs_search_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WaterBlue,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedContainerColor = SurfaceCard,
                unfocusedContainerColor = SurfaceCard,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                "ALL" to "All (${logs.size})",
                "UPLOAD" to "Uploads 📤",
                "DELETE" to "Deletions 🗑️",
                "COPY" to "Copies 📋",
                "MOVE" to "Moves 🚚",
                "RENAME" to "Renames ✏️"
            )

            filters.forEach { (key, label) ->
                val isSelected = selectedFilter == key
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = key },
                    label = {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color.LightGray
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaterBlue,
                        containerColor = SurfaceCard
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Color.White.copy(alpha = 0.15f),
                        selectedBorderColor = WaterBlue
                    )
                )
            }
        }

        // Summary bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Showing ${filteredLogs.size} logs",
                fontSize = 11.sp,
                color = Color.Gray,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable {
                    openGoogleDoc(context, FileActivityLogger.GOOGLE_DOC_URL)
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = WaterBlue,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "Google Drive Sync Doc",
                    fontSize = 10.sp,
                    color = WaterBlue,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Log List
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No activity logs found",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Upload, move, copy, or delete files to record detailed audit logs.",
                        color = Color.Gray.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    ActivityLogCard(log = log, onDocClick = {
                        openGoogleDoc(context, log.googleDocUrl)
                    })
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Activity Logs?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("This will remove all stored file operation logs from this device.", color = Color.LightGray) },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileActivityLogger.clearLogs(context)
                        refreshLogs()
                        showClearDialog = false
                        Toast.makeText(context, "Activity logs cleared.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear All", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = SurfaceCard
        )
    }
}

@Composable
fun ActivityLogCard(
    log: FileActivityLog,
    onDocClick: () -> Unit
) {
    val (badgeColor, actionLabel, actionIcon) = when (log.actionType.uppercase()) {
        "UPLOAD" -> Triple(Color(0xFF4ADE80), "UPLOADED 📤", Icons.Default.CloudUpload)
        "DELETE" -> Triple(Color(0xFFEF5350), "DELETED 🗑️", Icons.Default.Delete)
        "COPY" -> Triple(Color(0xFF60A5FA), "COPIED 📋", Icons.Default.ContentCopy)
        "MOVE" -> Triple(Color(0xFFC084FC), "MOVED 🚚", Icons.Default.DriveFileMove)
        "RENAME" -> Triple(Color(0xFFFBBF24), "RENAMED ✏️", Icons.Default.Edit)
        else -> Triple(WaterBlue, log.actionType.uppercase(), Icons.Default.Info)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_log_card_${log.id}"),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Action badge & File ID & Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(actionIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(12.dp))
                            Text(
                                text = actionLabel,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Sequential File ID
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = log.fileId,
                            color = WaterBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Date & Day
                Text(
                    text = log.dateDayString,
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            // File Name & Format/Size
            Column {
                Text(
                    text = log.fileName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Size: ${log.fileSizeFormatted} • Format: ${log.fileFormat}",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            // User & Folder Path Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                        Text(
                            text = "${log.userName} (${log.userEmail})",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(12.dp))
                        val locationText = if (log.destinationFolder.isNotEmpty()) {
                            "${log.sourceFolder} ➔ ${log.destinationFolder}"
                        } else {
                            log.sourceFolder
                        }
                        Text(
                            text = "Folder: $locationText",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Doc Sync button
                OutlinedButton(
                    onClick = onDocClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WaterBlue),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.4f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp))
                        Text("Doc Link", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun openGoogleDoc(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Google Doc Link", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Link copied to clipboard: $url", Toast.LENGTH_LONG).show()
    }
}
