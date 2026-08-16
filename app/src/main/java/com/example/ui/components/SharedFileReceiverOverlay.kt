package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.AppViewModel
import com.example.ui.SharedFileIntentData
import com.example.ui.theme.WaterBlue
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SharedFileReceiverOverlay(
    viewModel: AppViewModel,
    sharedIntent: SharedFileIntentData
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    when (sharedIntent.flowType) {
        "journal" -> {
            LaunchedEffect(sharedIntent) {
                isProcessing = true
                try {
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    val currentTimeStr = timeFormat.format(Date())

                    val urisToProcess = if (sharedIntent.uris.isNotEmpty()) {
                        sharedIntent.uris.mapIndexed { idx, u ->
                            val n = sharedIntent.names.getOrNull(idx) ?: "attachment_$idx"
                            Pair(u, n)
                        }
                    } else if (sharedIntent.uri != Uri.EMPTY) {
                        listOf(Pair(sharedIntent.uri, sharedIntent.name))
                    } else {
                        emptyList()
                    }

                    val newAttachments = mutableListOf<String>()
                    urisToProcess.forEach { (fileUri, fileName) ->
                        val finalPath = viewModel.getUploadedOrLocalPath(context, fileUri, fileName, sharedIntent.mimeType)
                        val prefix = when {
                            sharedIntent.mimeType.startsWith("image/") || fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".webp", true) -> "photo:"
                            sharedIntent.mimeType.startsWith("video/") || fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".webm", true) -> "video:"
                            sharedIntent.mimeType.startsWith("audio/") || fileName.endsWith(".mp3", true) || fileName.endsWith(".wav", true) || fileName.endsWith(".m4a", true) -> "audio:"
                            else -> "file:$fileName|path:"
                        }
                        newAttachments.add("$prefix$finalPath")
                    }

                    // Resolve current location
                    val locTag = viewModel.getAutoLocationGeotag()
                    if (locTag.isNotEmpty() && newAttachments.none { it.startsWith("loc:") }) {
                        newAttachments.add(locTag)
                    }

                    val entries = viewModel.journalEntries.value
                    val existing = entries.find { it.dateString == todayDate }
                    val sharedBodyText = sharedIntent.sharedText?.trim()

                    if (existing != null) {
                        val currentAttachments = if (existing.attachmentsJson.isNotEmpty()) {
                            existing.attachmentsJson.split(";;").filter { it.isNotBlank() }
                        } else {
                            emptyList()
                        }
                        val updatedAttachments = (currentAttachments + newAttachments).distinct()
                        val updatedText = if (!sharedBodyText.isNullOrBlank()) {
                            if (existing.text.isBlank()) sharedBodyText else "${existing.text}\n\n$sharedBodyText"
                        } else {
                            existing.text
                        }
                        val updated = existing.copy(
                            text = updatedText,
                            attachmentsJson = updatedAttachments.joinToString(";;")
                        )
                        viewModel.updateJournalEntry(updated)
                    } else {
                        val initialTitle = "Journal Entry - $currentTimeStr"
                        val initialText = if (!sharedBodyText.isNullOrBlank()) sharedBodyText else "Journal entry with shared attachments"
                        viewModel.createJournalEntryWithId(
                            title = initialTitle,
                            text = initialText,
                            dateString = todayDate,
                            timestamp = System.currentTimeMillis(),
                            attachments = newAttachments.joinToString(";;")
                        )
                    }
                    Toast.makeText(context, "Saved to Journal for today ($todayDate, $currentTimeStr) with current location!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("SharedFile", "Failed to save journal entry with location & time", e)
                    Toast.makeText(context, "Error saving to Journal: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                    viewModel.setSharedFileIntent(null)
                }
            }

            Dialog(onDismissRequest = {}) {
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .background(Color(0xFF101014), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(color = WaterBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Saving to Journal...",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Adding date, time & location",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        "note", "private_note" -> {
            LaunchedEffect(sharedIntent) {
                isProcessing = true
                try {
                    val urisToProcess = if (sharedIntent.uris.isNotEmpty()) {
                        sharedIntent.uris.mapIndexed { idx, u ->
                            val n = sharedIntent.names.getOrNull(idx) ?: "attachment_$idx"
                            Pair(u, n)
                        }
                    } else if (sharedIntent.uri != Uri.EMPTY) {
                        listOf(Pair(sharedIntent.uri, sharedIntent.name))
                    } else {
                        emptyList()
                    }

                    val attachmentPaths = mutableListOf<String>()
                    urisToProcess.forEach { (fileUri, fileName) ->
                        val finalPath = viewModel.getUploadedOrLocalPath(context, fileUri, fileName, sharedIntent.mimeType)
                        attachmentPaths.add(finalPath)
                    }

                    val noteTitle = if (sharedIntent.name.isNotBlank() && sharedIntent.name != "Shared Content") {
                        sharedIntent.name
                    } else if (!sharedIntent.sharedText.isNullOrBlank()) {
                        sharedIntent.sharedText.take(30).trim()
                    } else {
                        "Shared Note"
                    }

                    val contentBuilder = StringBuilder()
                    if (!sharedIntent.sharedText.isNullOrBlank()) {
                        contentBuilder.append(sharedIntent.sharedText)
                    }
                    if (attachmentPaths.isNotEmpty()) {
                        if (contentBuilder.isNotEmpty()) contentBuilder.append("\n\n")
                        attachmentPaths.forEach { p ->
                            contentBuilder.append("Attachment: $p\n")
                        }
                    }

                    viewModel.insertKeepNote(
                        title = noteTitle,
                        content = contentBuilder.toString().trim()
                    )
                    Toast.makeText(context, "Note created successfully!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("SharedFile", "Failed to save note attachment", e)
                    Toast.makeText(context, "Error saving Note: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                    viewModel.setSharedFileIntent(null)
                }
            }

            // Show progress
            Dialog(onDismissRequest = {}) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .background(Color(0xFF101014), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = WaterBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Creating Note...", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        "shared_folder", "private_folder" -> {
            val isPrivate = sharedIntent.flowType == "private_folder"
            val flowTitle = if (isPrivate) "Save to Private Folder" else "Save to Shared Folder"

            val pathStack = remember { mutableStateListOf<String>() }
            val dbFilesState by viewModel.files.collectAsState()

            var showCreateFolderDialog by remember { mutableStateOf(false) }
            var newFolderName by remember { mutableStateOf("") }

            // Current directory path derived from stack
            val currentPath = if (pathStack.isEmpty()) "" else pathStack.joinToString("/")

            val defaultRootFolders = remember(isPrivate) {
                if (isPrivate) {
                    listOf("Personal", "Documents", "Vault", "Private Files", "Archive")
                } else {
                    listOf("General", "Friends", "Journal", "Tasks", "Contacts")
                }
            }

            val foldersList = remember(currentPath, dbFilesState, isPrivate) {
                if (currentPath.isEmpty()) {
                    val customRootFolders = dbFilesState.filter {
                        it.mimeType == "inode/directory" && it.path.isEmpty()
                    }.map { it.name }
                    (defaultRootFolders + customRootFolders).distinct()
                } else {
                    dbFilesState.filter {
                        it.mimeType == "inode/directory" && it.path == currentPath
                    }.map { it.name }.distinct()
                }
            }

            val filesList = remember(currentPath, dbFilesState) {
                if (currentPath.isEmpty()) {
                    emptyList()
                } else {
                    dbFilesState.filter {
                        it.mimeType != "inode/directory" && it.path == currentPath
                    }
                }
            }

            Dialog(onDismissRequest = { viewModel.setSharedFileIntent(null) }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.80f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF101014)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Title Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPrivate) Icons.Default.Lock else Icons.Default.FolderShared,
                                    contentDescription = null,
                                    tint = if (isPrivate) Color(0xFF81C784) else WaterBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    flowTitle,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                            }
                            IconButton(onClick = { viewModel.setSharedFileIntent(null) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Path / Breadcrumb with back navigation & Create Folder
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF16161B), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (pathStack.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        tint = if (isPrivate) Color(0xFF81C784) else WaterBlue,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                pathStack.removeLast()
                                            }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = (if (isPrivate) "Private Storage" else "Shared Storage") + (if (currentPath.isEmpty()) " (Root)" else " / $currentPath"),
                                    color = if (isPrivate) Color(0xFF81C784) else WaterBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            // Add Subfolder Button
                            IconButton(
                                onClick = {
                                    newFolderName = ""
                                    showCreateFolderDialog = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "New Folder",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Folder and Files List
                        Box(modifier = Modifier.weight(1f)) {
                            if (foldersList.isEmpty() && filesList.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("This folder is empty. Tap Save below to store files here.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // List Directories
                                    foldersList.forEach { folderName ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF16161B))
                                                .clickable {
                                                    pathStack.add(folderName)
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = "Folder",
                                                tint = if (isPrivate) Color(0xFF81C784) else Color(0xFFFFB74D),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = folderName,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Open",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // List Files
                                    filesList.forEach { file ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF16161B).copy(alpha = 0.4f))
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.InsertDriveFile,
                                                contentDescription = "File",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = file.name,
                                                    color = Color.LightGray,
                                                    fontSize = 12.sp
                                                )
                                                Text(
                                                    text = file.mimeType,
                                                    color = Color.Gray,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = if (isPrivate) Color(0xFF81C784) else WaterBlue)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Bottom Save Button
                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    try {
                                        val urisToProcess = if (sharedIntent.uris.isNotEmpty()) {
                                            sharedIntent.uris.mapIndexed { idx, u ->
                                                val n = sharedIntent.names.getOrNull(idx) ?: "shared_file_$idx"
                                                Pair(u, n)
                                            }
                                        } else if (sharedIntent.uri != Uri.EMPTY) {
                                            listOf(Pair(sharedIntent.uri, sharedIntent.name))
                                        } else {
                                            emptyList()
                                        }

                                        if (urisToProcess.isNotEmpty()) {
                                            urisToProcess.forEach { (fileUri, fileName) ->
                                                viewModel.addFile(
                                                    name = fileName,
                                                    path = currentPath,
                                                    size = 0L,
                                                    mimeType = sharedIntent.mimeType.ifEmpty { "application/octet-stream" },
                                                    uriString = fileUri.toString()
                                                )
                                            }
                                        } else if (!sharedIntent.sharedText.isNullOrBlank()) {
                                            // Save text snippet as a .txt document
                                            val textFile = File(context.cacheDir, "shared_note_${System.currentTimeMillis()}.txt")
                                            textFile.writeText(sharedIntent.sharedText)
                                            val fileUri = Uri.fromFile(textFile)
                                            viewModel.addFile(
                                                name = "Shared_Text_${System.currentTimeMillis()}.txt",
                                                path = currentPath,
                                                size = textFile.length(),
                                                mimeType = "text/plain",
                                                uriString = fileUri.toString()
                                            )
                                        }

                                        val destName = if (currentPath.isEmpty()) "Root Folder" else currentPath
                                        val folderTypeStr = if (isPrivate) "Private Folder" else "Shared Folder"
                                        Toast.makeText(context, "Saved successfully to $folderTypeStr: $destName", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Log.e("SharedFile", "Failed to save file to folder", e)
                                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isProcessing = false
                                        viewModel.setSharedFileIntent(null)
                                    }
                                }
                            },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPrivate) Color(0xFF81C784) else WaterBlue
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isPrivate) "SAVE TO THIS PRIVATE FOLDER" else "SAVE TO THIS SHARED FOLDER",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Create Subfolder Dialog
            if (showCreateFolderDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateFolderDialog = false },
                    title = { Text("Create Folder", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("Folder Name", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = if (isPrivate) Color(0xFF81C784) else WaterBlue,
                                unfocusedBorderColor = Color.Gray
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val trimmed = newFolderName.trim()
                                if (trimmed.isNotEmpty()) {
                                    viewModel.addFile(
                                        name = trimmed,
                                        path = currentPath,
                                        size = 0L,
                                        mimeType = "inode/directory",
                                        uriString = "local_virtual_directory"
                                    )
                                    pathStack.add(trimmed)
                                    showCreateFolderDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPrivate) Color(0xFF81C784) else WaterBlue
                            )
                        ) {
                            Text("Create & Open", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateFolderDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    },
                    containerColor = Color(0xFF16161B),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}
