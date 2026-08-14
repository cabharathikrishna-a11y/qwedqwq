package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter as AndroidColorMatrixColorFilter
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberVideoThumbnail(videoPath: String): ImageBitmap? {
    var bitmap by remember(videoPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(videoPath) {
        if (videoPath.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                if (videoPath.startsWith("http://") || videoPath.startsWith("https://")) {
                    retriever.setDataSource(videoPath, HashMap<String, String>())
                } else {
                    val file = File(videoPath)
                    if (file.exists()) {
                        retriever.setDataSource(videoPath)
                    } else {
                        return@withContext
                    }
                }
                val bmp = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (bmp != null) {
                    bitmap = bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmap
}

@Composable
fun rememberPdfFirstPagePreview(pdfPath: String): ImageBitmap? {
    var bitmap by remember(pdfPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pdfPath) {
        if (pdfPath.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = File(pdfPath)
                if (file.exists() && file.name.endsWith(".pdf", ignoreCase = true)) {
                    val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pdfRenderer = PdfRenderer(fileDescriptor)
                    if (pdfRenderer.pageCount > 0) {
                        val page = pdfRenderer.openPage(0)
                        val scale = 300f / page.width
                        val finalWidth = (page.width * scale).toInt().coerceAtLeast(1)
                        val finalHeight = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp.asImageBitmap()
                        page.close()
                    }
                    pdfRenderer.close()
                    fileDescriptor.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmap
}

fun openWithSystemChooser(
    context: Context,
    filePathOrUri: String,
    mimeType: String? = null,
    title: String? = null
) {
    if (filePathOrUri.isBlank()) {
        android.widget.Toast.makeText(context, "Invalid file path", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri: Uri
        var resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"

        if (filePathOrUri.startsWith("content://")) {
            uri = Uri.parse(filePathOrUri)
            if (resolvedMime == "*/*") {
                val crMime = context.contentResolver.getType(uri)
                if (!crMime.isNullOrEmpty()) {
                    resolvedMime = crMime
                }
            }
        } else {
            val file = if (filePathOrUri.startsWith("file://")) {
                java.io.File(Uri.parse(filePathOrUri).path ?: "")
            } else {
                java.io.File(filePathOrUri)
            }

            if (!file.exists()) {
                android.widget.Toast.makeText(context, "File does not exist: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            uri = try {
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: Exception) {
                try {
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                } catch (e2: Exception) {
                    Uri.fromFile(file)
                }
            }

            if (resolvedMime == "*/*") {
                val extension = file.extension.lowercase()
                val mimeFromExt = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                if (!mimeFromExt.isNullOrEmpty()) {
                    resolvedMime = mimeFromExt
                } else {
                    resolvedMime = when (extension) {
                        "pdf" -> "application/pdf"
                        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "video/*"
                        "mp3", "wav", "m4a", "ogg", "aac", "flac" -> "audio/*"
                        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "image/*"
                        "txt", "log", "json", "csv", "xml", "html", "md" -> "text/*"
                        "doc", "docx" -> "application/msword"
                        "xls", "xlsx" -> "application/vnd.ms-excel"
                        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                        "zip", "rar", "7z", "tar", "gz" -> "application/zip"
                        else -> "*/*"
                    }
                }
            }
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserTitle = if (!title.isNullOrEmpty()) "Open \"$title\" with..." else "Open file with..."
        val chooserIntent = android.content.Intent.createChooser(intent, chooserTitle).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooserIntent)
    } catch (e: Exception) {
        Log.e("OpenWithChooser", "Failed to launch Open With system chooser", e)
        android.widget.Toast.makeText(context, "Could not open file: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun openPdfInSystemApp(context: android.content.Context, file: File) {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File does not exist", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Check if phone has a saved default PDF app
        val packageManager = context.packageManager
        val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val defaultPackageName = resolveInfo?.activityInfo?.packageName

        if (resolveInfo != null && !defaultPackageName.isNullOrEmpty() && defaultPackageName != "android" && !defaultPackageName.contains("resolver")) {
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val chooser = android.content.Intent.createChooser(intent, "Open PDF with...")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } else {
            // No default PDF app set -> ask user which app to open with via system chooser
            val chooser = android.content.Intent.createChooser(intent, "Open PDF with...")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(context, "Error opening PDF: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun openFileInGoogleDrivePdfViewer(context: android.content.Context, file: File) {
    openPdfInSystemApp(context, file)
}

fun sharePdfFile(context: android.content.Context, file: File, title: String = "Share PDF") {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File does not exist", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        }

        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(shareIntent, title).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.e("SharePdf", "Error sharing PDF", e)
        android.widget.Toast.makeText(context, "Error sharing PDF: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun openFileInGoogleDocsOrSheets(context: android.content.Context, file: File, docType: String) {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File does not exist", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val mimeType = when (docType) {
            "word" -> {
                if (file.name.lowercase().endsWith(".doc")) "application/msword" 
                else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            "excel" -> {
                if (file.name.lowercase().endsWith(".xls")) "application/vnd.ms-excel" 
                else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }
            else -> "application/*"
        }

        val targetPackage = when (docType) {
            "word" -> "com.google.android.apps.docs.editors.docs"
            "excel" -> "com.google.android.apps.docs.editors.sheets"
            else -> null
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
        }

        try {
            context.startActivity(intent)
            val appLabel = if (docType == "word") "Google Docs" else "Google Sheets"
            android.widget.Toast.makeText(context, "Opening in $appLabel...", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try {
                fallbackIntent.setPackage("com.google.android.apps.docs")
                context.startActivity(fallbackIntent)
                android.widget.Toast.makeText(context, "Opening with Google Drive...", android.widget.Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                fallbackIntent.setPackage(null)
                val chooser = android.content.Intent.createChooser(fallbackIntent, "Open with...")
                context.startActivity(chooser)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(context, "Error opening file: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewBox(
    pathOrName: String,
    type: String, // "image", "video", "audio", "others"
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cleanPath = remember(pathOrName) {
        when {
            pathOrName.startsWith("photo:") -> pathOrName.removePrefix("photo:")
            pathOrName.startsWith("video:") -> pathOrName.removePrefix("video:")
            pathOrName.startsWith("audio:") -> pathOrName.removePrefix("audio:")
            pathOrName.startsWith("file:") -> {
                val parts = pathOrName.removePrefix("file:").split("|path:")
                parts.getOrNull(1) ?: parts.getOrNull(0) ?: ""
            }
            else -> {
                val internalFile = File(StorageHelper.getAppFilesDir(context), pathOrName)
                if (internalFile.exists()) {
                    internalFile.absolutePath
                } else {
                    pathOrName
                }
            }
        }
    }

    val isWebUrl = remember(cleanPath) { cleanPath.startsWith("http://") || cleanPath.startsWith("https://") }
    val file = remember(cleanPath, isWebUrl) { if (isWebUrl) File("") else File(cleanPath) }

    var showPdfViewer by remember { mutableStateOf(false) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    var showPhotoEditorSelection by remember { mutableStateOf(false) }
    var showPhotoEditor by remember { mutableStateOf(false) }
    var showPhotoViewerOnly by remember { mutableStateOf(false) }
    
    // File change observer to trigger cache busting
    var fileLastModified by remember(cleanPath) { mutableStateOf(if (isWebUrl) 0L else file.lastModified()) }

    Box(
        modifier = modifier
            .clickable {
                when (type) {
                    "image" -> {
                        if (!isWebUrl && file.exists()) {
                            showPhotoEditorSelection = true
                        } else {
                            showPhotoViewerOnly = true
                        }
                    }
                    "video" -> {
                        if (cleanPath.isNotEmpty()) {
                            showVideoPlayer = true
                        }
                    }
                    "pdf" -> {
                        if (cleanPath.isNotEmpty()) {
                            if (isWebUrl && NetworkChecker.isOnline(context)) {
                                showPdfViewer = true
                            } else {
                                openPdfInSystemApp(context, file)
                            }
                        }
                    }
                    "word" -> {
                        if (cleanPath.isNotEmpty()) {
                            openFileInGoogleDocsOrSheets(context, file, "word")
                        }
                    }
                    "excel" -> {
                        if (cleanPath.isNotEmpty()) {
                            openFileInGoogleDocsOrSheets(context, file, "excel")
                        }
                    }
                    else -> {
                        if (cleanPath.endsWith(".pdf", ignoreCase = true) || cleanPath.contains(".pdf", ignoreCase = true) || pathOrName.contains(".pdf", ignoreCase = true)) {
                            if (isWebUrl && NetworkChecker.isOnline(context)) {
                                showPdfViewer = true
                            } else {
                                openPdfInSystemApp(context, file)
                            }
                        } else {
                            // Try opening with standard android system intent
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    file
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/*")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "No app to open this file", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
    ) {
        when (type) {
            "image" -> {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    AsyncImage(
                        model = if (isWebUrl) cleanPath else java.io.File(cleanPath),
                        contentDescription = "Image Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            "video" -> {
                val thumbnailBitmap = rememberVideoThumbnail(cleanPath)
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (thumbnailBitmap != null) {
                            Image(
                                bitmap = thumbnailBitmap,
                                contentDescription = "Video Thumbnail Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF141414)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
            "pdf" -> {
                val pdfBitmap = rememberPdfFirstPagePreview(cleanPath)
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (pdfBitmap != null) {
                            Image(
                                bitmap = pdfBitmap,
                                contentDescription = "PDF Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "PDF",
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "PDF Document",
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            "word" -> {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4285F4).copy(alpha = 0.4f))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B263B))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = "Word Document",
                                tint = Color(0xFF4285F4),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val shortName = cleanPath.substringAfterLast("/")
                            Text(
                                text = shortName,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Word / Google Docs",
                                color = Color.Gray,
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            "excel" -> {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                    border = CardDefaults.outlinedCardBorder(true).copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF0F9D58).copy(alpha = 0.4f))
                    ),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF12281F))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Excel Spreadsheet",
                                tint = Color(0xFF0F9D58),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val shortName = cleanPath.substringAfterLast("/")
                            Text(
                                text = shortName,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Excel / Google Sheets",
                                color = Color.Gray,
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            else -> {
                val isPdf = remember(cleanPath) { cleanPath.endsWith(".pdf", ignoreCase = true) }
                if (isPdf) {
                    val pdfBitmap = rememberPdfFirstPagePreview(cleanPath)
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(8.dp),
                        border = CardDefaults.outlinedCardBorder(true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))
                        ),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F))
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (pdfBitmap != null) {
                                Image(
                                    bitmap = pdfBitmap,
                                    contentDescription = "PDF Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.InsertDriveFile,
                                        contentDescription = "PDF",
                                        tint = Color(0xFFE57373),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "PDF Document",
                                        color = Color.LightGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(8.dp),
                        border = CardDefaults.outlinedCardBorder(true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))
                        ),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22))
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    tint = Color(0xFF2E6FF3),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val shortName = cleanPath.substringAfterLast("/")
                                Text(
                                    text = shortName,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // PDF Viewer Dialog (Native offline rendering)
    if (showPdfViewer && cleanPath.isNotEmpty()) {
        InAppPdfViewerDialog(
            cleanPath = cleanPath,
            isWebUrl = isWebUrl,
            onDismiss = { showPdfViewer = false }
        )
    }

    // Video Player Dialog
    if (showVideoPlayer && cleanPath.isNotEmpty()) {
        VideoPlayerDialog(filePath = cleanPath, onDismiss = { showVideoPlayer = false })
    }

    // Full Photo Viewer (Simple View Only)
    if (showPhotoViewerOnly && cleanPath.isNotEmpty()) {
        Dialog(onDismissRequest = { showPhotoViewerOnly = false }) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = if (isWebUrl) cleanPath else file,
                        contentDescription = "Full Screen Preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = { showPhotoViewerOnly = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }

    // Photo Action Selection Dialog
    if (showPhotoEditorSelection) {
        AlertDialog(
            onDismissRequest = { showPhotoEditorSelection = false },
            containerColor = Color(0xFF13141C),
            title = { Text("Photo Actions", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Would you like to view the photo or open the interactive photo editor?", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        showPhotoEditorSelection = false
                        showPhotoEditor = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6FF3))
                ) {
                    Text("Edit / Crop Photo")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPhotoEditorSelection = false
                        showPhotoViewerOnly = true
                    }
                ) {
                    Text("View Photo", color = Color.White)
                }
            }
        )
    }

    // Full Photo Editor & Cropper
    if (showPhotoEditor && cleanPath.isNotEmpty()) {
        PhotoEditorDialog(
            filePath = cleanPath,
            onDismiss = { showPhotoEditor = false },
            onSaved = {
                fileLastModified = System.currentTimeMillis()
                showPhotoEditor = false
            }
        )
    }
}

// ==========================================
private fun getCleanVideoUriAndName(context: Context, filePath: String): Pair<Uri, String> {
    return try {
        when {
            filePath.startsWith("content://") -> {
                val uri = Uri.parse(filePath)
                var name = "Video File"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex) ?: "Video File"
                        }
                    }
                }
                Pair(uri, name)
            }
            filePath.startsWith("file://") -> {
                val uri = Uri.parse(filePath)
                val name = uri.lastPathSegment ?: "Video File"
                Pair(uri, name)
            }
            filePath.startsWith("http://") || filePath.startsWith("https://") -> {
                val uri = Uri.parse(filePath)
                val name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Network Stream"
                Pair(uri, name)
            }
            else -> {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    Pair(Uri.fromFile(file), file.name)
                } else {
                    Pair(Uri.parse(filePath), java.io.File(filePath).name)
                }
            }
        }
    } catch (e: Exception) {
        Pair(Uri.parse(filePath), "Video File")
    }
}

// ==========================================
// 2. STABLE HIGH-PERFORMANCE VIDEO PLAYER (EXOPLAYER MEDIA3)
// ==========================================

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    filePath: String,
    onToggleFullscreen: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val (videoUri, displayFileName) = remember(filePath) { getCleanVideoUriAndName(context, filePath) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<String?>(null) }

    var isFullscreen by remember { mutableStateOf(false) }
    var isBackgroundPlayEnabled by remember { mutableStateOf(false) }
    var showInspectorDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    // Playback Speed & Aspect Ratio Mode
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Enhancer States
    var videoEnhanceMode by remember { mutableStateOf("AI_HD_CLARITY") }
    var contrastLevel by remember { mutableFloatStateOf(1.25f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.04f) }
    var saturationLevel by remember { mutableFloatStateOf(1.20f) }
    var loudnessGainMb by remember { mutableIntStateOf(1000) } // +10dB Gain
    var bassStrength by remember { mutableIntStateOf(500) } // 50% Bass
    var virtualizerStrength by remember { mutableIntStateOf(400) } // 40% 3D Surround
    var audioPreset by remember { mutableStateOf("VOCAL_SPEECH") } // AI Speech Cleaner
    var showEnhancerStudio by remember { mutableStateOf(false) }

    var audioEffectsManager by remember { mutableStateOf<AvAudioEffectsManager?>(null) }

    // Seeking Notice
    var seekNoticeText by remember { mutableStateOf<String?>(null) }

    // Initialize ExoPlayer Engine
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    // Attach ExoPlayer Listener & Manage Cleanup
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    playerError = null
                } else if (state == Player.STATE_ENDED) {
                    isPlaying = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("VideoPlayerDialog", "ExoPlayer error: ${error.message}", error)
                playerError = error.localizedMessage ?: "Failed to load video stream or unsupported codec."
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    try {
                        val effects = AvAudioEffectsManager(audioSessionId)
                        audioEffectsManager = effects
                        effects.setLoudnessGainMb(loudnessGainMb)
                        effects.setBassStrength(bassStrength)
                        effects.setVirtualizerStrength(virtualizerStrength)
                        effects.setEqualizerPreset(audioPreset)
                    } catch (e: Exception) {
                        Log.e("VideoPlayerDialog", "Error attaching audio effects", e)
                    }
                }
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            audioEffectsManager?.release()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onToggleFullscreen?.invoke(false)
        }
    }

    // Dynamic Audio Effects updates
    LaunchedEffect(loudnessGainMb, bassStrength, virtualizerStrength, audioPreset, audioEffectsManager) {
        audioEffectsManager?.let { eff ->
            eff.setLoudnessGainMb(loudnessGainMb)
            eff.setBassStrength(bassStrength)
            eff.setVirtualizerStrength(virtualizerStrength)
            eff.setEqualizerPreset(audioPreset)
        }
    }

    // Ticker Loop for Progress
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0) {
                duration = exoPlayer.duration
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // Auto-dismiss notice overlay
    LaunchedEffect(seekNoticeText) {
        if (seekNoticeText != null) {
            kotlinx.coroutines.delay(1000)
            seekNoticeText = null
        }
    }

    val handleDismiss = {
        val currentPos = exoPlayer.currentPosition
        if (isBackgroundPlayEnabled && currentPos > 0) {
            com.example.util.BackgroundMediaManager.play(filePath, currentPos.toInt())
            android.widget.Toast.makeText(context, "Continuing video audio in background...", android.widget.Toast.LENGTH_SHORT).show()
        }
        audioEffectsManager?.release()
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDismiss()
    }

    Dialog(
        onDismissRequest = handleDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = !showControls }
            ) {
                // Video Player Container
                Box(
                    modifier = (if (isFullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .align(Alignment.Center)
                    }).drawWithContent {
                        if (videoEnhanceMode != "OFF") {
                            val matrix = buildEnhancerColorMatrix(videoEnhanceMode, contrastLevel, brightnessLevel, saturationLevel)
                            val androidCM = android.graphics.ColorMatrix(matrix.values)
                            val filter = android.graphics.ColorMatrixColorFilter(androidCM)
                            val paint = Paint().apply {
                                asFrameworkPaint().colorFilter = filter
                            }
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint)
                                drawContent()
                                canvas.restore()
                            }
                        } else {
                            drawContent()
                        }
                    }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                this.resizeMode = resizeMode
                            }
                        },
                        update = { playerView ->
                            playerView.player = exoPlayer
                            playerView.resizeMode = resizeMode
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (videoEnhanceMode == "EYE_COMFORT") {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color(0xFFFFB74D).copy(alpha = 0.12f))
                        )
                    }

                    if (videoEnhanceMode != "OFF") {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0xFFFFD700).copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(13.dp))
                            val activeLabel = when (videoEnhanceMode) {
                                "AI_HD_CLARITY" -> "AI HD CLARITY (+10dB VOL)"
                                "VIBRANT_HDR" -> "VIBRANT HDR BOOST"
                                "NIGHT_VISION" -> "NIGHT VISION BOOST"
                                "EYE_COMFORT" -> "EYE COMFORT MODE"
                                else -> "CUSTOM ENHANCED"
                            }
                            Text(activeLabel, color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Seek Notice Overlay
                    if (seekNoticeText != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Text(seekNoticeText!!, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Buffering Indicator
                    if (isBuffering && playerError == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFF60A5FA)
                        )
                    }

                    // Error Card Overlay
                    if (playerError != null) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                                .background(Color(0xFF1F2937), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(40.dp))
                            Text("Video Playback Error", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                playerError!!,
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        playerError = null
                                        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                                        exoPlayer.prepare()
                                        exoPlayer.playWhenReady = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6FF3))
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry")
                                }
                                OutlinedButton(
                                    onClick = {
                                        openWithSystemChooser(context, filePath, "video/*", displayFileName)
                                    }
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open External App", color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Controls Overlay (Toolbar + Bottom Controls)
                if (showControls) {
                    // Top Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = handleDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = displayFileName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )

                        // A/V Enhancer Studio
                        IconButton(onClick = { showEnhancerStudio = true }) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "A/V Enhancers", tint = Color(0xFFFFD700))
                        }

                        // Open With
                        IconButton(onClick = { openWithSystemChooser(context, filePath, "video/*", displayFileName) }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open With", tint = Color(0xFFFFB74D))
                        }

                        // Media Inspector
                        IconButton(onClick = { showInspectorDialog = true }) {
                            Icon(Icons.Default.Analytics, contentDescription = "Inspect Media", tint = Color(0xFF64B5F6))
                        }

                        // Background Play
                        IconButton(onClick = {
                            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
                            val status = if (isBackgroundPlayEnabled) "Enabled" else "Disabled"
                            android.widget.Toast.makeText(context, "Background Audio: $status", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = if (isBackgroundPlayEnabled) Icons.Default.Headset else Icons.Default.HeadsetOff,
                                contentDescription = "Background Play",
                                tint = if (isBackgroundPlayEnabled) Color(0xFF4CAF50) else Color.White
                            )
                        }
                    }

                    // Bottom Controls Bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Time Slider Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                                onValueChange = { percent ->
                                    val target = (percent * duration).toLong()
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF3B82F6),
                                    activeTrackColor = Color(0xFF3B82F6),
                                    inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
                                )
                            )
                            Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                        }

                        // Main Control Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Aspect Ratio Mode Toggle
                            TextButton(onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                val modeLabel = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "FILL"
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
                                    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "FIXED WIDTH"
                                    else -> "FIT"
                                }
                                seekNoticeText = "Aspect Ratio: $modeLabel"
                            }) {
                                val label = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Width"
                                    else -> "Fit"
                                }
                                Text(label, color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // Central Playback Controls
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Rewind 10s
                                IconButton(onClick = {
                                    val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                    seekNoticeText = "-10s"
                                }) {
                                    Icon(Icons.Default.FastRewind, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(28.dp))
                                }

                                // Play / Pause
                                IconButton(
                                    onClick = {
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                        } else {
                                            exoPlayer.play()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2563EB))
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                // Forward 10s
                                IconButton(onClick = {
                                    val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                                    exoPlayer.seekTo(target)
                                    currentPosition = target
                                    seekNoticeText = "+10s"
                                }) {
                                    Icon(Icons.Default.FastForward, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                            }

                            // Playback Speed Selector (Up to 3x)
                            Box {
                                TextButton(
                                    onClick = { showSpeedMenu = true }
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false },
                                    modifier = Modifier
                                        .background(Color(0xFF1F2937))
                                        .border(1.dp, Color(0xFF374151), RoundedCornerShape(8.dp))
                                ) {
                                    val speedOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f)
                                    speedOptions.forEach { spd ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                                                    color = if (spd == playbackSpeed) Color(0xFFFBBF24) else Color.White,
                                                    fontWeight = if (spd == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp
                                                )
                                            },
                                            onClick = {
                                                playbackSpeed = spd
                                                exoPlayer.setPlaybackSpeed(spd)
                                                seekNoticeText = "Speed: ${spd}x"
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Fullscreen Toggle
                            IconButton(onClick = {
                                isFullscreen = !isFullscreen
                                onToggleFullscreen?.invoke(isFullscreen)
                                activity?.requestedOrientation = if (isFullscreen) {
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                } else {
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            }) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInspectorDialog) {
        com.example.ui.components.MediaInspectorDialog(
            mediaPath = filePath,
            onDismiss = { showInspectorDialog = false }
        )
    }

    if (showEnhancerStudio) {
        AvQualityEnhancerDialog(
            videoEnhanceMode = videoEnhanceMode,
            onVideoEnhanceModeChange = { videoEnhanceMode = it },
            contrastLevel = contrastLevel,
            onContrastChange = { contrastLevel = it },
            brightnessLevel = brightnessLevel,
            onBrightnessChange = { brightnessLevel = it },
            saturationLevel = saturationLevel,
            onSaturationChange = { saturationLevel = it },
            loudnessGainMb = loudnessGainMb,
            onLoudnessGainChange = { loudnessGainMb = it },
            bassStrength = bassStrength,
            onBassStrengthChange = { bassStrength = it },
            virtualizerStrength = virtualizerStrength,
            onVirtualizerStrengthChange = { virtualizerStrength = it },
            audioPreset = audioPreset,
            onAudioPresetChange = { audioPreset = it },
            isVideoPlayer = true,
            onDismiss = { showEnhancerStudio = false }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

// ==========================================
// 3. INBUILT PHOTO EDITOR AND CROPPER
// ==========================================

enum class EditTab { ADJUST, FILTERS, DOODLE, CROP }

data class DoodleStroke(
    val points: List<Offset>,
    val color: Color,
    val width: Float
)

@Composable
fun PhotoEditorDialog(
    filePath: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var workingBitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    var editTab by remember { mutableStateOf(EditTab.ADJUST) }

    // Color matrix sliders
    var brightness by remember { mutableStateOf(1.0f) } // 0.5f to 1.5f
    var contrast by remember { mutableStateOf(1.0f) }   // 0.5f to 1.5f
    var saturation by remember { mutableStateOf(1.0f) } // 0.0f to 2.0f

    // Filters
    var activeFilter by remember { mutableStateOf("None") }

    // Doodle state
    var doodleColor by remember { mutableStateOf(Color.Red) }
    var doodleBrushWidth by remember { mutableStateOf(8f) }
    val doodleStrokes = remember { mutableStateListOf<DoodleStroke>() }
    val currentPoints = remember { mutableStateListOf<Offset>() }

    // Gestures inside Crop Mode
    var imageScale by remember { mutableStateOf(1f) }
    var imageTranslation by remember { mutableStateOf(Offset.Zero) }

    // Initial load
    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val orig = BitmapFactory.decodeFile(filePath)
                if (orig != null) {
                    workingBitmap = orig.copy(Bitmap.Config.ARGB_8888, true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val finalColorMatrix = remember(brightness, contrast, saturation, activeFilter) {
        val cm = android.graphics.ColorMatrix()
        // Brightness scale
        cm.setScale(brightness, brightness, brightness, 1f)

        // Contrast adjustment
        val scale = contrast
        val translate = 128f * (1f - scale)
        val contrastMatrix = floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(android.graphics.ColorMatrix(contrastMatrix))

        // Saturation adjustment
        val satMat = android.graphics.ColorMatrix()
        satMat.setSaturation(saturation)
        cm.postConcat(satMat)

        // Pre-defined Filter overlays
        when (activeFilter) {
            "Grayscale" -> {
                val gray = android.graphics.ColorMatrix()
                gray.setSaturation(0f)
                cm.postConcat(gray)
            }
            "Sepia" -> {
                val sepiaMatrix = floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                cm.postConcat(android.graphics.ColorMatrix(sepiaMatrix))
            }
            "Invert" -> {
                val invertMatrix = floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
                cm.postConcat(android.graphics.ColorMatrix(invertMatrix))
            }
            "Vintage" -> {
                val vintageMatrix = floatArrayOf(
                    0.9f, 0.3f, 0.15f, 0f, 0f,
                    0.15f, 0.9f, 0.2f, 0f, 0f,
                    0.15f, 0.15f, 0.9f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                cm.postConcat(android.graphics.ColorMatrix(vintageMatrix))
            }
            "Warm" -> {
                val warmMatrix = floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                cm.postConcat(android.graphics.ColorMatrix(warmMatrix))
            }
            "Cool" -> {
                val coolMatrix = floatArrayOf(
                    0.8f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.2f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                cm.postConcat(android.graphics.ColorMatrix(coolMatrix))
            }
        }

        androidx.compose.ui.graphics.ColorMatrix(cm.array)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF090A0F)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF13141C))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Text("Photo Studio Editor", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(
                        onClick = {
                            workingBitmap?.let { bmp ->
                                // Trigger save routine in background
                                val out = File(filePath)
                                try {
                                    val finalSaved = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(finalSaved)
                                    
                                    // 1. Draw image with adjustments & filters applied
                                    val cmPaint = android.graphics.Paint().apply {
                                        val arr = finalColorMatrix.values
                                        val androidCM = android.graphics.ColorMatrix(arr)
                                        colorFilter = android.graphics.ColorMatrixColorFilter(androidCM)
                                    }
                                    canvas.drawBitmap(bmp, 0f, 0f, cmPaint)

                                    // 2. Draw doodles mapped to bitmap coordinates
                                    val doodlePaint = android.graphics.Paint().apply {
                                        isAntiAlias = true
                                        style = android.graphics.Paint.Style.STROKE
                                        strokeCap = android.graphics.Paint.Cap.ROUND
                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                    }
                                    doodleStrokes.forEach { stroke ->
                                        doodlePaint.color = stroke.color.toArgb()
                                        doodlePaint.strokeWidth = stroke.width * (bmp.width / 400f) // Scale stroke size relative to canvas preview W=400dp
                                        val path = android.graphics.Path()
                                        if (stroke.points.isNotEmpty()) {
                                            path.moveTo(stroke.points.first().x * (bmp.width / 400f), stroke.points.first().y * (bmp.height / 300f))
                                            for (i in 1 until stroke.points.size) {
                                                path.lineTo(stroke.points[i].x * (bmp.width / 400f), stroke.points[i].y * (bmp.height / 300f))
                                            }
                                            canvas.drawPath(path, doodlePaint)
                                        }
                                    }

                                    // 3. Write directly to original path
                                    val fos = FileOutputStream(out)
                                    finalSaved.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                                    fos.flush()
                                    fos.close()
                                    onSaved()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    android.widget.Toast.makeText(context, "Failed to save edits", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Edits", tint = Color(0xFF2E6FF3))
                    }
                }

                // Image Preview Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (workingBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(width = 400.dp, height = 300.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        ) {
                            if (editTab == EditTab.CROP) {
                                // Crop Mode Gestures Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF141414))
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                imageScale = (imageScale * zoom).coerceIn(0.5f, 4f)
                                                imageTranslation += pan
                                            }
                                        }
                                ) {
                                    Image(
                                        bitmap = workingBitmap!!.asImageBitmap(),
                                        contentDescription = "Editing Image",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = imageScale,
                                                scaleY = imageScale,
                                                translationX = imageTranslation.x,
                                                translationY = imageTranslation.y
                                            ),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(finalColorMatrix)
                                    )

                                    // Bounding Box Frame overlay for crop
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val boxSize = 200.dp.toPx()
                                        val left = (size.width - boxSize) / 2
                                        val top = (size.height - boxSize) / 2
                                        val right = left + boxSize
                                        val bottom = top + boxSize

                                        // Draw outside darkened region
                                        drawRect(
                                            color = Color.Black.copy(alpha = 0.6f),
                                            size = size
                                        )
                                        // Blend in-center transparent hole
                                        drawIntoCanvas { canvas ->
                                            val paint = android.graphics.Paint().apply {
                                                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                                            }
                                            canvas.nativeCanvas.drawRect(left, top, right, bottom, paint)
                                        }
                                        // Draw white frame lines
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset(left, top),
                                            size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }
                            } else {
                                // Regular adjust, filters, or doodle draw mode
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Image(
                                        bitmap = workingBitmap!!.asImageBitmap(),
                                        contentDescription = "Editing Image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(finalColorMatrix)
                                    )

                                    // Doodle Overlay
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(editTab) {
                                                if (editTab == EditTab.DOODLE) {
                                                    detectDragGestures(
                                                        onDragStart = { offset ->
                                                            currentPoints.add(offset)
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            currentPoints.add(change.position)
                                                        },
                                                        onDragEnd = {
                                                            doodleStrokes.add(DoodleStroke(currentPoints.toList(), doodleColor, doodleBrushWidth))
                                                            currentPoints.clear()
                                                        }
                                                    )
                                                }
                                            }
                                    ) {
                                        // Draw historic strokes
                                        doodleStrokes.forEach { stroke ->
                                            if (stroke.points.size > 1) {
                                                for (i in 0 until stroke.points.size - 1) {
                                                    drawLine(
                                                        color = stroke.color,
                                                        start = stroke.points[i],
                                                        end = stroke.points[i + 1],
                                                        strokeWidth = stroke.width,
                                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                                    )
                                                }
                                            }
                                        }
                                        // Draw active line currently drawing
                                        if (currentPoints.size > 1) {
                                            for (i in 0 until currentPoints.size - 1) {
                                                drawLine(
                                                    color = doodleColor,
                                                    start = currentPoints[i],
                                                    end = currentPoints[i + 1],
                                                    strokeWidth = doodleBrushWidth,
                                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        CircularProgressIndicator(color = Color(0xFF2E6FF3))
                    }
                }

                // Control Center & Tabs
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF13141C))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (editTab) {
                        EditTab.ADJUST -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Brightness Slider
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Brightness", color = Color.White, fontSize = 12.sp)
                                        Text(String.format("%.2f", brightness), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = brightness,
                                        onValueChange = { brightness = it },
                                        valueRange = 0.5f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFF2E6FF3), activeTrackColor = Color(0xFF2E6FF3))
                                    )
                                }
                                // Contrast Slider
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Contrast", color = Color.White, fontSize = 12.sp)
                                        Text(String.format("%.2f", contrast), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = contrast,
                                        onValueChange = { contrast = it },
                                        valueRange = 0.5f..1.5f,
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFF2E6FF3), activeTrackColor = Color(0xFF2E6FF3))
                                    )
                                }
                                // Saturation Slider
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Saturation", color = Color.White, fontSize = 12.sp)
                                        Text(String.format("%.2f", saturation), color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = saturation,
                                        onValueChange = { saturation = it },
                                        valueRange = 0.0f..2.0f,
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFF2E6FF3), activeTrackColor = Color(0xFF2E6FF3))
                                    )
                                }
                            }
                        }
                        EditTab.FILTERS -> {
                            val filtersList = listOf("None", "Grayscale", "Sepia", "Invert", "Vintage", "Warm", "Cool")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filtersList.forEach { filterName ->
                                    val isSelected = filterName == activeFilter
                                    Button(
                                        onClick = { activeFilter = filterName },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) Color(0xFF2E6FF3) else Color.White.copy(alpha = 0.1f)
                                        ),
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text(filterName, color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        EditTab.DOODLE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Color row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Brush Color", color = Color.White, fontSize = 12.sp)
                                    val colorsList = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.White, Color.Black)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        colorsList.forEach { color ->
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .border(
                                                        width = if (doodleColor == color) 2.dp else 0.dp,
                                                        color = Color.White,
                                                        shape = CircleShape
                                                    )
                                                    .clickable { doodleColor = color }
                                            )
                                        }
                                    }
                                }
                                // Size slider
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Brush Width", color = Color.White, fontSize = 12.sp)
                                        Text("${doodleBrushWidth.toInt()}px", color = Color.Gray, fontSize = 12.sp)
                                    }
                                    Slider(
                                        value = doodleBrushWidth,
                                        onValueChange = { doodleBrushWidth = it },
                                        valueRange = 2f..24f,
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFF2E6FF3), activeTrackColor = Color(0xFF2E6FF3))
                                    )
                                }
                                // Undo and Clear buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { if (doodleStrokes.isNotEmpty()) doodleStrokes.removeAt(doodleStrokes.lastIndex) },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                                    ) {
                                        Text("Undo", color = Color.White, fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = { doodleStrokes.clear() },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f))
                                    ) {
                                        Text("Clear", color = Color.Red, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        EditTab.CROP -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pinch & Position image in central box", color = Color.LightGray, fontSize = 12.sp)
                                Button(
                                    onClick = {
                                        workingBitmap?.let { bmp ->
                                            try {
                                                // Calculate sub-rect mapping from coordinate screen space
                                                // Center mapping crop calculations
                                                val scaledWidth = bmp.width * imageScale
                                                val scaledHeight = bmp.height * imageScale
                                                
                                                // Coordinates relative to centering
                                                val cropW = (bmp.width / imageScale).toInt().coerceIn(10, bmp.width)
                                                val cropH = (bmp.height / imageScale).toInt().coerceIn(10, bmp.height)

                                                val leftX = (((bmp.width - cropW) / 2) - (imageTranslation.x / imageScale)).toInt().coerceIn(0, bmp.width - 1)
                                                val topY = (((bmp.height - cropH) / 2) - (imageTranslation.y / imageScale)).toInt().coerceIn(0, bmp.height - 1)
                                                val finalW = cropW.coerceAtMost(bmp.width - leftX)
                                                val finalH = cropH.coerceAtMost(bmp.height - topY)

                                                if (finalW > 0 && finalH > 0) {
                                                    val cropped = Bitmap.createBitmap(bmp, leftX, topY, finalW, finalH)
                                                    workingBitmap = cropped
                                                    imageScale = 1f
                                                    imageTranslation = Offset.Zero
                                                    android.widget.Toast.makeText(context, "Image Cropped", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                android.widget.Toast.makeText(context, "Unable to crop this ratio", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6FF3)),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Apply Crop", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f))

                    // Base Rotator/Flipper and Tab Navigation Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Transform helpers
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    workingBitmap?.let { bmp ->
                                        val mat = android.graphics.Matrix().apply { postRotate(90f) }
                                        workingBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90", tint = Color.White)
                            }
                            IconButton(
                                onClick = {
                                    workingBitmap?.let { bmp ->
                                        val mat = android.graphics.Matrix().apply { postScale(-1f, 1f) }
                                        workingBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mat, true)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Flip, contentDescription = "Flip Horizontal", tint = Color.White)
                            }
                        }

                        // Tab switches
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                EditTab.ADJUST to Icons.Default.Tune,
                                EditTab.FILTERS to Icons.Default.FilterList,
                                EditTab.DOODLE to Icons.Default.Brush,
                                EditTab.CROP to Icons.Default.Crop
                            ).forEach { (tab, icon) ->
                                val isSelected = editTab == tab
                                IconButton(
                                    onClick = { editTab = tab },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF2E6FF3) else Color.Transparent)
                                ) {
                                    Icon(icon, contentDescription = tab.name, tint = if (isSelected) Color.White else Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// ==================== CONSOLIDATED FROM: BackgroundMediaManager.kt ====================
object BackgroundMediaManager {
    private val TAG = "BackgroundMediaManager"
    private var mediaPlayer: MediaPlayer? = null

    private val _currentPlayingPath = MutableStateFlow<String?>(null)
    val currentPlayingPath: StateFlow<String?> = _currentPlayingPath

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    fun play(path: String, startOffsetMs: Int = 0, onComplete: () -> Unit = {}) {
        try {
            stop()
            _currentPlayingPath.value = path
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { mp ->
                    if (startOffsetMs > 0) {
                        mp.seekTo(startOffsetMs)
                    }
                    mp.start()
                    _isPlaying.value = true
                    Log.d(TAG, "Started playback for: $path from: ${startOffsetMs}ms")
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPlayingPath.value = null
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer Error: what=$what, extra=$extra")
                    _isPlaying.value = false
                    _currentPlayingPath.value = null
                    stop()
                    true
                }
                prepareAsync()
            }
            Log.d(TAG, "Preparing media asynchronously for: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing media: $path", e)
            _isPlaying.value = false
            _currentPlayingPath.value = null
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    _isPlaying.value = false
                    Log.d(TAG, "Paused playback")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing media", e)
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    _isPlaying.value = true
                    Log.d(TAG, "Resumed playback")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming media", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) {
                        it.stop()
                    }
                } catch (ex: IllegalStateException) {
                    // Ignore state conflict during stop
                }
                it.release()
            }
            mediaPlayer = null
            _currentPlayingPath.value = null
            _isPlaying.value = false
            Log.d(TAG, "Stopped and released previous MediaPlayer")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media", e)
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }
}


// ==================== CONSOLIDATED FROM: MediaCompressionHelper.kt ====================
/**
 * A client-side helper to compress and optimize images memory-safely
 * before they are stored or transferred, preventing excessive memory usage
 * and storage bloat.
 */
object MediaCompressionHelper {
    private const val TAG = "MediaCompressionHelper"

    /**
     * Compresses an existing image file in-place or returns the optimized file.
     * Prevents out-of-memory issues by downscaling large pictures.
     */
    fun compressImageFile(context: Context, sourceFile: File, maxDimension: Int = 2560, quality: Int = 95): File {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return sourceFile

        val name = sourceFile.name.lowercase()
        if (!name.endsWith(".jpg") && !name.endsWith(".jpeg") && !name.endsWith(".png") && !name.endsWith(".webp")) {
            return sourceFile // Keep other files as-is
        }

        try {
            // Phase 1: Determine dimensions without loading bitmap into memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, options)

            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return sourceFile

            // Phase 2: Compute sample size
            var sampleSize = 1
            while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            // Phase 3: Decode bitmap safely
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return sourceFile

            // Phase 4: Output compressed JPEG bytes to a temporary cache file
            val tempCompressed = File(context.cacheDir, "cmp_${System.currentTimeMillis()}_${sourceFile.name}")
            FileOutputStream(tempCompressed).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            }
            bitmap.recycle()

            // Phase 5: Swap files if compressed result is smaller
            if (tempCompressed.exists() && tempCompressed.length() < sourceFile.length()) {
                Log.d(TAG, "Compressed: ${sourceFile.name} (${sourceFile.length()} -> ${tempCompressed.length()} bytes)")
                sourceFile.delete()
                tempCompressed.renameTo(sourceFile)
                return sourceFile
            } else {
                tempCompressed.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image file: ${sourceFile.name}", e)
        }
        return sourceFile
    }

    /**
     * Read from image Uri, downscale, and compress directly into target destination file memory-safely.
     */
    fun compressImageFromUri(context: Context, uri: Uri, destFile: File, maxDimension: Int = 2560, quality: Int = 95): Boolean {
        return try {
            val resolver = context.contentResolver

            // Phase 1: Read bounds
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) return false

            // Phase 2: Compute sample scale
            var sampleSize = 1
            while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            // Phase 3: Decode bitmap with sampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return false

            // Phase 4: Save compressed format
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
            }
            bitmap.recycle()

            Log.d(TAG, "Successfully compressed uri image into: ${destFile.name} (${destFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing Uri image: $uri", e)
            false
        }
    }

    /**
     * Web Compression API equivalent: Compresses a file using the GZIP format.
     * Memory-safe streaming implementation avoiding load of full files into RAM.
     */
    fun compressFileGzip(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        return try {
            destination.parentFile?.mkdirs()
            java.io.FileInputStream(source).use { fileIn ->
                java.io.FileOutputStream(destination).use { fileOut ->
                    java.util.zip.GZIPOutputStream(fileOut).use { gzipOut ->
                        val buffer = ByteArray(8192)
                        var bytesRead = fileIn.read(buffer)
                        while (bytesRead != -1) {
                            gzipOut.write(buffer, 0, bytesRead)
                            bytesRead = fileIn.read(buffer)
                        }
                    }
                }
            }
            Log.d(TAG, "GZIP Compressed: ${source.name} (${source.length()} -> ${destination.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "GZIP Compression failed for: ${source.name}", e)
            false
        }
    }

    /**
     * Web Compression API equivalent: Decompresses a GZIP-compressed file back to its original raw form.
     */
    fun decompressFileGzip(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        return try {
            destination.parentFile?.mkdirs()
            java.io.FileInputStream(source).use { fileIn ->
                java.util.zip.GZIPInputStream(fileIn).use { gzipIn ->
                    java.io.FileOutputStream(destination).use { fileOut ->
                        val buffer = ByteArray(8192)
                        var bytesRead = gzipIn.read(buffer)
                        while (bytesRead != -1) {
                            fileOut.write(buffer, 0, bytesRead)
                            bytesRead = gzipIn.read(buffer)
                        }
                    }
                }
            }
            Log.d(TAG, "GZIP Decompressed: ${source.name} to ${destination.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "GZIP Decompression failed for: ${source.name}", e)
            false
        }
    }

    /**
     * Web Compression API equivalent: Compresses a file using DEFLATE (zlib wrapper) format.
     */
    fun compressFileDeflate(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        return try {
            destination.parentFile?.mkdirs()
            java.io.FileInputStream(source).use { fileIn ->
                java.io.FileOutputStream(destination).use { fileOut ->
                    java.util.zip.DeflaterOutputStream(fileOut).use { deflateOut ->
                        val buffer = ByteArray(8192)
                        var bytesRead = fileIn.read(buffer)
                        while (bytesRead != -1) {
                            deflateOut.write(buffer, 0, bytesRead)
                            bytesRead = fileIn.read(buffer)
                        }
                    }
                }
            }
            Log.d(TAG, "DEFLATE Compressed: ${source.name} (${source.length()} -> ${destination.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DEFLATE Compression failed for: ${source.name}", e)
            false
        }
    }

    /**
     * Web Compression API equivalent: Decompresses a DEFLATE-compressed file back to original form.
     */
    fun decompressFileDeflate(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        return try {
            destination.parentFile?.mkdirs()
            java.io.FileInputStream(source).use { fileIn ->
                java.util.zip.InflaterInputStream(fileIn).use { inflateIn ->
                    java.io.FileOutputStream(destination).use { fileOut ->
                        val buffer = ByteArray(8192)
                        var bytesRead = inflateIn.read(buffer)
                        while (bytesRead != -1) {
                            fileOut.write(buffer, 0, bytesRead)
                            bytesRead = inflateIn.read(buffer)
                        }
                    }
                }
            }
            Log.d(TAG, "DEFLATE Decompressed: ${source.name} to ${destination.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DEFLATE Decompression failed for: ${source.name}", e)
            false
        }
    }

    /**
     * Checks if a file has a GZIP magic header (signature is 0x1f8b in big endian or little endian bytes).
     */
    fun isGzipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 2) return false
        return try {
            java.io.FileInputStream(file).use { fileIn ->
                val b1 = fileIn.read()
                val b2 = fileIn.read()
                b1 == 0x1F && b2 == 0x8B
            }
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
fun InAppPdfViewerDialog(
    cleanPath: String,
    isWebUrl: Boolean = false,
    autoCompress: Boolean = false,
    askCompressOrView: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var currentActivePath by remember(cleanPath) { mutableStateOf(cleanPath) }
    var pdfRenderer by remember { mutableStateOf<android.graphics.pdf.PdfRenderer?>(null) }
    var parcelDescriptor by remember { mutableStateOf<android.os.ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var isCompressing by remember { mutableStateOf(false) }
    var shouldAutoCompress by remember(autoCompress) { mutableStateOf(autoCompress) }
    var showActionChooserDialog by remember(askCompressOrView) { mutableStateOf(askCompressOrView) }
    var compressionProgressText by remember { mutableStateOf("Compressing PDF below 5 MB...") }
    var compressionPageProgress by remember { mutableStateOf(0 to 0) }
    var compressionDetailStats by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pdfFileName by remember { mutableStateOf("PDF Document") }
    var scale by remember { mutableFloatStateOf(1.0f) }
    var resolvedFile by remember { mutableStateOf<java.io.File?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpPageInput by remember { mutableStateOf("") }
    var isFullScreen by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var bookmarks by remember(currentActivePath) { mutableStateOf(setOf<Int>()) }

    val bmPrefs = remember(context) { context.getSharedPreferences("pdf_bookmarks_pref", android.content.Context.MODE_PRIVATE) }
    val bmKey = remember(currentActivePath) { "bm_${currentActivePath.hashCode()}" }

    LaunchedEffect(currentActivePath) {
        val savedSet = bmPrefs.getStringSet(bmKey, emptySet()) ?: emptySet()
        bookmarks = savedSet.mapNotNull { it.toIntOrNull() }.toSet()
    }

    val toggleBookmark: (Int) -> Unit = { pageIndex ->
        val updated = if (bookmarks.contains(pageIndex)) bookmarks - pageIndex else bookmarks + pageIndex
        bookmarks = updated
        bmPrefs.edit().putStringSet(bmKey, updated.map { it.toString() }.toSet()).apply()
        val isNowBookmarked = updated.contains(pageIndex)
        android.widget.Toast.makeText(
            context,
            if (isNowBookmarked) "Page ${pageIndex + 1} bookmarked" else "Bookmark removed for Page ${pageIndex + 1}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }


    DisposableEffect(currentActivePath) {
        onDispose {
            try {
                pdfRenderer?.close()
                parcelDescriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(currentActivePath) {
        isLoading = true
        errorMessage = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                var targetFile: java.io.File? = null

                if (currentActivePath.startsWith("content://")) {
                    try {
                        val uri = android.net.Uri.parse(currentActivePath)
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1 && cursor.moveToFirst()) {
                                    val displayName = cursor.getString(nameIdx)
                                    if (!displayName.isNullOrEmpty()) {
                                        pdfFileName = displayName
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        val hash = currentActivePath.hashCode().toString()
                        val cacheFile = java.io.File(context.cacheDir, "pdf_cache_$hash.pdf")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (cacheFile.exists() && cacheFile.length() > 0) {
                            targetFile = cacheFile
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewer", "Failed resolving content:// URI to cache", e)
                    }
                } else if (currentActivePath.startsWith("file://")) {
                    try {
                        val filePath = android.net.Uri.parse(currentActivePath).path
                        if (!filePath.isNullOrEmpty()) {
                            val f = java.io.File(filePath)
                            if (f.exists() && f.length() > 0) {
                                targetFile = f
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (!isWebUrl) {
                    val f = java.io.File(currentActivePath)
                    if (f.exists() && f.length() > 0) {
                        targetFile = f
                    }
                }

                if (targetFile == null || !targetFile.exists() || targetFile.length() == 0L) {
                    val hash = currentActivePath.hashCode().toString()
                    val cacheFile = java.io.File(context.cacheDir, "pdf_cache_$hash.pdf")
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        targetFile = cacheFile
                    } else if (isWebUrl || currentActivePath.startsWith("http://") || currentActivePath.startsWith("https://") || com.example.util.DriveUrlUtil.isCloudPdfUrl(currentActivePath)) {
                        try {
                            val directUrlStr = com.example.util.DriveUrlUtil.toDirectDownloadUrl(currentActivePath)
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                            val request = okhttp3.Request.Builder()
                                .url(directUrlStr)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                .header("Accept", "application/pdf,application/octet-stream,*/*")
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    response.body?.byteStream()?.use { input ->
                                        cacheFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    if (cacheFile.exists() && cacheFile.length() > 0) {
                                        targetFile = cacheFile
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("PdfViewer", "Failed downloading web PDF", e)
                        }
                    }
                }

                val fileToRender = targetFile
                if (fileToRender != null && fileToRender.exists() && fileToRender.length() > 0) {
                    resolvedFile = fileToRender
                    if (pdfFileName == "PDF Document" || pdfFileName.startsWith("pdf_cache_")) {
                        pdfFileName = fileToRender.name
                    }
                    val pfd = android.os.ParcelFileDescriptor.open(fileToRender, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = android.graphics.pdf.PdfRenderer(pfd)

                    parcelDescriptor = pfd
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    isLoading = false
                } else {
                    errorMessage = "PDF file is not available."
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e("PdfViewer", "Error opening PDF", e)
                errorMessage = "Unable to open PDF: ${e.localizedMessage}"
                isLoading = false
            }
        }
    }

    // Auto-compress logic if opened via Life OS Compressor target
    LaunchedEffect(resolvedFile, shouldAutoCompress) {
        val fileToCompress = resolvedFile
        if (shouldAutoCompress && fileToCompress != null && fileToCompress.exists() && !isCompressing) {
            shouldAutoCompress = false
            isCompressing = true
            val origMb = String.format(java.util.Locale.US, "%.2f MB", fileToCompress.length() / (1024.0 * 1024.0))
            compressionDetailStats = "Original: $origMb • Target: < 5.00 MB"
            try {
                val result = com.example.util.PdfCompressorHelper.compressPdf(
                    context = context,
                    inputSource = fileToCompress,
                    targetMaxSizeBytes = 5 * 1024 * 1024L,
                    onProgress = { cur, total, msg ->
                        compressionPageProgress = cur to total
                        compressionProgressText = msg
                    }
                )
                val compMb = String.format(java.util.Locale.US, "%.2f MB", result.compressedSizeBytes / (1024.0 * 1024.0))
                val summaryText = "Compressed: $origMb ➔ $compMb (${result.reductionPercentage}% smaller)"
                compressionDetailStats = summaryText

                try {
                    val pdfRepo = com.example.pdf.PdfStorageRepository(context)
                    val item = com.example.pdf.PdfDocumentItem(
                        id = java.util.UUID.randomUUID().toString(),
                        title = result.outputFile.name,
                        uriString = result.outputFile.absolutePath,
                        fileSizeFormatted = compMb,
                        pageCount = pageCount
                    )
                    pdfRepo.addOrUpdatePdf(item)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                android.widget.Toast.makeText(context, summaryText, android.widget.Toast.LENGTH_LONG).show()

                // Auto open and view the compressed copy!
                currentActivePath = result.outputFile.absolutePath
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Compression failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                isCompressing = false
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF13141C)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isFullScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1F2B))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "PDF",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = pdfFileName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (pageCount > 0) {
                                    Text(
                                        text = "$pageCount Pages • ${(scale * 100).toInt()}% Zoom",
                                        color = Color.LightGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Bookmark Current Page Button
                            val currentVisibleIndex = listState.firstVisibleItemIndex
                            val isCurrentBookmarked = bookmarks.contains(currentVisibleIndex)
                            IconButton(onClick = { toggleBookmark(currentVisibleIndex) }) {
                                Icon(
                                    imageVector = if (isCurrentBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark Current Page",
                                    tint = if (isCurrentBookmarked) Color(0xFFFFD700) else Color.White
                                )
                            }

                            // Bookmarks List Dialog Trigger
                            Box {
                                IconButton(onClick = { showBookmarksDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Bookmarks,
                                        contentDescription = "Bookmarks List",
                                        tint = if (bookmarks.isNotEmpty()) Color(0xFFFFD700) else Color.White
                                    )
                                }
                                if (bookmarks.isNotEmpty()) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 4.dp, end = 4.dp),
                                        shape = CircleShape,
                                        color = Color(0xFF6C5CE7)
                                    ) {
                                        Text(
                                            text = "${bookmarks.size}",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }

                            // Full Screen Toggle Button
                            IconButton(onClick = { isFullScreen = true }) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Full Screen Mode",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = { scale = (scale - 0.1f).coerceAtLeast(0.6f) },
                                enabled = scale > 0.6f
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomOut,
                                    contentDescription = "Zoom Out",
                                    tint = if (scale > 0.6f) Color.White else Color.Gray
                                )
                            }
                            IconButton(
                                onClick = { scale = (scale + 0.1f).coerceAtMost(3.0f) },
                                enabled = scale < 3.0f
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomIn,
                                    contentDescription = "Zoom In",
                                    tint = if (scale < 3.0f) Color.White else Color.Gray
                                )
                            }

                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(Color(0xFF1E1F2B))
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Bookmarks (${bookmarks.size})", color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            showMenu = false
                                            showBookmarksDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Bookmarks, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Full Screen Mode", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            showMenu = false
                                            isFullScreen = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share PDF", color = Color(0xFF818CF8), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            showMenu = false
                                            val fileToShare = resolvedFile ?: if (!isWebUrl) java.io.File(currentActivePath) else null
                                            if (fileToShare != null && fileToShare.exists()) {
                                                sharePdfFile(context, fileToShare, "Share PDF")
                                            } else {
                                                android.widget.Toast.makeText(context, "File unavailable for sharing", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open With (Other Apps)", color = Color(0xFFFFB74D), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            showMenu = false
                                            val pathToOpen = resolvedFile?.absolutePath ?: currentActivePath
                                            openWithSystemChooser(context, pathToOpen, "application/pdf")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reset Zoom", color = Color.White, fontSize = 13.sp) },
                                        onClick = {
                                            showMenu = false
                                            scale = 1.0f
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Compress PDF (Target < 5 MB)", color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            showMenu = false
                                            val fileToCompress = resolvedFile ?: if (!isWebUrl) java.io.File(currentActivePath) else null
                                            if (fileToCompress != null && fileToCompress.exists()) {
                                                scope.launch {
                                                    isCompressing = true
                                                    val origMb = String.format(java.util.Locale.US, "%.2f MB", fileToCompress.length() / (1024.0 * 1024.0))
                                                    compressionDetailStats = "Original: $origMb • Target: < 5.00 MB"
                                                    try {
                                                        val result = com.example.util.PdfCompressorHelper.compressPdf(
                                                            context = context,
                                                            inputSource = fileToCompress,
                                                            targetMaxSizeBytes = 5 * 1024 * 1024L,
                                                            onProgress = { cur, total, msg ->
                                                                compressionPageProgress = cur to total
                                                                compressionProgressText = msg
                                                            }
                                                        )
                                                        val compMb = String.format(java.util.Locale.US, "%.2f MB", result.compressedSizeBytes / (1024.0 * 1024.0))
                                                        val summaryText = "Compressed: $origMb ➔ $compMb (${result.reductionPercentage}% smaller)"
                                                        compressionDetailStats = summaryText

                                                        try {
                                                            val pdfRepo = com.example.pdf.PdfStorageRepository(context)
                                                            val item = com.example.pdf.PdfDocumentItem(
                                                                id = java.util.UUID.randomUUID().toString(),
                                                                title = result.outputFile.name,
                                                                uriString = result.outputFile.absolutePath,
                                                                fileSizeFormatted = compMb,
                                                                pageCount = pageCount
                                                            )
                                                            pdfRepo.addOrUpdatePdf(item)
                                                        } catch (e: Exception) {
                                                            e.printStackTrace()
                                                        }
                                                        android.widget.Toast.makeText(context, summaryText, android.widget.Toast.LENGTH_LONG).show()
                                                        
                                                        // Automatically open the compressed copy view!
                                                        currentActivePath = result.outputFile.absolutePath
                                                    } catch (e: Exception) {
                                                        android.widget.Toast.makeText(context, "Compression failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        isCompressing = false
                                                    }
                                                }
                                            } else {
                                                android.widget.Toast.makeText(context, "File unavailable for compression", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Compress, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(16.dp))
                                        }
                                    )
                                    resolvedFile?.let { file ->
                                        DropdownMenuItem(
                                            text = { Text("Open in Drive / External App", color = Color.White, fontSize = 13.sp) },
                                            onClick = {
                                                showMenu = false
                                                openPdfInSystemApp(context, file)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(16.dp))
                                            }
                                        )
                                    }
                                }
                            }

                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompressing) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.88f))
                                .padding(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    color = Color(0xFF4ADE80),
                                    modifier = Modifier.size(60.dp),
                                    strokeWidth = 4.dp
                                )
                                Icon(
                                    imageVector = Icons.Default.Compress,
                                    contentDescription = null,
                                    tint = Color(0xFF4ADE80),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Life OS PDF Compressor",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = compressionProgressText,
                                color = Color(0xFF4ADE80),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (compressionPageProgress.second > 0) {
                                Spacer(modifier = Modifier.height(14.dp))
                                LinearProgressIndicator(
                                    progress = { compressionPageProgress.first.toFloat() / compressionPageProgress.second.toFloat() },
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF4ADE80),
                                    trackColor = Color(0xFF2A2B3D)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Page ${compressionPageProgress.first} of ${compressionPageProgress.second}",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                            compressionDetailStats?.let { stats ->
                                Spacer(modifier = Modifier.height(16.dp))
                                Surface(
                                    color = Color(0xFF1E1F2B),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = stats,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    } else if (isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF6C5CE7))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Loading PDF...", color = Color.LightGray, fontSize = 14.sp)
                        }
                    } else if (errorMessage != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = errorMessage ?: "",
                                color = Color.White,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onDismiss) {
                                Text("Close")
                            }
                        }
                    } else {
                        val renderer = pdfRenderer
                        if (renderer != null && pageCount > 0) {
                            val displayMetrics = context.resources.displayMetrics
                            val density = displayMetrics.density
                            val currentVisibleIndex by remember {
                                derivedStateOf { listState.firstVisibleItemIndex }
                            }

                            Box(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 8.dp),
                                    contentPadding = PaddingValues(bottom = 96.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    items(
                                        count = pageCount,
                                        key = { index -> index }
                                    ) { index ->
                                        PdfLazyPageItem(
                                            pdfRenderer = renderer,
                                            pageIndex = index,
                                            scale = scale,
                                            density = density,
                                            pageCount = pageCount,
                                            isBookmarked = bookmarks.contains(index),
                                            onToggleBookmark = { toggleBookmark(index) }
                                        )
                                    }
                                }

                                // Floating Overlay Bar in Fullscreen Mode
                                if (isFullScreen) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 16.dp, end = 16.dp)
                                            .clip(RoundedCornerShape(24.dp)),
                                        color = Color(0xEA1E1F2B),
                                        tonalElevation = 8.dp,
                                        shadowElevation = 8.dp
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            val isCurrentBookmarked = bookmarks.contains(currentVisibleIndex)
                                            IconButton(
                                                onClick = { toggleBookmark(currentVisibleIndex) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isCurrentBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                    contentDescription = "Bookmark Current Page",
                                                    tint = if (isCurrentBookmarked) Color(0xFFFFD700) else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = { showBookmarksDialog = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Bookmarks,
                                                    contentDescription = "Bookmarks",
                                                    tint = if (bookmarks.isNotEmpty()) Color(0xFFFFD700) else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = { isFullScreen = false },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.FullscreenExit,
                                                    contentDescription = "Exit Fullscreen",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Fast Navigation Bar Overlay for Large/Huge PDFs (1000+ pages)
                                if (pageCount > 1) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .navigationBarsPadding()
                                            .padding(bottom = 28.dp, start = 12.dp, end = 12.dp)
                                            .clip(RoundedCornerShape(24.dp)),
                                        color = Color(0xEA1E1F2B),
                                        tonalElevation = 8.dp,
                                        shadowElevation = 8.dp
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            // Jump to First Page
                                            IconButton(
                                                onClick = {
                                                    scope.launch { listState.scrollToItem(0) }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.FirstPage,
                                                    contentDescription = "First Page",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Previous Page
                                            IconButton(
                                                onClick = {
                                                    val prev = (currentVisibleIndex - 1).coerceAtLeast(0)
                                                    scope.launch { listState.scrollToItem(prev) }
                                                },
                                                enabled = currentVisibleIndex > 0,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ChevronLeft,
                                                    contentDescription = "Previous Page",
                                                    tint = if (currentVisibleIndex > 0) Color.White else Color.Gray,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            // Current Page Pill / Quick Jump Trigger
                                            Surface(
                                                color = Color(0xFF2E3146),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .clickable {
                                                        jumpPageInput = (currentVisibleIndex + 1).toString()
                                                        showJumpDialog = true
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = "${currentVisibleIndex + 1} / $pageCount",
                                                        color = Color(0xFF818CF8),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.SwapVert,
                                                        contentDescription = "Jump",
                                                        tint = Color(0xFF818CF8),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }

                                            // Next Page
                                            IconButton(
                                                onClick = {
                                                    val next = (currentVisibleIndex + 1).coerceAtMost(pageCount - 1)
                                                    scope.launch { listState.scrollToItem(next) }
                                                },
                                                enabled = currentVisibleIndex < pageCount - 1,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ChevronRight,
                                                    contentDescription = "Next Page",
                                                    tint = if (currentVisibleIndex < pageCount - 1) Color.White else Color.Gray,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            // Jump to Last Page
                                            IconButton(
                                                onClick = {
                                                    scope.launch { listState.scrollToItem(pageCount - 1) }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LastPage,
                                                    contentDescription = "Last Page",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Quick Page Jump Dialog
                                if (showJumpDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showJumpDialog = false },
                                        containerColor = Color(0xFF1E1F2B),
                                        title = {
                                            Text(
                                                text = "Jump to Page (1 - $pageCount)",
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        text = {
                                            Column {
                                                OutlinedTextField(
                                                    value = jumpPageInput,
                                                    onValueChange = { input ->
                                                        if (input.all { it.isDigit() }) {
                                                            jumpPageInput = input
                                                        }
                                                    },
                                                    label = { Text("Page Number", color = Color.LightGray) },
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = Color(0xFF818CF8),
                                                        unfocusedBorderColor = Color.Gray,
                                                        focusedTextColor = Color.White,
                                                        unfocusedTextColor = Color.White
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                if (pageCount > 10) {
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    val currentSliderVal = (jumpPageInput.toIntOrNull() ?: (currentVisibleIndex + 1)).coerceIn(1, pageCount).toFloat()
                                                    Text(
                                                        text = "Fast Slider Navigation:",
                                                        color = Color.LightGray,
                                                        fontSize = 12.sp
                                                    )
                                                    Slider(
                                                        value = currentSliderVal,
                                                        onValueChange = { v ->
                                                            jumpPageInput = v.toInt().toString()
                                                        },
                                                        valueRange = 1f..pageCount.toFloat(),
                                                        colors = SliderDefaults.colors(
                                                            thumbColor = Color(0xFF818CF8),
                                                            activeTrackColor = Color(0xFF818CF8)
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    val targetPg = (jumpPageInput.toIntOrNull() ?: 1).coerceIn(1, pageCount)
                                                    showJumpDialog = false
                                                    scope.launch {
                                                        listState.scrollToItem(targetPg - 1)
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C5CE7))
                                            ) {
                                                Text("Go to Page", color = Color.White)
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showJumpDialog = false }) {
                                                Text("Cancel", color = Color.LightGray)
                                            }
                                        }
                                    )
                                }

                                // Bookmarks List Dialog
                                if (showBookmarksDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showBookmarksDialog = false },
                                        containerColor = Color(0xFF1E1F2B),
                                        title = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Bookmarks, contentDescription = null, tint = Color(0xFFFFD700))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Bookmarks (${bookmarks.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                }
                                                if (bookmarks.isNotEmpty()) {
                                                    TextButton(onClick = {
                                                        bookmarks = emptySet()
                                                        bmPrefs.edit().remove(bmKey).apply()
                                                    }) {
                                                        Text("Clear All", color = Color(0xFFFF5252), fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        },
                                        text = {
                                            if (bookmarks.isEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 24.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            "No bookmarks added yet.\nTap the bookmark icon to mark key pages.",
                                                            color = Color.LightGray,
                                                            fontSize = 13.sp,
                                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                        )
                                                    }
                                                }
                                            } else {
                                                val sortedBookmarks = bookmarks.sorted()
                                                LazyColumn(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 320.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    items(
                                                        count = sortedBookmarks.size,
                                                        key = { index -> sortedBookmarks[index] }
                                                    ) { idx ->
                                                        val pg = sortedBookmarks[idx]
                                                        Surface(
                                                            color = Color(0xFF2A2C3C),
                                                            shape = RoundedCornerShape(8.dp),
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    showBookmarksDialog = false
                                                                    scope.launch { listState.scrollToItem(pg) }
                                                                }
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                                                                    Spacer(modifier = Modifier.width(10.dp))
                                                                    Text("Page ${pg + 1} of $pageCount", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                                                }
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Text("Jump ➔", color = Color(0xFF818CF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                                    Spacer(modifier = Modifier.width(8.dp))
                                                                    IconButton(
                                                                        onClick = { toggleBookmark(pg) },
                                                                        modifier = Modifier.size(28.dp)
                                                                    ) {
                                                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Bookmark", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(onClick = { showBookmarksDialog = false }) {
                                                Text("Close", color = Color(0xFF818CF8))
                                             }
                                         }
                                     )
                                 }

                                 if (showActionChooserDialog) {
                                     AlertDialog(
                                         onDismissRequest = { showActionChooserDialog = false },
                                         containerColor = Color(0xFF1E1F2B),
                                         title = {
                                             Row(verticalAlignment = Alignment.CenterVertically) {
                                                 Icon(
                                                     imageVector = Icons.Default.PictureAsPdf,
                                                     contentDescription = null,
                                                     tint = Color(0xFFFF5252),
                                                     modifier = Modifier.size(24.dp)
                                                 )
                                                 Spacer(modifier = Modifier.width(8.dp))
                                                 Text(
                                                     text = when {
                                                         currentActivePath.contains("drive.google.com") -> "Google Drive PDF"
                                                         currentActivePath.contains("adobe") -> "Adobe Acrobat PDF"
                                                         else -> "Shared PDF Document"
                                                     },
                                                     color = Color.White,
                                                     fontSize = 16.sp,
                                                     fontWeight = FontWeight.Bold
                                                 )
                                             }
                                         },
                                         text = {
                                             Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                 Text(
                                                     text = "A PDF document or cloud link was shared with the app.",
                                                     color = Color.LightGray,
                                                     fontSize = 13.sp
                                                 )
                                                 Surface(
                                                     color = Color(0xFF2A2C3C),
                                                     shape = RoundedCornerShape(8.dp),
                                                     modifier = Modifier.fillMaxWidth()
                                                 ) {
                                                     Text(
                                                         text = if (pdfFileName != "PDF Document" && pdfFileName.isNotBlank()) pdfFileName else currentActivePath,
                                                         color = Color(0xFF818CF8),
                                                         fontSize = 12.sp,
                                                         fontWeight = FontWeight.Medium,
                                                         maxLines = 2,
                                                         overflow = TextOverflow.Ellipsis,
                                                         modifier = Modifier.padding(10.dp)
                                                     )
                                                 }
                                                 Text(
                                                     text = "Would you like to compress this PDF below 5 MB or view it?",
                                                     color = Color.White,
                                                     fontSize = 13.sp,
                                                     fontWeight = FontWeight.SemiBold
                                                 )
                                             }
                                         },
                                         confirmButton = {
                                             Button(
                                                 onClick = {
                                                     showActionChooserDialog = false
                                                     shouldAutoCompress = true
                                                 },
                                                 colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80))
                                             ) {
                                                 Icon(Icons.Default.Compress, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                 Spacer(modifier = Modifier.width(6.dp))
                                                 Text("Compress PDF (< 5 MB)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                             }
                                         },
                                         dismissButton = {
                                             OutlinedButton(
                                                 onClick = {
                                                     showActionChooserDialog = false
                                                 }
                                             ) {
                                                 Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                 Spacer(modifier = Modifier.width(6.dp))
                                                 Text("View PDF", color = Color.White, fontSize = 13.sp)
                                             }
                                         }
                                     )
                                 }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfLazyPageItem(
    pdfRenderer: android.graphics.pdf.PdfRenderer,
    pageIndex: Int,
    scale: Float,
    density: Float,
    pageCount: Int,
    isBookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null
) {
    var bitmap by remember(pageIndex, scale) { mutableStateOf<ImageBitmap?>(null) }
    var isRendering by remember(pageIndex, scale) { mutableStateOf(true) }

    LaunchedEffect(pageIndex, scale) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                synchronized(pdfRenderer) {
                    val page = pdfRenderer.openPage(pageIndex)
                    val targetWidth = (page.width * density * 1.1f * scale).toInt().coerceIn(250, 2048)
                    val aspectRatio = page.height.toFloat() / page.width.toFloat()
                    val targetHeight = (targetWidth * aspectRatio).toInt().coerceIn(250, 3200)

                    val bmp = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap = bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                Log.e("PdfLazyPageItem", "Error rendering page $pageIndex", e)
            } finally {
                isRendering = false
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (isRendering && bitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(Color(0xFF2A2C3C)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6C5CE7), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Rendering Page ${pageIndex + 1} of $pageCount...",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (bitmap != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = "PDF Page ${pageIndex + 1}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Page ${pageIndex + 1} of $pageCount",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (onToggleBookmark != null) {
                            IconButton(
                                onClick = onToggleBookmark,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Bookmark Page",
                                    tint = if (isBookmarked) Color(0xFFFFB300) else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isBookmarked) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color(0xEA1E1F2B),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Bookmarked",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InAppAudioPlayerDialog(
    filePathOrUri: String,
    title: String = "Audio File",
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var isPrepared by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Audio Enhancers State
    var loudnessGainMb by remember { mutableIntStateOf(1000) } // +10dB Gain
    var bassStrength by remember { mutableIntStateOf(500) }
    var virtualizerStrength by remember { mutableIntStateOf(400) }
    var audioPreset by remember { mutableStateOf("VOCAL_SPEECH") }
    var showEnhancerStudio by remember { mutableStateOf(false) }
    var audioEffectsManager by remember { mutableStateOf<AvAudioEffectsManager?>(null) }

    val displayName = remember(title, filePathOrUri) {
        if (title.isNotEmpty() && title != "Audio File") title
        else filePathOrUri.substringAfterLast("/").substringAfterLast("%2F").ifEmpty { "Audio File" }
    }

    DisposableEffect(filePathOrUri) {
        val mp = android.media.MediaPlayer()
        try {
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                mp.setDataSource(context, Uri.parse(filePathOrUri))
            } else {
                val file = java.io.File(filePathOrUri)
                if (file.exists()) {
                    mp.setDataSource(file.absolutePath)
                } else {
                    mp.setDataSource(filePathOrUri)
                }
            }
            mp.setOnPreparedListener {
                durationMs = it.duration.coerceAtLeast(1)
                isPrepared = true
                it.start()
                isPlaying = true
                try {
                    val effects = AvAudioEffectsManager(it.audioSessionId)
                    audioEffectsManager = effects
                    effects.setLoudnessGainMb(loudnessGainMb)
                    effects.setBassStrength(bassStrength)
                    effects.setVirtualizerStrength(virtualizerStrength)
                    effects.setEqualizerPreset(audioPreset)
                } catch (e: Exception) {
                    Log.e("InAppAudioPlayer", "Error attaching audio effects", e)
                }
            }
            mp.setOnCompletionListener {
                isPlaying = false
                currentPositionMs = durationMs
            }
            mp.setOnErrorListener { _, _, _ ->
                errorMessage = "Unable to play audio format."
                false
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e("InAppAudioPlayer", "Error initializing audio player", e)
            errorMessage = e.localizedMessage ?: "Failed to open audio file"
        }

        onDispose {
            try {
                audioEffectsManager?.release()
                mp.stop()
                mp.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(loudnessGainMb, bassStrength, virtualizerStrength, audioPreset, audioEffectsManager) {
        audioEffectsManager?.let { eff ->
            eff.setLoudnessGainMb(loudnessGainMb)
            eff.setBassStrength(bassStrength)
            eff.setVirtualizerStrength(virtualizerStrength)
            eff.setEqualizerPreset(audioPreset)
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            try {
                currentPositionMs = mediaPlayer?.currentPosition ?: 0
            } catch (e: Exception) {}
            kotlinx.coroutines.delay(300)
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF10121A),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AUDIO PLAYER", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { showEnhancerStudio = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Audio Enhancer", tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { openWithSystemChooser(context, filePathOrUri, "audio/*", displayName) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open With (Other Apps)", tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Disc artwork
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF07090E))
                        .border(3.dp, Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF64B5F6).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(24.dp))
                    }
                }

                // Title
                Text(
                    text = displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                if (errorMessage != null) {
                    Text(errorMessage ?: "", color = Color(0xFFFF5252), fontSize = 12.sp)
                } else if (!isPrepared) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF64B5F6), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading audio...", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    // Time & Slider
                    val progress = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    Slider(
                        value = progress,
                        onValueChange = { newFrac ->
                            val targetMs = (newFrac * durationMs).toInt()
                            currentPositionMs = targetMs
                            mediaPlayer?.seekTo(targetMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF64B5F6),
                            activeTrackColor = Color(0xFF64B5F6),
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )

                    val currSec = currentPositionMs / 1000
                    val durSec = durationMs / 1000
                    val timeStr = String.format(java.util.Locale.US, "%02d:%02d / %02d:%02d", currSec / 60, currSec % 60, durSec / 60, durSec % 60)
                    Text(timeStr, color = Color.Gray, fontSize = 11.sp)

                    // Controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val newPos = (currentPositionMs - 10000).coerceAtLeast(0)
                            currentPositionMs = newPos
                            mediaPlayer?.seekTo(newPos)
                        }) {
                            Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White)
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    mediaPlayer?.pause()
                                    isPlaying = false
                                } else {
                                    mediaPlayer?.start()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF64B5F6), androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black
                            )
                        }

                        IconButton(onClick = {
                            val newPos = (currentPositionMs + 10000).coerceAtMost(durationMs)
                            currentPositionMs = newPos
                            mediaPlayer?.seekTo(newPos)
                        }) {
                            Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showEnhancerStudio) {
        AvQualityEnhancerDialog(
            videoEnhanceMode = "OFF",
            onVideoEnhanceModeChange = {},
            contrastLevel = 1.0f,
            onContrastChange = {},
            brightnessLevel = 0.0f,
            onBrightnessChange = {},
            saturationLevel = 1.0f,
            onSaturationChange = {},
            loudnessGainMb = loudnessGainMb,
            onLoudnessGainChange = { loudnessGainMb = it },
            bassStrength = bassStrength,
            onBassStrengthChange = { bassStrength = it },
            virtualizerStrength = virtualizerStrength,
            onVirtualizerStrengthChange = { virtualizerStrength = it },
            audioPreset = audioPreset,
            onAudioPresetChange = { audioPreset = it },
            isVideoPlayer = false,
            onDismiss = { showEnhancerStudio = false }
        )
    }
}

// =========================================================================
// A/V QUALITY & AUDIO ENHANCER ENGINE (FOR DOWNLOADED LOW-QUALITY VIDEOS/AUDIO)
// =========================================================================

class AvAudioEffectsManager(val audioSessionId: Int) {
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null

    init {
        if (audioSessionId != 0) {
            try {
                loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).apply {
                    enabled = true
                }
            } catch (e: Exception) {
                Log.w("AvAudioEffects", "LoudnessEnhancer unavailable: ${e.message}")
            }

            try {
                bassBoost = android.media.audiofx.BassBoost(0, audioSessionId).apply {
                    enabled = true
                }
            } catch (e: Exception) {
                Log.w("AvAudioEffects", "BassBoost unavailable: ${e.message}")
            }

            try {
                virtualizer = android.media.audiofx.Virtualizer(0, audioSessionId).apply {
                    enabled = true
                }
            } catch (e: Exception) {
                Log.w("AvAudioEffects", "Virtualizer unavailable: ${e.message}")
            }

            try {
                equalizer = android.media.audiofx.Equalizer(0, audioSessionId).apply {
                    enabled = true
                }
            } catch (e: Exception) {
                Log.w("AvAudioEffects", "Equalizer unavailable: ${e.message}")
            }
        }
    }

    fun setLoudnessGainMb(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb.coerceIn(0, 1500))
        } catch (e: Exception) {
            Log.w("AvAudioEffects", "Error setting loudness gain: ${e.message}")
        }
    }

    fun setBassStrength(strength: Int) {
        try {
            bassBoost?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.w("AvAudioEffects", "Error setting bass strength: ${e.message}")
        }
    }

    fun setVirtualizerStrength(strength: Int) {
        try {
            virtualizer?.let {
                if (it.strengthSupported) {
                    it.setStrength(strength.coerceIn(0, 1000).toShort())
                }
            }
        } catch (e: Exception) {
            Log.w("AvAudioEffects", "Error setting virtualizer strength: ${e.message}")
        }
    }

    fun setEqualizerPreset(presetName: String) {
        try {
            val eq = equalizer ?: return
            val bands = eq.numberOfBands
            if (bands <= 0) return

            val minLevel = eq.bandLevelRange[0]
            val maxLevel = eq.bandLevelRange[1]

            when (presetName) {
                "VOCAL_SPEECH" -> {
                    for (i in 0 until bands) {
                        val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                        if (centerFreq in 800..4000) {
                            eq.setBandLevel(i.toShort(), 600.coerceAtMost(maxLevel.toInt()).toShort())
                        } else if (centerFreq < 250) {
                            eq.setBandLevel(i.toShort(), (-200).coerceAtLeast(minLevel.toInt()).toShort())
                        } else {
                            eq.setBandLevel(i.toShort(), 0.toShort())
                        }
                    }
                }
                "BASS_HEAVY" -> {
                    for (i in 0 until bands) {
                        val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                        if (centerFreq < 350) {
                            eq.setBandLevel(i.toShort(), 800.coerceAtMost(maxLevel.toInt()).toShort())
                        } else {
                            eq.setBandLevel(i.toShort(), 0.toShort())
                        }
                    }
                }
                "TREBLE_CLARITY" -> {
                    for (i in 0 until bands) {
                        val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                        if (centerFreq > 3000) {
                            eq.setBandLevel(i.toShort(), 700.coerceAtMost(maxLevel.toInt()).toShort())
                        } else {
                            eq.setBandLevel(i.toShort(), 0.toShort())
                        }
                    }
                }
                "CINEMA_HD" -> {
                    for (i in 0 until bands) {
                        val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                        if (centerFreq < 200 || centerFreq in 1000..4000) {
                            eq.setBandLevel(i.toShort(), 500.coerceAtMost(maxLevel.toInt()).toShort())
                        } else {
                            eq.setBandLevel(i.toShort(), 0.toShort())
                        }
                    }
                }
                else -> {
                    for (i in 0 until bands) {
                        eq.setBandLevel(i.toShort(), 0.toShort())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AvAudioEffects", "Error setting EQ preset: ${e.message}")
        }
    }

    fun release() {
        try { loudnessEnhancer?.release() } catch (e: Exception) {}
        try { bassBoost?.release() } catch (e: Exception) {}
        try { virtualizer?.release() } catch (e: Exception) {}
        try { equalizer?.release() } catch (e: Exception) {}
    }
}

fun buildEnhancerColorMatrix(
    mode: String,
    contrast: Float,
    brightness: Float,
    saturation: Float
): androidx.compose.ui.graphics.ColorMatrix {
    val cm = android.graphics.ColorMatrix()

    var effContrast = contrast
    var effBrightness = brightness * 255f
    var effSat = saturation

    when (mode) {
        "AI_HD_CLARITY" -> {
            effContrast *= 1.25f
            effSat *= 1.20f
            effBrightness += 10f
        }
        "VIBRANT_HDR" -> {
            effContrast *= 1.35f
            effSat *= 1.40f
            effBrightness += 5f
        }
        "NIGHT_VISION" -> {
            effContrast *= 1.15f
            effBrightness += 45f
            effSat *= 0.90f
        }
        "EYE_COMFORT" -> {
            effContrast *= 1.0f
            effSat *= 0.85f
        }
    }

    val c = effContrast
    val b = effBrightness
    val contrastMatrix = floatArrayOf(
        c, 0f, 0f, 0f, b,
        0f, c, 0f, 0f, b,
        0f, 0f, c, 0f, b,
        0f, 0f, 0f, 1f, 0f
    )
    cm.set(contrastMatrix)

    if (effSat != 1.0f) {
        val satMat = android.graphics.ColorMatrix()
        satMat.setSaturation(effSat)
        cm.postConcat(satMat)
    }

    return androidx.compose.ui.graphics.ColorMatrix(cm.array)
}

@Composable
fun AvQualityEnhancerDialog(
    videoEnhanceMode: String,
    onVideoEnhanceModeChange: (String) -> Unit,
    contrastLevel: Float,
    onContrastChange: (Float) -> Unit,
    brightnessLevel: Float,
    onBrightnessChange: (Float) -> Unit,
    saturationLevel: Float,
    onSaturationChange: (Float) -> Unit,
    loudnessGainMb: Int,
    onLoudnessGainChange: (Int) -> Unit,
    bassStrength: Int,
    onBassStrengthChange: (Int) -> Unit,
    virtualizerStrength: Int,
    onVirtualizerStrengthChange: (Int) -> Unit,
    audioPreset: String,
    onAudioPresetChange: (String) -> Unit,
    isVideoPlayer: Boolean = true,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableIntStateOf(if (isVideoPlayer) 0 else 1) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF121420),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.4f)),
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFFFFD700).copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text("A/V QUALITY ENHANCER", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Real-Time HD Clarity & Volume Booster", color = Color(0xFFFFD700), fontSize = 10.sp)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                }

                // Quick Presets Bar
                Text("QUICK PRESETS (LOW-RES FIXES)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            onVideoEnhanceModeChange("AI_HD_CLARITY")
                            onContrastChange(1.25f)
                            onBrightnessChange(0.04f)
                            onSaturationChange(1.20f)
                            onLoudnessGainChange(1000) // +10dB
                            onAudioPresetChange("VOCAL_SPEECH")
                            onBassStrengthChange(500)
                            onVirtualizerStrengthChange(400)
                        },
                        label = { Text("🚀 Max HD Quality", fontSize = 11.sp, color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF2E6FF3).copy(alpha = 0.3f))
                    )
                    AssistChip(
                        onClick = {
                            onVideoEnhanceModeChange("AI_HD_CLARITY")
                            onLoudnessGainChange(1200) // +12dB
                            onAudioPresetChange("VOCAL_SPEECH")
                            onBassStrengthChange(200)
                        },
                        label = { Text("🗣️ Speech & Dialogue", fontSize = 11.sp, color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF00B894).copy(alpha = 0.3f))
                    )
                    AssistChip(
                        onClick = {
                            onVideoEnhanceModeChange("VIBRANT_HDR")
                            onContrastChange(1.35f)
                            onSaturationChange(1.40f)
                            onLoudnessGainChange(800)
                            onAudioPresetChange("CINEMA_HD")
                            onBassStrengthChange(700)
                            onVirtualizerStrengthChange(600)
                        },
                        label = { Text("🎬 Cinema Night", fontSize = 11.sp, color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFE17055).copy(alpha = 0.3f))
                    )
                    AssistChip(
                        onClick = {
                            onVideoEnhanceModeChange("EYE_COMFORT")
                            onContrastChange(1.0f)
                            onBrightnessChange(-0.05f)
                            onLoudnessGainChange(400)
                            onAudioPresetChange("OFF")
                        },
                        label = { Text("👁️ Eye Comfort", fontSize = 11.sp, color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFFDCB6E).copy(alpha = 0.3f))
                    )
                    AssistChip(
                        onClick = {
                            onVideoEnhanceModeChange("OFF")
                            onContrastChange(1.0f)
                            onBrightnessChange(0.0f)
                            onSaturationChange(1.0f)
                            onLoudnessGainChange(0)
                            onAudioPresetChange("OFF")
                            onBassStrengthChange(0)
                            onVirtualizerStrengthChange(0)
                        },
                        label = { Text("🔄 Reset Raw", fontSize = 11.sp, color = Color.LightGray) }
                    )
                }

                // Tab Buttons
                if (isVideoPlayer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1D2030), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { activeTab = 0 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (activeTab == 0) Color(0xFF2E6FF3) else Color.Transparent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🎬 Video Quality", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { activeTab = 1 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (activeTab == 1) Color(0xFF2E6FF3) else Color.Transparent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔊 Audio Booster", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Content Tab 0: Video Enhancer
                if (activeTab == 0 && isVideoPlayer) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ENHANCEMENT MODE", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val modes = listOf(
                                "AI_HD_CLARITY" to "✨ AI HD Clarity",
                                "VIBRANT_HDR" to "🌈 Vibrant HDR",
                                "NIGHT_VISION" to "🌙 Night Boost",
                                "EYE_COMFORT" to "👁️ Eye Comfort",
                                "OFF" to "🚫 Raw / Off"
                            )
                            modes.forEach { (modeKey, label) ->
                                val isSelected = videoEnhanceMode == modeKey
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onVideoEnhanceModeChange(modeKey) },
                                    label = { Text(label, fontSize = 11.sp, color = if (isSelected) Color.Black else Color.White) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFFFD700),
                                        containerColor = Color(0xFF24283B)
                                    )
                                )
                            }
                        }

                        // Fine Tuning Sliders
                        Text("FINE TUNING SLIDERS", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        // Contrast Slider
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Contrast Sharpness", color = Color.White, fontSize = 12.sp)
                                Text(String.format(java.util.Locale.US, "%.2fx", contrastLevel), color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = contrastLevel,
                                onValueChange = onContrastChange,
                                valueRange = 0.8f..1.8f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD700), activeTrackColor = Color(0xFFFFD700))
                            )
                        }

                        // Brightness Slider
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Brightness Lift", color = Color.White, fontSize = 12.sp)
                                Text(String.format(java.util.Locale.US, "%+d%%", (brightnessLevel * 100).toInt()), color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = brightnessLevel,
                                onValueChange = onBrightnessChange,
                                valueRange = -0.3f..0.3f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD700), activeTrackColor = Color(0xFFFFD700))
                            )
                        }

                        // Saturation Slider
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Color Saturation", color = Color.White, fontSize = 12.sp)
                                Text(String.format(java.util.Locale.US, "%.2fx", saturationLevel), color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = saturationLevel,
                                onValueChange = onSaturationChange,
                                valueRange = 0.5f..2.0f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFFFFD700), activeTrackColor = Color(0xFFFFD700))
                            )
                        }
                    }
                }

                // Content Tab 1: Audio Enhancer
                if (activeTab == 1 || !isVideoPlayer) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Volume Loudness Booster
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(16.dp))
                                    Text("Loudness Boost (+dB)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                val dbGain = loudnessGainMb / 100
                                Text("+$dbGain dB", color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("Boosts quiet speech & low-volume downloaded clips up to +15dB", color = Color.Gray, fontSize = 10.sp)
                            Slider(
                                value = loudnessGainMb.toFloat(),
                                onValueChange = { onLoudnessGainChange(it.toInt()) },
                                valueRange = 0f..1500f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF4ADE80), activeTrackColor = Color(0xFF4ADE80))
                            )
                        }

                        // Equalizer Presets
                        Text("EQUALIZER & DIALOGUE CLEANER", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val eqPresets = listOf(
                                "VOCAL_SPEECH" to "🗣️ AI Speech Cleaner",
                                "BASS_HEAVY" to "🎵 Deep Bass",
                                "TREBLE_CLARITY" to "📻 Treble Boost",
                                "CINEMA_HD" to "🎬 Cinema HD",
                                "OFF" to "📻 Flat"
                            )
                            eqPresets.forEach { (presetKey, label) ->
                                val isSelected = audioPreset == presetKey
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onAudioPresetChange(presetKey) },
                                    label = { Text(label, fontSize = 11.sp, color = if (isSelected) Color.Black else Color.White) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF64B5F6),
                                        containerColor = Color(0xFF24283B)
                                    )
                                )
                            }
                        }

                        // Bass Boost
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Deep Bass Boost", color = Color.White, fontSize = 12.sp)
                                Text("${(bassStrength / 10)}%", color = Color(0xFF64B5F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = bassStrength.toFloat(),
                                onValueChange = { onBassStrengthChange(it.toInt()) },
                                valueRange = 0f..1000f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF64B5F6), activeTrackColor = Color(0xFF64B5F6))
                            )
                        }

                        // 3D Virtualizer
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("3D Surround Sound (Virtualizer)", color = Color.White, fontSize = 12.sp)
                                Text("${(virtualizerStrength / 10)}%", color = Color(0xFF64B5F6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = virtualizerStrength.toFloat(),
                                onValueChange = { onVirtualizerStrengthChange(it.toInt()) },
                                valueRange = 0f..1000f,
                                colors = SliderDefaults.colors(thumbColor = Color(0xFF64B5F6), activeTrackColor = Color(0xFF64B5F6))
                            )
                        }
                    }
                }

                // Apply / Close Button
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply & Continue Playback", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

