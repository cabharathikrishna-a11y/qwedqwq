package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Data model for PowerPoint slides
 */
data class PptxSlide(
    val slideNumber: Int,
    val title: String,
    val textBlocks: List<String>
)

/**
 * Data model for Office Document Content
 */
sealed class OfficeDocumentData {
    data class ExcelData(
        val sheets: Map<String, List<List<String>>>
    ) : OfficeDocumentData()

    data class WordData(
        val fullText: String,
        val paragraphs: List<String>
    ) : OfficeDocumentData()

    data class PowerPointData(
        val slides: List<PptxSlide>
    ) : OfficeDocumentData()

    data class GenericTextData(
        val text: String
    ) : OfficeDocumentData()
}

/**
 * In-App Office Document Viewer Dialog for Excel, Word, and PowerPoint files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppOfficeDocumentViewerDialog(
    cleanPath: String,
    fileName: String = "Document",
    mimeType: String? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("office_doc_viewer_dialog"),
            color = Color(0xFF111827) // Dark slate background
        ) {
            OfficeDocumentViewerContent(
                cleanPath = cleanPath,
                initialFileName = fileName,
                mimeType = mimeType,
                onDismiss = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeDocumentViewerContent(
    cleanPath: String,
    initialFileName: String,
    mimeType: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var docType by remember { mutableStateOf(detectDocType(cleanPath, mimeType, initialFileName)) }
    var parsedData by remember { mutableStateOf<OfficeDocumentData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var displayFileName by remember { mutableStateOf(initialFileName) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var useWebViewFallback by remember { mutableStateOf(false) }
    var isDarkMode by remember { mutableStateOf(true) }
    var textSizeSp by remember { mutableIntStateOf(14) }

    // Parse Document on Launch
    LaunchedEffect(cleanPath) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val resolvedName = resolveFileName(context, cleanPath, initialFileName)
                displayFileName = resolvedName
                docType = detectDocType(cleanPath, mimeType, resolvedName)

                val data = parseOfficeDocument(context, cleanPath, docType)
                if (data != null) {
                    parsedData = data
                } else {
                    // Fallback to text parsing or web viewer
                    useWebViewFallback = cleanPath.startsWith("http://") || cleanPath.startsWith("https://")
                    if (!useWebViewFallback) {
                        val genericText = readGenericText(context, cleanPath)
                        if (genericText.isNotBlank()) {
                            parsedData = OfficeDocumentData.GenericTextData(genericText)
                        } else {
                            errorMessage = "Unable to extract readable content from this file."
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("OfficeDocViewer", "Error parsing document: ${e.message}", e)
                errorMessage = "Error loading document: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F2937),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("office_viewer_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Document Viewer")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DocTypeBadge(docType = docType)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = displayFileName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = when (docType) {
                                DocType.EXCEL -> "Excel / Spreadsheet Viewer"
                                DocType.WORD -> "Word Document Reader"
                                DocType.POWERPOINT -> "PowerPoint Presentation Viewer"
                                DocType.CSV -> "CSV Data Table Viewer"
                                DocType.GENERIC -> "Document Viewer"
                            },
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchActive = !isSearchActive }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isSearchActive) Color(0xFF60A5FA) else Color.White
                        )
                    }

                    // Toggle between Native Parser and Google Docs Web Viewer
                    IconButton(
                        onClick = { useWebViewFallback = !useWebViewFallback },
                        modifier = Modifier.testTag("toggle_web_view_btn")
                    ) {
                        Icon(
                            imageVector = if (useWebViewFallback) Icons.Default.MenuBook else Icons.Default.Language,
                            contentDescription = if (useWebViewFallback) "Switch to Native Reader" else "Switch to Web Preview",
                            tint = if (useWebViewFallback) Color(0xFF34D399) else Color.White
                        )
                    }

                    // Open with system chooser
                    IconButton(onClick = {
                        val mime = when (docType) {
                            DocType.EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            DocType.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            DocType.POWERPOINT -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                            DocType.CSV -> "text/csv"
                            DocType.GENERIC -> "*/*"
                        }
                        openWithSystemChooser(context, cleanPath, mime, displayFileName)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share Document")
                    }
                }
            )
        },
        containerColor = Color(0xFF111827)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Search Bar Sub-Header
            AnimatedVisibility(visible = isSearchActive) {
                Surface(
                    color = Color(0xFF1F2937),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search text or numbers...", color = Color.Gray, fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF60A5FA),
                                unfocusedBorderColor = Color.Gray,
                                focusedContainerColor = Color(0xFF111827),
                                unfocusedContainerColor = Color(0xFF111827)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.Gray)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Main Content Area
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF60A5FA))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Reading ${docType.name} document...", color = Color.White, fontSize = 14.sp)
                    }
                }
            } else if (useWebViewFallback) {
                WebOfficeDocumentViewer(
                    cleanPath = cleanPath,
                    fileName = displayFileName
                )
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Unable to Parse Local File",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errorMessage ?: "Unknown error",
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { useWebViewFallback = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6))
                                ) {
                                    Text("Try Web Preview")
                                }
                                OutlinedButton(
                                    onClick = {
                                        openWithSystemChooser(context, cleanPath, "*/*", displayFileName)
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) {
                                    Text("Open External App")
                                }
                            }
                        }
                    }
                }
            } else {
                when (val data = parsedData) {
                    is OfficeDocumentData.ExcelData -> {
                        ExcelSpreadsheetViewer(
                            data = data,
                            searchQuery = searchQuery
                        )
                    }
                    is OfficeDocumentData.WordData -> {
                        WordDocumentViewer(
                            data = data,
                            searchQuery = searchQuery,
                            textSizeSp = textSizeSp
                        )
                    }
                    is OfficeDocumentData.PowerPointData -> {
                        PowerPointPresentationViewer(
                            data = data,
                            searchQuery = searchQuery
                        )
                    }
                    is OfficeDocumentData.GenericTextData -> {
                        GenericTextViewer(
                            text = data.text,
                            searchQuery = searchQuery
                        )
                    }
                    null -> {
                        WebOfficeDocumentViewer(cleanPath = cleanPath, fileName = displayFileName)
                    }
                }
            }
        }
    }
}

/**
 * Excel / CSV Interactive Grid Viewer
 */
@Composable
fun ExcelSpreadsheetViewer(
    data: OfficeDocumentData.ExcelData,
    searchQuery: String
) {
    var selectedSheetName by remember(data) {
        mutableStateOf(data.sheets.keys.firstOrNull() ?: "Sheet1")
    }

    val currentRows = remember(data, selectedSheetName) {
        data.sheets[selectedSheetName] ?: emptyList()
    }

    val columnCount = remember(currentRows) {
        currentRows.maxOfOrNull { it.size } ?: 0
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sheet Tabs Bar if multiple sheets exist
        if (data.sheets.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F2937))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(data.sheets.keys.toList()) { sheetName ->
                    val isSelected = sheetName == selectedSheetName
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedSheetName = sheetName },
                        label = { Text(sheetName, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF10B981),
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF374151),
                            labelColor = Color.LightGray
                        )
                    )
                }
            }
        }

        // Stats Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111827))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${currentRows.size} rows • $columnCount columns",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Text(
                text = "Sheet: $selectedSheetName",
                color = Color(0xFF10B981),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (currentRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sheet is empty", color = Color.Gray)
            }
        } else {
            // Interactive Scrollable Grid Table
            val horizontalScrollState = rememberScrollState()
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(),
                    state = rememberLazyListState()
                ) {
                    // Column Header Row (A, B, C, D...)
                    item {
                        Row(
                            modifier = Modifier
                                .background(Color(0xFF374151))
                                .border(0.5.dp, Color(0xFF4B5563))
                        ) {
                            // Row Number Header
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(32.dp)
                                    .background(Color(0xFF1F2937))
                                    .border(0.5.dp, Color(0xFF4B5563)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("#", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            for (colIndex in 0 until columnCount) {
                                val colLabel = getColumnLetterName(colIndex)
                                Box(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .height(32.dp)
                                        .background(Color(0xFF1F2937))
                                        .border(0.5.dp, Color(0xFF4B5563))
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = colLabel,
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Data Rows
                    itemsIndexed(currentRows) { rowIndex, row ->
                        val isHeaderRow = rowIndex == 0
                        Row(
                            modifier = Modifier
                                .background(if (isHeaderRow) Color(0xFF1E293B) else Color(0xFF0F172A))
                                .border(0.2.dp, Color(0xFF334155))
                        ) {
                            // Row Index Number
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(36.dp)
                                    .background(Color(0xFF1E293B))
                                    .border(0.2.dp, Color(0xFF334155)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${rowIndex + 1}",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            for (colIndex in 0 until columnCount) {
                                val cellValue = row.getOrNull(colIndex) ?: ""
                                val isMatch = searchQuery.isNotBlank() && cellValue.contains(searchQuery, ignoreCase = true)

                                Box(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .height(36.dp)
                                        .background(
                                            when {
                                                isMatch -> Color(0xFF9333EA).copy(alpha = 0.4f)
                                                isHeaderRow -> Color(0xFF1E293B)
                                                colIndex % 2 == 0 -> Color(0xFF0F172A)
                                                else -> Color(0xFF111827)
                                            }
                                        )
                                        .border(0.2.dp, Color(0xFF334155))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = cellValue,
                                            color = if (isHeaderRow) Color(0xFF60A5FA) else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isHeaderRow) FontWeight.Bold else FontWeight.Normal,
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
        }
    }
}

/**
 * Word Document Reader View
 */
@Composable
fun WordDocumentViewer(
    data: OfficeDocumentData.WordData,
    searchQuery: String,
    textSizeSp: Int
) {
    val paragraphs = remember(data) { data.paragraphs.ifEmpty { listOf(data.fullText) } }
    val wordCount = remember(data) {
        data.fullText.split("\\s+".toRegex()).count { it.isNotBlank() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Document Meta Header
        Surface(
            color = Color(0xFF1F2937),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Words: $wordCount • Paragraphs: ${paragraphs.size}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Text(
                    text = "Reading Time: ~${maxOf(1, wordCount / 200)} min",
                    color = Color(0xFF60A5FA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Clean Paper / Document Reading Layout
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(paragraphs) { index, para ->
                val isHeading = para.length < 60 && (para.startsWith("Chapter") || para.startsWith("Section") || para.all { it.isUpperCase() || it.isWhitespace() || it.isDigit() })
                val isMatch = searchQuery.isNotBlank() && para.contains(searchQuery, ignoreCase = true)

                Surface(
                    color = if (isMatch) Color(0xFF312E81) else Color(0xFF1F2937),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = para,
                            color = if (isHeading) Color(0xFF93C5FD) else Color.White,
                            fontSize = if (isHeading) (textSizeSp + 4).sp else textSizeSp.sp,
                            fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                            lineHeight = (textSizeSp + 8).sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * PowerPoint Presentation Slide Deck Viewer
 */
@Composable
fun PowerPointPresentationViewer(
    data: OfficeDocumentData.PowerPointData,
    searchQuery: String
) {
    val slides = remember(data) { data.slides }
    var currentSlideIndex by remember { mutableIntStateOf(0) }

    if (slides.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No slides found in presentation", color = Color.Gray)
        }
        return
    }

    val currentSlide = slides.getOrNull(currentSlideIndex) ?: slides.first()

    Column(modifier = Modifier.fillMaxSize()) {
        // Slide Navigation Header
        Surface(
            color = Color(0xFF1F2937),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (currentSlideIndex > 0) currentSlideIndex-- },
                    enabled = currentSlideIndex > 0
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous Slide",
                        tint = if (currentSlideIndex > 0) Color.White else Color.DarkGray
                    )
                }

                Text(
                    text = "Slide ${currentSlideIndex + 1} of ${slides.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                IconButton(
                    onClick = { if (currentSlideIndex < slides.size - 1) currentSlideIndex++ },
                    enabled = currentSlideIndex < slides.size - 1
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "Next Slide",
                        tint = if (currentSlideIndex < slides.size - 1) Color.White else Color.DarkGray
                    )
                }
            }
        }

        // Active Slide Card Presentation Box
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = currentSlide.title.ifBlank { "Slide ${currentSlide.slideNumber}" },
                        color = Color(0xFFF59E0B),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    currentSlide.textBlocks.forEach { block ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("• ", color = Color(0xFFF59E0B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            SelectionContainer {
                                Text(
                                    text = block,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Slide Thumbnail Selector Strip
        Surface(
            color = Color(0xFF1F2937),
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyRow(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(slides) { idx, slide ->
                    val isSelected = idx == currentSlideIndex
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFFF59E0B) else Color(0xFF374151)
                        ),
                        modifier = Modifier
                            .width(80.dp)
                            .height(50.dp)
                            .clickable { currentSlideIndex = idx },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Slide ${idx + 1}",
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Generic Text Viewer for fallback files
 */
@Composable
fun GenericTextViewer(text: String, searchQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Surface(
            color = Color(0xFF1F2937),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

/**
 * Web View Viewer using Google Docs Embedded Viewer
 */
@Composable
fun WebOfficeDocumentViewer(
    cleanPath: String,
    fileName: String
) {
    val context = LocalContext.current
    var isWebLoading by remember { mutableStateOf(true) }

    val docUrl = remember(cleanPath) {
        if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
            "https://docs.google.com/gview?embedded=true&url=${Uri.encode(cleanPath)}"
        } else {
            "https://docs.google.com/gview?embedded=true&url=${Uri.encode("file://$cleanPath")}"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isWebLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isWebLoading = false
                        }
                    }
                    loadUrl(docUrl)
                }
            },
            onRelease = { wv ->
                try {
                    wv.stopLoading()
                    wv.onPause()
                    wv.pauseTimers()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (_: Exception) {}
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isWebLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111827).copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF60A5FA))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading Web Preview...", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Document Type Badge UI Component
 */
@Composable
fun DocTypeBadge(docType: DocType) {
    val (bgColor, label, icon) = when (docType) {
        DocType.EXCEL, DocType.CSV -> Triple(Color(0xFF10B981), "EXCEL", Icons.Default.GridOn)
        DocType.WORD -> Triple(Color(0xFF3B82F6), "WORD", Icons.AutoMirrored.Filled.Article)
        DocType.POWERPOINT -> Triple(Color(0xFFF59E0B), "PPT", Icons.Default.Slideshow)
        DocType.GENERIC -> Triple(Color(0xFF6B7280), "DOC", Icons.Default.Description)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

enum class DocType {
    EXCEL, WORD, POWERPOINT, CSV, GENERIC
}

/**
 * Utility to detect document type based on path, mime, or filename
 */
fun detectDocType(path: String, mime: String?, name: String): DocType {
    val lowerPath = path.lowercase()
    val lowerName = name.lowercase()
    val lowerMime = (mime ?: "").lowercase()

    return when {
        lowerName.endsWith(".csv") || lowerMime.contains("csv") -> DocType.CSV
        lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") || lowerMime.contains("excel") || lowerMime.contains("spreadsheet") -> DocType.EXCEL
        lowerName.endsWith(".docx") || lowerName.endsWith(".doc") || lowerMime.contains("word") || lowerMime.contains("wordprocessing") -> DocType.WORD
        lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt") || lowerMime.contains("powerpoint") || lowerMime.contains("presentation") -> DocType.POWERPOINT
        lowerPath.contains("spreadsheet") || lowerPath.contains("docs.google.com/spreadsheets") -> DocType.EXCEL
        lowerPath.contains("document") || lowerPath.contains("docs.google.com/document") -> DocType.WORD
        lowerPath.contains("presentation") || lowerPath.contains("docs.google.com/presentation") -> DocType.POWERPOINT
        else -> DocType.GENERIC
    }
}

/**
 * Resolves file display name from Uri or Path
 */
fun resolveFileName(context: Context, pathOrUri: String, fallback: String): String {
    if (fallback.isNotBlank() && fallback != "Document") return fallback
    return try {
        val uri = Uri.parse(pathOrUri)
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    return cursor.getString(nameIdx)
                }
            }
        }
        uri.lastPathSegment ?: fallback
    } catch (e: Exception) {
        fallback
    }
}

/**
 * Parses Office Documents (Excel, Word, PowerPoint, CSV)
 */
suspend fun parseOfficeDocument(
    context: Context,
    cleanPath: String,
    docType: DocType
): OfficeDocumentData? = withContext(Dispatchers.IO) {
    try {
        val inputStream: InputStream? = openInputStreamForPath(context, cleanPath)
        if (inputStream == null) return@withContext null

        when (docType) {
            DocType.CSV -> {
                val rows = parseCsvStream(inputStream)
                inputStream.close()
                OfficeDocumentData.ExcelData(mapOf("Sheet1" to rows))
            }
            DocType.EXCEL -> {
                val sheets = parseXlsxZipStream(context, cleanPath)
                if (sheets.isNotEmpty()) {
                    OfficeDocumentData.ExcelData(sheets)
                } else {
                    null
                }
            }
            DocType.WORD -> {
                val text = parseDocxZipStream(context, cleanPath)
                if (text.isNotBlank()) {
                    val paras = text.split("\n\n").filter { it.isNotBlank() }
                    OfficeDocumentData.WordData(fullText = text, paragraphs = paras)
                } else {
                    null
                }
            }
            DocType.POWERPOINT -> {
                val slides = parsePptxZipStream(context, cleanPath)
                if (slides.isNotEmpty()) {
                    OfficeDocumentData.PowerPointData(slides)
                } else {
                    null
                }
            }
            DocType.GENERIC -> {
                val generic = readGenericTextFromStream(inputStream)
                inputStream.close()
                if (generic.isNotBlank()) OfficeDocumentData.GenericTextData(generic) else null
            }
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error parsing office doc: ${e.message}", e)
        null
    }
}

/**
 * Open InputStream from Content Uri, File Path, or HTTP URL
 */
fun openInputStreamForPath(context: Context, pathOrUri: String): InputStream? {
    return try {
        val uri = Uri.parse(pathOrUri)
        if (uri.scheme == "content" || uri.scheme == "android.resource") {
            context.contentResolver.openInputStream(uri)
        } else if (uri.scheme == "file" || pathOrUri.startsWith("/")) {
            val file = File(uri.path ?: pathOrUri)
            if (file.exists()) file.inputStream() else null
        } else if (uri.scheme == "http" || uri.scheme == "https") {
            val url = java.net.URL(pathOrUri)
            url.openStream()
        } else {
            val file = File(pathOrUri)
            if (file.exists()) file.inputStream() else null
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Could not open stream for $pathOrUri: ${e.message}")
        null
    }
}

/**
 * CSV Stream Parser
 */
fun parseCsvStream(inputStream: InputStream): List<List<String>> {
    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    val result = mutableListOf<List<String>>()
    var line: String?
    while (reader.readLine().also { line = it } != null) {
        line?.let {
            val row = it.split(",").map { cell -> cell.trim().removeSurrounding("\"") }
            result.add(row)
        }
    }
    return result
}

/**
 * Parse XLSX XML Zip Structure natively!
 */
fun parseXlsxZipStream(context: Context, cleanPath: String): Map<String, List<List<String>>> {
    val sheetsMap = mutableMapOf<String, List<List<String>>>()
    try {
        val sharedStrings = mutableListOf<String>()

        // 1st Pass: Read Shared Strings XML
        var stream = openInputStreamForPath(context, cleanPath) ?: return emptyMap()
        ZipInputStream(stream).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry?.name == "xl/sharedStrings.xml") {
                    sharedStrings.addAll(parseSharedStringsXml(zip))
                    break
                }
            }
        }

        // 2nd Pass: Read Worksheets
        stream = openInputStreamForPath(context, cleanPath) ?: return emptyMap()
        ZipInputStream(stream).use { zip ->
            var entry: ZipEntry?
            var sheetCount = 1
            while (zip.nextEntry.also { entry = it } != null) {
                val entryName = entry?.name ?: ""
                if (entryName.startsWith("xl/worksheets/sheet") && entryName.endsWith(".xml")) {
                    val sheetRows = parseWorksheetXml(zip, sharedStrings)
                    val sheetName = "Sheet $sheetCount"
                    sheetsMap[sheetName] = sheetRows
                    sheetCount++
                }
            }
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error reading XLSX zip: ${e.message}")
    }
    return sheetsMap
}

/**
 * Parse sharedStrings.xml in XLSX
 */
fun parseSharedStringsXml(inputStream: InputStream): List<String> {
    val strings = mutableListOf<String>()
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var currentText = StringBuilder()
        var insideT = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        insideT = true
                        currentText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideT) {
                        currentText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "t") {
                        insideT = false
                        strings.add(currentText.toString())
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error parsing shared strings: ${e.message}")
    }
    return strings
}

/**
 * Parse worksheet XML in XLSX
 */
fun parseWorksheetXml(inputStream: InputStream, sharedStrings: List<String>): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var currentRow = mutableListOf<String>()
        var cellType = ""
        var cellValue = StringBuilder()
        var insideV = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "row") {
                        currentRow = mutableListOf()
                    } else if (parser.name == "c") {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        cellValue.clear()
                    } else if (parser.name == "v") {
                        insideV = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideV) {
                        cellValue.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "v") {
                        insideV = false
                    } else if (parser.name == "c") {
                        val rawVal = cellValue.toString().trim()
                        val finalVal = if (cellType == "s") {
                            val idx = rawVal.toIntOrNull()
                            if (idx != null && idx >= 0 && idx < sharedStrings.size) {
                                sharedStrings[idx]
                            } else {
                                rawVal
                            }
                        } else {
                            rawVal
                        }
                        currentRow.add(finalVal)
                    } else if (parser.name == "row") {
                        rows.add(currentRow)
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error parsing worksheet XML: ${e.message}")
    }
    return rows
}

/**
 * Parse DOCX document.xml natively!
 */
fun parseDocxZipStream(context: Context, cleanPath: String): String {
    val docText = StringBuilder()
    try {
        val stream = openInputStreamForPath(context, cleanPath) ?: return ""
        ZipInputStream(stream).use { zip ->
            var entry: ZipEntry?
            while (zip.nextEntry.also { entry = it } != null) {
                if (entry?.name == "word/document.xml") {
                    val factory = XmlPullParserFactory.newInstance()
                    factory.isNamespaceAware = true
                    val parser = factory.newPullParser()
                    parser.setInput(zip, "UTF-8")

                    var eventType = parser.eventType
                    var insideT = false

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "t") insideT = true
                            }
                            XmlPullParser.TEXT -> {
                                if (insideT) docText.append(parser.text)
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "t") insideT = false
                                else if (parser.name == "p") docText.append("\n\n")
                            }
                        }
                        eventType = parser.next()
                    }
                    break
                }
            }
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error reading DOCX zip: ${e.message}")
    }
    return docText.toString().trim()
}

/**
 * Parse PPTX slides natively!
 */
fun parsePptxZipStream(context: Context, cleanPath: String): List<PptxSlide> {
    val slides = mutableListOf<PptxSlide>()
    try {
        val stream = openInputStreamForPath(context, cleanPath) ?: return emptyList()
        ZipInputStream(stream).use { zip ->
            var entry: ZipEntry?
            var slideNum = 1
            while (zip.nextEntry.also { entry = it } != null) {
                val name = entry?.name ?: ""
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    val slideTextBlocks = mutableListOf<String>()
                    val factory = XmlPullParserFactory.newInstance()
                    factory.isNamespaceAware = true
                    val parser = factory.newPullParser()
                    parser.setInput(zip, "UTF-8")

                    var eventType = parser.eventType
                    var insideT = false
                    var currentBlock = StringBuilder()

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == "t") insideT = true
                            }
                            XmlPullParser.TEXT -> {
                                if (insideT) currentBlock.append(parser.text)
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "t") insideT = false
                                else if (parser.name == "p") {
                                    if (currentBlock.isNotBlank()) {
                                        slideTextBlocks.add(currentBlock.toString().trim())
                                        currentBlock.clear()
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }

                    val title = slideTextBlocks.firstOrNull() ?: "Slide $slideNum"
                    val content = if (slideTextBlocks.size > 1) slideTextBlocks.subList(1, slideTextBlocks.size) else slideTextBlocks
                    slides.add(PptxSlide(slideNumber = slideNum, title = title, textBlocks = content))
                    slideNum++
                }
            }
        }
    } catch (e: Exception) {
        Log.e("OfficeDocViewer", "Error reading PPTX zip: ${e.message}")
    }
    return slides
}

/**
 * Generic text stream reader
 */
fun readGenericTextFromStream(inputStream: InputStream): String {
    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    val sb = StringBuilder()
    var line: String?
    var linesRead = 0
    while (reader.readLine().also { line = it } != null && linesRead < 500) {
        val cleanLine = line?.filter { it.isDefined() || it.isWhitespace() } ?: ""
        if (cleanLine.isNotBlank()) {
            sb.append(cleanLine).append("\n")
            linesRead++
        }
    }
    return sb.toString()
}

fun readGenericText(context: Context, cleanPath: String): String {
    val stream = openInputStreamForPath(context, cleanPath) ?: return ""
    return try {
        readGenericTextFromStream(stream)
    } finally {
        stream.close()
    }
}

/**
 * Get Column Letter Name for Excel (0 -> A, 1 -> B, 25 -> Z, 26 -> AA)
 */
fun getColumnLetterName(index: Int): String {
    var i = index
    val sb = StringBuilder()
    while (i >= 0) {
        sb.insert(0, ('A' + (i % 26)))
        i = (i / 26) - 1
    }
    return sb.toString()
}
