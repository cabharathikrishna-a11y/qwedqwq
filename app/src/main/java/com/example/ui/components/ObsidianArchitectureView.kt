package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.KeepNote
import com.example.ui.AppViewModel
import com.example.ui.Screen
import com.example.util.GoogleDriveSyncManager
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsidianArchitectureView(
    viewModel: AppViewModel,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keepNotes by viewModel.keepNotes.collectAsStateWithLifecycle(initialValue = emptyList())

    // 0: Editor & Vault, 1: Graph View, 2: Drive Sync, 3: Core Architecture Specs
    var mainTab by remember { mutableStateOf(0) }

    // Active Selected Note State
    var activeNote by remember { mutableStateOf<KeepNote?>(null) }
    var noteTitleState by remember { mutableStateOf("") }
    var noteContentState by remember { mutableStateOf("") }
    var editorMode by remember { mutableStateOf(0) } // 0: Live Preview, 1: Source Mode, 2: Reading Mode

    // Vault Search & Tag Filter
    var searchQuery by remember { mutableStateOf("") }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }

    // Synchronize active note text fields when activeNote changes
    LaunchedEffect(activeNote) {
        activeNote?.let {
            noteTitleState = it.title
            noteContentState = it.content
        }
    }

    // Initialize with default sample notes if vault is empty
    LaunchedEffect(keepNotes) {
        if (keepNotes.isEmpty()) {
            viewModel.insertKeepNote(
                title = "Obsidian Local Vault",
                content = "# Welcome to Obsidian Vault\nThis is a **local-first** Markdown note with rich Word-style formatting.\n\n" +
                        "## Key Features\n" +
                        "- Bi-directional links: [[In-Memory Graph]] and [[CodeMirror Editor]]\n" +
                        "  - Sub-point level 1: Automatic indentation\n" +
                        "    - Sub-point level 2: Nested bullet hierarchy\n" +
                        "- Text Highlights: ==yellow highlight==, ==color:#A7F3D0|green highlight==\n" +
                        "- Custom Colors: <color:#F43F5E>Red Text</color>, <color:#38BDF8>Blue Accent</color>\n" +
                        "- External Links: https://obsidian.md or [Obsidian Help](https://help.obsidian.md)\n" +
                        "- Tags: #obsidian #architecture #knowledge",
                colorHex = "#202124",
                isPinned = true
            )
            viewModel.insertKeepNote(
                title = "Research & Advanced Tables",
                content = "# Research & Excel-like Tables\n" +
                        "This note demonstrates **Advanced Tables** and **Zotero Citations** integration in Obsidian.\n\n" +
                        "## Deep Learning Research Papers\n" +
                        "- Transformer Architecture: [@vaswani2017transformer]\n" +
                        "- Deep Residual Learning: [@he2016resnet]\n" +
                        "- BERT Pre-training: [@devlin2019bert]\n\n" +
                        "## Performance & Benchmark Matrix\n" +
                        "| Model Variant | Parameters | BLEU Score | Latency (ms) |\n" +
                        "| :--- | :---: | :---: | :---: |\n" +
                        "| Transformer-Base | 65M | 27.3 | 42 |\n" +
                        "| Transformer-Big | 213M | 28.4 | 88 |\n" +
                        "| ResNet-151 Backbone | 60M | 24.1 | 35 |\n" +
                        "| Total Average | 112M | =AVG(C3:C5) | =AVG(D3:D5) |\n\n" +
                        "#research #zotero #tables #deeplearning",
                colorHex = "#202124",
                isPinned = true
            )
            viewModel.insertKeepNote(
                title = "In-Memory Graph",
                content = "# In-Memory Graph Indexing\nObsidian parses all .md files in the vault into an in-memory graph.\n\n" +
                        "Connected to: [[Obsidian Local Vault]] and [[CodeMirror Editor]].\n\n#graph #performance",
                colorHex = "#202124"
            )
            viewModel.insertKeepNote(
                title = "CodeMirror Editor",
                content = "# CodeMirror 6 Engine\nPowered by CodeMirror 6 for performant syntax highlighting, live preview, and Vim support.\n\n" +
                        "Links to [[In-Memory Graph]]. #editor #web",
                colorHex = "#202124"
            )
        } else if (activeNote == null) {
            activeNote = keepNotes.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Obsidian Local Vault Studio",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Local-First Markdown Engine, In-Memory Graph & Drive Sync",
                            fontSize = 11.sp,
                            color = Color(0xFFA0A5C0)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (onBack != null) onBack() else viewModel.navigateTo(Screen.SETTINGS)
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // Create New Note in Vault
                        val newTitle = "Untitled Note ${keepNotes.size + 1}"
                        viewModel.insertKeepNote(
                            title = newTitle,
                            content = "# $newTitle\nType Markdown content here with [[WikiLinks]] and #tags...",
                            colorHex = "#202124"
                        )
                        Toast.makeText(context, "Created new vault note", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Note",
                            tint = Color(0xFF34D399)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Badges Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { VaultBadge("Vault Notes: ${keepNotes.size}", Color(0xFF38BDF8), Icons.Default.Folder) }
                item { VaultBadge("Advanced Tables 📊", Color(0xFF34D399), Icons.Default.TableChart) }
                item { VaultBadge("Zotero Citations 📚", Color(0xFFFBBF24), Icons.Default.MenuBook) }
                item { VaultBadge("Local-First .md", Color(0xFF34D399), Icons.Default.Description) }
                item { VaultBadge("In-Memory Graph", Color(0xFFA855F7), Icons.Default.Share) }
                item { VaultBadge("Refactoring Engine", Color(0xFFFBBF24), Icons.Default.SyncAlt) }
            }

            // Main Tab Navigation
            TabRow(
                selectedTabIndex = mainTab,
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF818CF8),
                indicator = { tabPositions ->
                    if (mainTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[mainTab]),
                            height = 3.dp,
                            color = Color(0xFF818CF8)
                        )
                    }
                }
            ) {
                Tab(
                    selected = mainTab == 0,
                    onClick = { mainTab = 0 },
                    text = { Text("Vault & Editor", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
                Tab(
                    selected = mainTab == 1,
                    onClick = { mainTab = 1 },
                    text = { Text("Graph View", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
                Tab(
                    selected = mainTab == 2,
                    onClick = { mainTab = 2 },
                    text = { Text("Advanced Tables", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
                Tab(
                    selected = mainTab == 3,
                    onClick = { mainTab = 3 },
                    text = { Text("Zotero Research", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
                Tab(
                    selected = mainTab == 4,
                    onClick = { mainTab = 4 },
                    text = { Text("Drive Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
                Tab(
                    selected = mainTab == 5,
                    onClick = { mainTab = 5 },
                    text = { Text("Architecture", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Architecture, contentDescription = null, modifier = Modifier.size(15.dp)) }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (mainTab) {
                    0 -> VaultEditorTab(
                        viewModel = viewModel,
                        keepNotes = keepNotes,
                        activeNote = activeNote,
                        onSelectNote = { activeNote = it },
                        noteTitleState = noteTitleState,
                        onTitleChange = { noteTitleState = it },
                        noteContentState = noteContentState,
                        onContentChange = { noteContentState = it },
                        editorMode = editorMode,
                        onEditorModeChange = { editorMode = it },
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        selectedTagFilter = selectedTagFilter,
                        onTagFilterChange = { selectedTagFilter = it },
                        onSaveActiveNote = {
                            val curr = activeNote
                            if (curr != null) {
                                val oldTitle = curr.title
                                val newTitle = noteTitleState.trim()

                                // Auto Link Refactoring across Vault
                                if (oldTitle != newTitle && oldTitle.isNotEmpty() && newTitle.isNotEmpty()) {
                                    refactorVaultWikiLinks(
                                        viewModel = viewModel,
                                        allNotes = keepNotes,
                                        oldTitle = oldTitle,
                                        newTitle = newTitle
                                    )
                                    Toast.makeText(context, "Refactored links across vault for [[$newTitle]]", Toast.LENGTH_SHORT).show()
                                }

                                val updated = curr.copy(
                                    title = newTitle,
                                    content = noteContentState,
                                    timestamp = System.currentTimeMillis()
                                )
                                viewModel.updateKeepNote(updated)
                                activeNote = updated
                                Toast.makeText(context, "Saved to Local Vault", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onOpenAdvancedTables = { mainTab = 2 },
                        onOpenZoteroResearch = { mainTab = 3 }
                    )
                    1 -> VaultGraphViewTab(
                        keepNotes = keepNotes,
                        onOpenNoteByTitle = { targetTitle ->
                            val match = keepNotes.find { it.title.equals(targetTitle, ignoreCase = true) }
                            if (match != null) {
                                activeNote = match
                                mainTab = 0
                            } else {
                                // Create note if missing
                                viewModel.insertKeepNote(
                                    title = targetTitle,
                                    content = "# $targetTitle\nCreated via Graph Node navigation.",
                                    colorHex = "#202124"
                                )
                                Toast.makeText(context, "Created [[$targetTitle]]", Toast.LENGTH_SHORT).show()
                                mainTab = 0
                            }
                        }
                    )
                    2 -> AdvancedTablesTab(
                        keepNotes = keepNotes,
                        activeNote = activeNote,
                        onUpdateNoteContent = { newContent ->
                            noteContentState = newContent
                            val curr = activeNote
                            if (curr != null) {
                                val updated = curr.copy(content = newContent, timestamp = System.currentTimeMillis())
                                viewModel.updateKeepNote(updated)
                                activeNote = updated
                            }
                        },
                        onNavigateToEditor = { mainTab = 0 }
                    )
                    3 -> ZoteroResearchTab(
                        activeNote = activeNote,
                        onInsertCitation = { citeKey ->
                            noteContentState = if (noteContentState.isEmpty()) " [@$citeKey]" else "$noteContentState [@$citeKey]"
                            val curr = activeNote
                            if (curr != null) {
                                val updated = curr.copy(content = noteContentState, timestamp = System.currentTimeMillis())
                                viewModel.updateKeepNote(updated)
                                activeNote = updated
                            }
                            Toast.makeText(context, "Inserted citation [@$citeKey] into active note", Toast.LENGTH_SHORT).show()
                        },
                        onAppendBibliography = { bibText ->
                            noteContentState = if (noteContentState.contains("## References")) {
                                "$noteContentState\n$bibText"
                            } else {
                                "$noteContentState\n\n## References & Bibliography\n$bibText"
                            }
                            val curr = activeNote
                            if (curr != null) {
                                val updated = curr.copy(content = noteContentState, timestamp = System.currentTimeMillis())
                                viewModel.updateKeepNote(updated)
                                activeNote = updated
                            }
                            Toast.makeText(context, "Appended bibliography to active note", Toast.LENGTH_SHORT).show()
                        },
                        onNavigateToEditor = { mainTab = 0 }
                    )
                    4 -> DriveSyncEmbeddedTab(viewModel = viewModel)
                    5 -> CoreArchitectureSpecsTab(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun VaultBadge(text: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ==========================================
// TAB 1: VAULT NOTES & CODEMIRROR EDITOR
// ==========================================
@Composable
private fun VaultEditorTab(
    viewModel: AppViewModel,
    keepNotes: List<KeepNote>,
    activeNote: KeepNote?,
    onSelectNote: (KeepNote) -> Unit,
    noteTitleState: String,
    onTitleChange: (String) -> Unit,
    noteContentState: String,
    onContentChange: (String) -> Unit,
    editorMode: Int,
    onEditorModeChange: (Int) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedTagFilter: String?,
    onTagFilterChange: (String?) -> Unit,
    onSaveActiveNote: () -> Unit,
    onOpenAdvancedTables: () -> Unit = {},
    onOpenZoteroResearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFileList by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // Export Dialog State
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf("DOCX") } // DOCX, PDF, TXT, GOOGLE_DOC
    var exportProgressLog by remember { mutableStateOf<String?>(null) }
    var exportedDriveLink by remember { mutableStateOf<String?>(null) }

    // Highlight palette popover state
    var showHighlightMenu by remember { mutableStateOf(false) }
    var showColorMenu by remember { mutableStateOf(false) }

    // Extract all unique tags across vault
    val allTags = remember(keepNotes) {
        val tagRegex = Regex("#(\\w+)")
        keepNotes.flatMap { note ->
            tagRegex.findAll(note.content).map { it.groupValues[1] }.toList()
        }.distinct()
    }

    // Filtered notes list
    val filteredNotes = remember(keepNotes, searchQuery, selectedTagFilter) {
        keepNotes.filter { note ->
            val matchesSearch = searchQuery.isBlank() ||
                    note.title.contains(searchQuery, ignoreCase = true) ||
                    note.content.contains(searchQuery, ignoreCase = true)

            val matchesTag = selectedTagFilter == null ||
                    note.content.contains("#$selectedTagFilter", ignoreCase = true)

            matchesSearch && matchesTag
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Vault Filter Bar
        Surface(
            color = Color(0xFF1E293B),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search Vault Notes...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF818CF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    // Toggle File Drawer Button
                    IconButton(
                        onClick = { showFileList = !showFileList },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showFileList) Color(0xFF818CF8).copy(alpha = 0.2f) else Color(0xFF0F172A))
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Vault Items",
                            tint = if (showFileList) Color(0xFF818CF8) else Color.White
                        )
                    }
                }

                // Tags Filter Row
                if (allTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedTagFilter == null,
                                onClick = { onTagFilterChange(null) },
                                label = { Text("All Notes", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF818CF8).copy(alpha = 0.2f),
                                    selectedLabelColor = Color(0xFF818CF8)
                                )
                            )
                        }
                        items(allTags) { tag ->
                            FilterChip(
                                selected = selectedTagFilter == tag,
                                onClick = { onTagFilterChange(if (selectedTagFilter == tag) null else tag) },
                                label = { Text("#$tag", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFA855F7).copy(alpha = 0.25f),
                                    selectedLabelColor = Color(0xFFA855F7)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Vault Drawer Dropdown List
        AnimatedVisibility(visible = showFileList) {
            Surface(
                color = Color(0xFF0F172A),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(filteredNotes) { note ->
                        val isSelected = activeNote?.id == note.id
                        Surface(
                            color = if (isSelected) Color(0xFF818CF8).copy(alpha = 0.2f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            onClick = {
                                onSelectNote(note)
                                showFileList = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = if (isSelected) Color(0xFF818CF8) else Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = note.title,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.deleteKeepNote(note) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF43F5E), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Active Note Editor View
        if (activeNote == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No note selected. Create or select a note from vault.", color = Color(0xFF64748B), fontSize = 13.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title Input + Save Action + 3-Dots Export Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = noteTitleState,
                        onValueChange = onTitleChange,
                        label = { Text("Note Title") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = onSaveActiveNote,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Box {
                        IconButton(
                            onClick = { showOptionsMenu = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Export & Options", tint = Color.White)
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export to Word (.docx)", color = Color.White, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF38BDF8)) },
                                onClick = {
                                    showOptionsMenu = false
                                    exportFormat = "DOCX"
                                    exportedDriveLink = null
                                    exportProgressLog = null
                                    showExportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export to PDF (.pdf)", color = Color.White, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFF43F5E)) },
                                onClick = {
                                    showOptionsMenu = false
                                    exportFormat = "PDF"
                                    exportedDriveLink = null
                                    exportProgressLog = null
                                    showExportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export to Plain Text (.txt)", color = Color.White, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.TextSnippet, contentDescription = null, tint = Color(0xFFFBBF24)) },
                                onClick = {
                                    showOptionsMenu = false
                                    exportFormat = "TXT"
                                    exportedDriveLink = null
                                    exportProgressLog = null
                                    showExportDialog = true
                                }
                            )
                            HorizontalDivider(color = Color(0xFF334155))
                            DropdownMenuItem(
                                text = { Text("Export as Google Doc", color = Color.White, fontSize = 13.sp) },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF34D399)) },
                                onClick = {
                                    showOptionsMenu = false
                                    exportFormat = "GOOGLE_DOC"
                                    exportedDriveLink = null
                                    exportProgressLog = null
                                    showExportDialog = true
                                }
                            )
                        }
                    }
                }

                // Editor Mode Selectors (Live Preview, Source, Reading)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = editorMode == 0,
                            onClick = { onEditorModeChange(0) },
                            label = { Text("Live Preview", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF818CF8).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF818CF8)
                            )
                        )
                        FilterChip(
                            selected = editorMode == 1,
                            onClick = { onEditorModeChange(1) },
                            label = { Text("Source Mode", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF38BDF8)
                            )
                        )
                        FilterChip(
                            selected = editorMode == 2,
                            onClick = { onEditorModeChange(2) },
                            label = { Text("Reading Mode", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF34D399).copy(alpha = 0.25f),
                                selectedLabelColor = Color(0xFF34D399)
                            )
                        )
                    }

                    // Word Count Metric
                    val words = remember(noteContentState) { noteContentState.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size }
                    Text("$words words", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }

                // Advanced Word-Style & Markdown Format Quick Toolbar
                if (editorMode != 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .background(Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToolbarFormatButton("H1", Color.White) { onContentChange(noteContentState + "\n# ") }
                        ToolbarFormatButton("H2", Color.White) { onContentChange(noteContentState + "\n## ") }
                        ToolbarFormatButton("H3", Color.White) { onContentChange(noteContentState + "\n### ") }

                        ToolbarFormatButton("**Bold**", Color.White) { onContentChange(noteContentState + " **bold**") }
                        ToolbarFormatButton("*Italic*", Color.White) { onContentChange(noteContentState + " *italic*") }

                        // Highlight Palette Dropdown
                        Box {
                            ToolbarFormatButton("==Highlight==", Color(0xFFFBBF24)) { showHighlightMenu = true }
                            DropdownMenu(
                                expanded = showHighlightMenu,
                                onDismissRequest = { showHighlightMenu = false },
                                modifier = Modifier.background(Color(0xFF0F172A))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Yellow Highlight", color = Color(0xFFFEF08A)) },
                                    onClick = {
                                        showHighlightMenu = false
                                        onContentChange(noteContentState + " ==highlighted text==")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Green Highlight", color = Color(0xFFA7F3D0)) },
                                    onClick = {
                                        showHighlightMenu = false
                                        onContentChange(noteContentState + " ==color:#A7F3D0|green highlight==")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cyan Highlight", color = Color(0xFFA5F3FC)) },
                                    onClick = {
                                        showHighlightMenu = false
                                        onContentChange(noteContentState + " ==color:#A5F3FC|cyan highlight==")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pink Highlight", color = Color(0xFFFBCFE8)) },
                                    onClick = {
                                        showHighlightMenu = false
                                        onContentChange(noteContentState + " ==color:#FBCFE8|pink highlight==")
                                    }
                                )
                            }
                        }

                        // Custom Text Color Palette Dropdown
                        Box {
                            ToolbarFormatButton("Text Color", Color(0xFFF43F5E)) { showColorMenu = true }
                            DropdownMenu(
                                expanded = showColorMenu,
                                onDismissRequest = { showColorMenu = false },
                                modifier = Modifier.background(Color(0xFF0F172A))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Red Text", color = Color(0xFFF43F5E)) },
                                    onClick = {
                                        showColorMenu = false
                                        onContentChange(noteContentState + " <color:#F43F5E>Red Text</color>")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Blue Accent", color = Color(0xFF38BDF8)) },
                                    onClick = {
                                        showColorMenu = false
                                        onContentChange(noteContentState + " <color:#38BDF8>Blue Accent</color>")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Emerald Green", color = Color(0xFF34D399)) },
                                    onClick = {
                                        showColorMenu = false
                                        onContentChange(noteContentState + " <color:#34D399>Emerald Green</color>")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Purple Glow", color = Color(0xFFA855F7)) },
                                    onClick = {
                                        showColorMenu = false
                                        onContentChange(noteContentState + " <color:#A855F7>Purple Glow</color>")
                                    }
                                )
                            }
                        }

                        // Word-Style List & Indentation Buttons
                        ToolbarFormatButton("• Bullet", Color(0xFF818CF8)) { onContentChange(noteContentState + "\n- ") }
                        ToolbarFormatButton("1. List", Color(0xFF818CF8)) { onContentChange(noteContentState + "\n1. ") }
                        ToolbarFormatButton("- [ ] Task", Color(0xFF34D399)) { onContentChange(noteContentState + "\n- [ ] ") }

                        // Indent & Outdent Shortcuts
                        ToolbarFormatButton("Indent ->", Color(0xFF38BDF8)) {
                            // Add 2 spaces indentation to active content
                            onContentChange(noteContentState + "  ")
                        }
                        ToolbarFormatButton("<- Outdent", Color(0xFF38BDF8)) {
                            if (noteContentState.endsWith("  ")) {
                                onContentChange(noteContentState.dropLast(2))
                            }
                        }

                        ToolbarFormatButton("[[Link]]", Color(0xFF38BDF8)) { onContentChange(noteContentState + " [[Note Name]]") }
                        ToolbarFormatButton("📊 Table", Color(0xFF34D399)) { onOpenAdvancedTables() }
                        ToolbarFormatButton("📚 Citation", Color(0xFFFBBF24)) { onOpenZoteroResearch() }
                        ToolbarFormatButton("[Web Link]", Color(0xFF818CF8)) { onContentChange(noteContentState + " [Link Title](https://example.com)") }
                        ToolbarFormatButton("#Tag", Color(0xFFA855F7)) { onContentChange(noteContentState + " #tag") }
                        ToolbarFormatButton("`Code`", Color(0xFFF43F5E)) { onContentChange(noteContentState + " `code`") }
                        ToolbarFormatButton("> Quote", Color(0xFF94A3B8)) { onContentChange(noteContentState + "\n> ") }
                    }
                }

                // Content View/Editor Container
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        when (editorMode) {
                            1 -> {
                                // Source Mode Raw Editor
                                OutlinedTextField(
                                    value = noteContentState,
                                    onValueChange = onContentChange,
                                    placeholder = { Text("Write Markdown here...", color = Color(0xFF64748B)) },
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF38BDF8)
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedTextColor = Color(0xFF38BDF8),
                                        unfocusedTextColor = Color(0xFF38BDF8)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 220.dp)
                                )
                            }
                            0, 2 -> {
                                // Live Preview & Reading Mode Renderer
                                val activeTitle = activeNote.title
                                RenderedMarkdownContent(
                                    content = noteContentState,
                                    keepNotes = keepNotes,
                                    onOpenWikiLink = { targetTitle ->
                                        val target = keepNotes.find { it.title.equals(targetTitle, ignoreCase = true) }
                                        if (target != null) {
                                            onSelectNote(target)
                                        } else {
                                            viewModel.insertKeepNote(
                                                title = targetTitle,
                                                content = "# $targetTitle\nCreated via WikiLink reference in [[$activeTitle]].",
                                                colorHex = "#202124"
                                            )
                                            Toast.makeText(context, "Created [[$targetTitle]]", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Backlinks & Unlinked Mentions Drawer
                ActiveNoteBacklinksPanel(
                    activeNote = activeNote,
                    allNotes = keepNotes,
                    onOpenNote = onSelectNote
                )
            }
        }
    }

    // Export Document Modal Dialog
    if (showExportDialog && activeNote != null) {
        val currNote = activeNote
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = Color(0xFF1E293B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (exportFormat) {
                            "PDF" -> Icons.Default.PictureAsPdf
                            "DOCX" -> Icons.Default.Description
                            "TXT" -> Icons.Default.TextSnippet
                            else -> Icons.Default.CloudUpload
                        },
                        contentDescription = null,
                        tint = Color(0xFF818CF8)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export Document: $exportFormat", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose export destination for \"${currNote.title}\":", color = Color(0xFFCBD5E1), fontSize = 12.sp)

                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Document Details:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Title: ${currNote.title}", color = Color.White, fontSize = 12.sp)
                            Text("Format: $exportFormat (Clean rendered document without raw Markdown symbols)", color = Color(0xFF34D399), fontSize = 11.sp)
                        }
                    }

                    if (exportProgressLog != null) {
                        Surface(
                            color = Color(0xFF090D16),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = exportProgressLog!!,
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (exportedDriveLink != null) {
                        Surface(
                            color = Color(0xFF34D399).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF34D399)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Shared Link Ready:", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(exportedDriveLink!!, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Drive Link", exportedDriveLink))
                                            Toast.makeText(context, "Copied link to clipboard!", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Copy Link", fontSize = 11.sp, color = Color.Black)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(exportedDriveLink))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Open Link", fontSize = 11.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Option 1: Export Local File
                    Button(
                        onClick = {
                            scope.launch {
                                exportProgressLog = "Generating clean local $exportFormat file..."
                                val cleanText = generateCleanDocumentText(currNote.title, noteContentState)

                                val file: File? = when (exportFormat) {
                                    "PDF" -> generatePdfFile(context, currNote.title, noteContentState)
                                    "DOCX" -> generateDocxFile(context, currNote.title, cleanText)
                                    else -> generateTxtFile(context, currNote.title, cleanText)
                                }

                                if (file != null && file.exists()) {
                                    exportProgressLog = "Saved locally: ${file.name}"
                                    Toast.makeText(context, "Exported clean document to local storage", Toast.LENGTH_SHORT).show()
                                    shareFileLocal(context, file, exportFormat)
                                } else {
                                    exportProgressLog = "Failed to create local document file."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Export Local File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Option 2: Upload to Google Drive & Share Link
                    Button(
                        onClick = {
                            scope.launch {
                                exportProgressLog = "Creating clean document on Google Drive..."
                                val cleanText = generateCleanDocumentText(currNote.title, noteContentState)

                                if (exportFormat == "GOOGLE_DOC" || exportFormat == "DOCX") {
                                    val result = GoogleDriveSyncManager.createGoogleDocWithContent(
                                        context = context,
                                        title = currNote.title,
                                        content = cleanText
                                    )
                                    if (result.first) {
                                        exportedDriveLink = result.second
                                        exportProgressLog = "Google Doc generated successfully!"
                                    } else {
                                        exportProgressLog = "Drive Upload Error: ${result.second}"
                                    }
                                } else {
                                    val file = if (exportFormat == "PDF") {
                                        generatePdfFile(context, currNote.title, noteContentState)
                                    } else {
                                        generateTxtFile(context, currNote.title, cleanText)
                                    }

                                    if (file != null && file.exists()) {
                                        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        val token = prefs.getString("google_oauth_token", "") ?: ""
                                        if (token.isNotEmpty()) {
                                            val link = GoogleDriveSyncManager.uploadPublicMediaFileDirect(
                                                context = context,
                                                accessToken = token,
                                                file = file,
                                                categoryFolder = "General_Files"
                                            )
                                            if (link != null) {
                                                exportedDriveLink = link
                                                exportProgressLog = "Uploaded file to Google Drive and generated sharing link!"
                                            } else {
                                                exportProgressLog = "Uploaded file to Drive."
                                            }
                                        } else {
                                            exportProgressLog = "Please sign in with Google Drive first."
                                        }
                                    } else {
                                        exportProgressLog = "Failed to generate file."
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Upload Drive Link", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close", color = Color.Gray, fontSize = 12.sp)
                }
            }
        )
    }
}

@Composable
private fun ToolbarFormatButton(label: String, color: Color, onClick: () -> Unit) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
        onClick = onClick
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ==========================================
// RENDERED MARKDOWN & WORD-STYLE LIST ENGINE
// ==========================================
@Composable
private fun RenderedMarkdownContent(
    content: String,
    keepNotes: List<KeepNote>,
    onOpenWikiLink: (String) -> Unit
) {
    val lines = content.split("\n")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            // Calculate leading indentation space count
            val indentSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
            val indentLevel = indentSpaces / 2
            val trimmedLine = line.trimStart()

            when {
                trimmedLine.startsWith("# ") -> {
                    Text(
                        text = trimmedLine.removePrefix("# "),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                trimmedLine.startsWith("## ") -> {
                    Text(
                        text = trimmedLine.removePrefix("## "),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF818CF8)
                    )
                }
                trimmedLine.startsWith("### ") -> {
                    Text(
                        text = trimmedLine.removePrefix("### "),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
                trimmedLine.startsWith("> ") -> {
                    // Indented Quote Block
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (indentLevel * 16).dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(22.dp)
                                .background(Color(0xFF818CF8))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = trimmedLine.removePrefix("> "),
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
                trimmedLine.startsWith("- [ ] ") -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = (indentLevel * 16).dp)
                    ) {
                        Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        MarkdownInlineFormattedText(text = trimmedLine.removePrefix("- [ ] "), onOpenWikiLink = onOpenWikiLink)
                    }
                }
                trimmedLine.startsWith("- [x] ") || trimmedLine.startsWith("- [X] ") -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = (indentLevel * 16).dp)
                    ) {
                        Icon(Icons.Default.CheckBox, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = trimmedLine.removePrefix("- [x] ").removePrefix("- [X] "), fontSize = 12.sp, color = Color.Gray)
                    }
                }
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                    // Word-style Hierarchical Bullets per indent depth
                    val bulletChar = when (indentLevel % 4) {
                        0 -> "• "
                        1 -> "◦ "
                        2 -> "▪ "
                        else -> "› "
                    }
                    val bulletColor = when (indentLevel % 3) {
                        0 -> Color(0xFF818CF8)
                        1 -> Color(0xFF38BDF8)
                        else -> Color(0xFF34D399)
                    }

                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(start = (indentLevel * 16).dp)
                    ) {
                        Text(bulletChar, color = bulletColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        MarkdownInlineFormattedText(
                            text = trimmedLine.removePrefix("- ").removePrefix("* "),
                            onOpenWikiLink = onOpenWikiLink
                        )
                    }
                }
                trimmedLine.matches(Regex("""^\d+\.\s+.*""")) -> {
                    // Numbered List
                    val parts = trimmedLine.split(Regex("""\.\s+"""), limit = 2)
                    val num = parts.getOrNull(0) ?: "1"
                    val rest = parts.getOrNull(1) ?: ""

                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(start = (indentLevel * 16).dp)
                    ) {
                        Text("$num. ", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        MarkdownInlineFormattedText(text = rest, onOpenWikiLink = onOpenWikiLink)
                    }
                }
                trimmedLine.startsWith("|") && trimmedLine.endsWith("|") -> {
                    // Markdown Table Line Rendering
                    val cells = trimmedLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    val isSeparatorLine = cells.all { it.contains("---") || it.contains(":-") || it.contains("-:") }
                    if (!isSeparatorLine) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            cells.forEach { cellText ->
                                Surface(
                                    color = Color(0xFF1E293B),
                                    border = BorderStroke(0.5.dp, Color(0xFF334155)),
                                    modifier = Modifier.width(110.dp)
                                ) {
                                    Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                        MarkdownInlineFormattedText(text = cellText, onOpenWikiLink = onOpenWikiLink)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Row(modifier = Modifier.padding(start = (indentLevel * 16).dp)) {
                        MarkdownInlineFormattedText(text = trimmedLine, onOpenWikiLink = onOpenWikiLink)
                    }
                }
            }
        }
    }
}

// ==========================================
// INLINE TEXT FORMATTING & LINK RECOGNITION
// ==========================================
@Composable
private fun MarkdownInlineFormattedText(
    text: String,
    onOpenWikiLink: (String) -> Unit
) {
    val context = LocalContext.current

    // Parse [[WikiLinks]], web links (https://...), highlights (==text==), colors (<color:#HEX>text</color>), bold, italic, code
    val wikiRegex = Regex("\\[\\[(.*?)\\]\\]")
    val webLinkRegex = Regex("""(https?://[^\s]+)""")
    val highlightRegex = Regex("==color:(#[0-9a-fA-F]+)\\|(.*?)==|==(.*?)==")
    val colorTagRegex = Regex("""<color:(#[0-9a-fA-F]+)>(.*?)</color>""")

    if (text.isBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        return
    }

    // Check for Citations [@citekey]
    val citeRegex = Regex("""\[@([a-zA-Z0-9_-]+)\]""")
    val citeMatches = citeRegex.findAll(text).toList()
    if (citeMatches.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            var currentIndex = 0
            citeMatches.forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val citeKey = match.groupValues[1]

                if (start > currentIndex) {
                    Text(text = text.substring(currentIndex, start), color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }

                Surface(
                    color = Color(0xFFFBBF24).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f)),
                    onClick = {
                        Toast.makeText(context, "Citation Reference [@$citeKey] in Zotero Suite", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = "@$citeKey", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                currentIndex = end
            }

            if (currentIndex < text.length) {
                Text(text = text.substring(currentIndex), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        return
    }

    // Check for WikiLinks
    val wikiMatches = wikiRegex.findAll(text).toList()
    val webMatches = webLinkRegex.findAll(text).toList()

    if (wikiMatches.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            var currentIndex = 0
            wikiMatches.forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val linkTitle = match.groupValues[1]

                if (start > currentIndex) {
                    Text(text = text.substring(currentIndex, start), color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }

                Surface(
                    color = Color(0xFF818CF8).copy(alpha = 0.25f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.5f)),
                    onClick = { onOpenWikiLink(linkTitle) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = linkTitle, color = Color(0xFF818CF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                currentIndex = end
            }

            if (currentIndex < text.length) {
                Text(text = text.substring(currentIndex), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        return
    }

    // Check for Web Links
    if (webMatches.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            var currentIndex = 0
            webMatches.forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val url = match.groupValues[1]

                if (start > currentIndex) {
                    Text(text = text.substring(currentIndex, start), color = Color(0xFFCBD5E1), fontSize = 12.sp)
                }

                Surface(
                    color = Color(0xFF38BDF8).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "URL: $url", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = url, color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                currentIndex = end
            }

            if (currentIndex < text.length) {
                Text(text = text.substring(currentIndex), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        return
    }

    // Check for Highlights (==text== or ==color:#HEX|text==)
    val highlightMatch = highlightRegex.find(text)
    if (highlightMatch != null) {
        val customHex = highlightMatch.groupValues[1]
        val highlightedText = if (customHex.isNotEmpty()) highlightMatch.groupValues[2] else highlightMatch.groupValues[3]
        val highlightBg = if (customHex.isNotEmpty()) parseObsidianColorHex(customHex) else Color(0xFFFEF08A)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = text.substring(0, highlightMatch.range.first), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            Surface(
                color = highlightBg,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                Text(
                    text = highlightedText,
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Text(text = text.substring(highlightMatch.range.last + 1), color = Color(0xFFCBD5E1), fontSize = 12.sp)
        }
        return
    }

    // Check for Color Spans (<color:#HEX>text</color>)
    val colorMatch = colorTagRegex.find(text)
    if (colorMatch != null) {
        val hex = colorMatch.groupValues[1]
        val coloredText = colorMatch.groupValues[2]
        val textColor = parseObsidianColorHex(hex)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = text.substring(0, colorMatch.range.first), color = Color(0xFFCBD5E1), fontSize = 12.sp)
            Text(text = coloredText, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text = text.substring(colorMatch.range.last + 1), color = Color(0xFFCBD5E1), fontSize = 12.sp)
        }
        return
    }

    // Standard Markdown Text
    Text(
        text = text
            .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
            .replace(Regex("""\*(.*?)\*"""), "$1")
            .replace(Regex("""`(.*?)`"""), "$1"),
        color = Color(0xFFCBD5E1),
        fontSize = 12.sp,
        lineHeight = 18.sp
    )
}

@Composable
private fun ActiveNoteBacklinksPanel(
    activeNote: KeepNote,
    allNotes: List<KeepNote>,
    onOpenNote: (KeepNote) -> Unit
) {
    val backlinks = remember(activeNote, allNotes) {
        allNotes.filter { note ->
            note.id != activeNote.id && note.content.contains("[[${activeNote.title}]]", ignoreCase = true)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vault Backlinks (${backlinks.size})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text("References to [[${activeNote.title}]]", color = Color(0xFF94A3B8), fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (backlinks.isEmpty()) {
                Text("No other vault notes link to this note yet. Use [[${activeNote.title}]] in another note to create a backlink.", color = Color(0xFF64748B), fontSize = 11.sp)
            } else {
                backlinks.forEach { backlinkNote ->
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(8.dp),
                        onClick = { onOpenNote(backlinkNote) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(backlinkNote.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// EXPORT GENERATION UTILITY FUNCTIONS
// ==========================================
fun stripMarkdownSyntax(raw: String): String {
    return raw
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        .replace(Regex("""\*(.*?)\*"""), "$1")
        .replace(Regex("""==color:#[0-9a-fA-F]+\|(.*?)==|==(.*?)==|==color:\w+\|(.*?)==|==color:\w+==|==(.*?)"""), "$1$2$3")
        .replace(Regex("""<color:#[0-9a-fA-F]+>(.*?)</color>"""), "$1")
        .replace(Regex("""\[\[(.*?)\]\]"""), "$1")
        .replace(Regex("""\[(.*?)\]\((.*?)\)"""), "$1 ($2)")
        .replace(Regex("""`{1,3}(.*?)`{1,3}"""), "$1")
        .replace(Regex("""^\s*-\s*\[[ xX]\]\s*"""), "[ ] ")
        .replace(Regex("""^\s*-\s+"""), "• ")
        .trim()
}

fun generateCleanDocumentText(title: String, rawContent: String): String {
    val sb = StringBuilder()
    sb.append("Document Title: ").append(title).append("\n")
    sb.append("Date: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("\n")
    sb.append("--------------------------------------------------\n\n")

    val lines = rawContent.split("\n")
    lines.forEach { line ->
        sb.append(stripMarkdownSyntax(line)).append("\n")
    }
    return sb.toString()
}

fun generateTxtFile(context: Context, title: String, cleanContent: String): File? {
    return try {
        val fileName = "${title.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.txt"
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { fos ->
            fos.write(cleanContent.toByteArray(Charsets.UTF_8))
        }
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun generateDocxFile(context: Context, title: String, cleanContent: String): File? {
    return try {
        val fileName = "${title.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.docx"
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { fos ->
            fos.write(cleanContent.toByteArray(Charsets.UTF_8))
        }
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun generatePdfFile(context: Context, title: String, rawContent: String): File? {
    return try {
        val pdfDocument = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint()

        paint.color = android.graphics.Color.BLACK
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText(title, 40f, 50f, paint)

        paint.textSize = 10f
        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.DKGRAY
        canvas.drawText("Exported from Obsidian Vault Studio • ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}", 40f, 70f, paint)

        paint.strokeWidth = 1f
        canvas.drawLine(40f, 85f, 555f, 85f, paint)

        paint.textSize = 11f
        paint.color = android.graphics.Color.BLACK
        var y = 110f
        val lines = rawContent.split("\n")

        for (line in lines) {
            if (y > 800f) break
            val clean = stripMarkdownSyntax(line)
            if (clean.isBlank()) {
                y += 10f
                continue
            }
            val indentSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
            val xPos = 40f + (indentSpaces * 8f)

            if (line.trimStart().startsWith("# ")) {
                paint.textSize = 16f
                paint.isFakeBoldText = true
                canvas.drawText(clean, xPos, y, paint)
                y += 22f
            } else if (line.trimStart().startsWith("## ")) {
                paint.textSize = 14f
                paint.isFakeBoldText = true
                canvas.drawText(clean, xPos, y, paint)
                y += 18f
            } else {
                paint.textSize = 11f
                paint.isFakeBoldText = false
                canvas.drawText(clean, xPos, y, paint)
                y += 16f
            }
        }

        pdfDocument.finishPage(page)
        val fileName = "${title.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.pdf"
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { fos ->
            pdfDocument.writeTo(fos)
        }
        pdfDocument.close()
        file
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun shareFileLocal(context: Context, file: File, format: String) {
    try {
        val mime = when (format) {
            "PDF" -> "application/pdf"
            "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "text/plain"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, "Clean Document Export: ${file.name}")
        }
        context.startActivity(Intent.createChooser(intent, "Share Clean Document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Saved file to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

private fun parseObsidianColorHex(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color(0xFF818CF8)
    }
}

private fun refactorVaultWikiLinks(
    viewModel: AppViewModel,
    allNotes: List<KeepNote>,
    oldTitle: String,
    newTitle: String
) {
    val oldPattern = "[[$oldTitle]]"
    val newPattern = "[[$newTitle]]"

    allNotes.forEach { note ->
        if (note.content.contains(oldPattern, ignoreCase = true)) {
            val updatedContent = note.content.replace(oldPattern, newPattern, ignoreCase = true)
            viewModel.updateKeepNote(note.copy(content = updatedContent))
        }
    }
}

// ==========================================
// TAB 2: DYNAMIC VAULT GRAPH VIEW
// ==========================================
@Composable
private fun VaultGraphViewTab(
    keepNotes: List<KeepNote>,
    onOpenNoteByTitle: (String) -> Unit
) {
    var selectedGraphNode by remember { mutableStateOf<KeepNote?>(null) }

    val graphNodesWithPos = remember(keepNotes) {
        val count = keepNotes.size
        keepNotes.mapIndexed { index, note ->
            val angle = (2 * Math.PI * index / count).toFloat()
            val radius = 0.35f
            val x = 0.5f + radius * cos(angle)
            val y = 0.5f + radius * sin(angle)
            Pair(note, Offset(x, y))
        }
    }

    val graphEdges = remember(keepNotes) {
        val edges = mutableListOf<Pair<KeepNote, KeepNote>>()
        keepNotes.forEach { noteA ->
            keepNotes.forEach { noteB ->
                if (noteA.id != noteB.id && noteA.content.contains("[[${noteB.title}]]", ignoreCase = true)) {
                    edges.add(Pair(noteA, noteB))
                }
            }
        }
        edges.distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Global In-Memory Graph View", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vault Connections Map", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Nodes: ${keepNotes.size} | Edges: ${graphEdges.size}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0B0F19))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height

                        graphEdges.forEach { edge ->
                            val posA = graphNodesWithPos.find { it.first.id == edge.first.id }?.second
                            val posB = graphNodesWithPos.find { it.first.id == edge.second.id }?.second
                            if (posA != null && posB != null) {
                                drawLine(
                                    color = Color(0xFF818CF8).copy(alpha = 0.6f),
                                    start = Offset(posA.x * w, posA.y * h),
                                    end = Offset(posB.x * w, posB.y * h),
                                    strokeWidth = 3f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                )
                            }
                        }

                        graphNodesWithPos.forEach { (note, pos) ->
                            val center = Offset(pos.x * w, pos.y * h)
                            val isSelected = selectedGraphNode?.id == note.id

                            drawCircle(
                                color = if (isSelected) Color.White else Color(0xFFA855F7).copy(alpha = 0.3f),
                                radius = if (isSelected) 26f else 20f,
                                center = center
                            )
                            drawCircle(
                                color = if (note.isPinned) Color(0xFFFBBF24) else Color(0xFFA855F7),
                                radius = if (isSelected) 16f else 12f,
                                center = center
                            )
                        }
                    }

                    graphNodesWithPos.forEach { (note, pos) ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            Button(
                                onClick = {
                                    selectedGraphNode = note
                                    onOpenNoteByTitle(note.title)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier
                                    .size(52.dp)
                                    .offset(
                                        x = (pos.x * 290 - 26).dp,
                                        y = (pos.y * 200 - 26).dp
                                    )
                            ) {}
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Tap any note node to navigate directly into editor:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(keepNotes) { note ->
                        Surface(
                            color = Color(0xFFA855F7).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.4f)),
                            onClick = { onOpenNoteByTitle(note.title) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (note.isPinned) Color(0xFFFBBF24) else Color(0xFFA855F7))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(note.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 3: EMBEDDED DRIVE SYNC
// ==========================================
@Composable
private fun DriveSyncEmbeddedTab(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isSyncing by remember { mutableStateOf(false) }
    var syncLogText by remember { mutableStateOf("Drive sync ready.") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Obsidian Cloud Vault Sync (Google Drive)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("One-Tap Drive Backup & Multi-Device Sync", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Sync your entire Obsidian local vault, notes, graph relationships, and settings securely to your Google Drive account.", color = Color(0xFFCBD5E1), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(14.dp))

                if (isSyncing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color(0xFF818CF8), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing with Google Drive...", color = Color.White, fontSize = 12.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isSyncing = true
                                    syncLogText = "Backing up Obsidian vault to Google Drive..."
                                    val (ok, msg) = GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                    syncLogText = if (ok) "Vault backed up successfully!" else "Backup failed: $msg"
                                    isSyncing = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF818CF8)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Push Backup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isSyncing = true
                                    syncLogText = "Restoring Obsidian vault from Google Drive..."
                                    val (ok, msg) = GoogleDriveSyncManager.restoreAllAppData(context, viewModel.appDatabase)
                                    syncLogText = if (ok) "Vault restored successfully!" else "Restore failed: $msg"
                                    isSyncing = false
                                }
                            },
                            border = BorderStroke(1.dp, Color(0xFF34D399)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pull Restore", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = Color(0xFF090D16),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Status: $syncLogText",
                        color = Color(0xFF34D399),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// TAB 4: CORE ARCHITECTURE SPECS
// ==========================================
@Composable
private fun CoreArchitectureSpecsTab(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("How Obsidian Operates (Core Technical Architecture)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Obsidian operates fundamentally differently from cloud-native software like Notion, Evernote, or Google Docs. Instead of saving your data to a remote corporate database, Obsidian treats a normal folder on your local machine—called a \"Vault\"—as its primary database.\n\n" +
                            "1. Local-First Engine: Every single note is stored directly on your disk as a plain-text Markdown (.md) file.\n" +
                            "2. In-Memory Graph Indexing: Obsidian background-scans all files to build an in-memory database of links, tags, and headings for instant Graph View and backlinks.\n" +
                            "3. Bi-Directional Refactoring: [[WikiLink]] references are dynamically refactored when files are renamed.\n" +
                            "4. Word-Style Document & Export Engine: Advanced formatting shortcuts, hierarchical points & sub-points with indentation recognition, and export to DOCX, PDF, or Google Drive links with clean Markdown stripping.\n" +
                            "5. Advanced Tables Suite: Interactive Excel-like matrix with live formula evaluation (=SUM, =AVG, =COUNT, =MIN, =MAX), row/column insertion, and GFM table generation.\n" +
                            "6. Zotero Research Integration: Citation search, BibTeX parsing, inline [@citekey] insertion, and automated bibliography generator.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ==========================================
// TAB 2: ADVANCED TABLES (EXCEL SHEETS)
// ==========================================
@Composable
private fun AdvancedTablesTab(
    keepNotes: List<KeepNote>,
    activeNote: KeepNote?,
    onUpdateNoteContent: (String) -> Unit,
    onNavigateToEditor: () -> Unit
) {
    val context = LocalContext.current
    var selectedNote by remember { mutableStateOf<KeepNote?>(activeNote ?: keepNotes.firstOrNull()) }

    // Table Matrix State
    val headers = remember { mutableStateListOf("Item / Variable", "Category", "Quantity", "Unit Cost ($)", "Total ($)") }
    val rows = remember {
        mutableStateListOf(
            mutableStateListOf("High-Perf GPU Servers", "Hardware", "4", "2500", "=C1*D1"),
            mutableStateListOf("SSD Storage Arrays", "Hardware", "10", "300", "=C2*D2"),
            mutableStateListOf("Zotero Academic License", "Software", "1", "120", "=C3*D3"),
            mutableStateListOf("Total Benchmark Expense", "Summary", "-", "-", "=SUM(E1:E3)")
        )
    }

    var activeRowIndex by remember { mutableStateOf<Int?>(null) }
    var activeColIndex by remember { mutableStateOf<Int?>(null) }
    var rawInputValue by remember { mutableStateOf("") }
    var showImportMenu by remember { mutableStateOf(false) }

    // Synchronize formula bar input when active cell changes
    LaunchedEffect(activeRowIndex, activeColIndex) {
        val r = activeRowIndex
        val c = activeColIndex
        if (r != null && c != null && r in rows.indices && c in rows[r].indices) {
            rawInputValue = rows[r][c]
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Banner Header
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFF34D399).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TableChart, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Advanced Tables Engine (Excel Sheets in Markdown)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Live formula calculation (=SUM, =AVG, =COUNT, =MIN, =MAX), row/column controls, & auto-formatting.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val markdownTable = generateMarkdownTable(headers, rows)
                        val currContent = selectedNote?.content ?: ""
                        val updatedContent = if (currContent.isBlank()) markdownTable else "$currContent\n\n$markdownTable"
                        onUpdateNoteContent(updatedContent)
                        Toast.makeText(context, "Inserted Advanced Table into '${selectedNote?.title ?: "Note"}'", Toast.LENGTH_SHORT).show()
                        onNavigateToEditor()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34D399)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Insert to Note", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Note Selector & Import / Export Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Note Dropdown Picker
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { showImportMenu = true },
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedNote?.title ?: "Select Vault Note",
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false },
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    keepNotes.forEach { note ->
                        DropdownMenuItem(
                            text = { Text(note.title, color = Color.White, fontSize = 12.sp) },
                            onClick = {
                                selectedNote = note
                                showImportMenu = false
                                val parsed = parseMarkdownTable(note.content)
                                if (parsed != null) {
                                    headers.clear()
                                    headers.addAll(parsed.first)
                                    rows.clear()
                                    parsed.second.forEach { row ->
                                        rows.add(row.toMutableStateList())
                                    }
                                    Toast.makeText(context, "Loaded table from '${note.title}'", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No table found in '${note.title}'", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            // Export CSV
            OutlinedButton(
                onClick = {
                    val csvText = buildString {
                        append(headers.joinToString(","))
                        append("\n")
                        rows.forEach { row ->
                            append(row.joinToString(","))
                            append("\n")
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("CSV Export", csvText))
                    Toast.makeText(context, "Copied CSV to clipboard!", Toast.LENGTH_SHORT).show()
                },
                border = BorderStroke(1.dp, Color(0xFF818CF8)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy CSV", color = Color(0xFF818CF8), fontSize = 11.sp)
            }
        }

        // Spreadsheet Toolbar (Row/Column Actions & Presets)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                Button(
                    onClick = {
                        val newRow = MutableList(headers.size) { "" }.toMutableStateList()
                        rows.add(newRow)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("+ Row", color = Color(0xFF38BDF8), fontSize = 11.sp)
                }
            }
            item {
                Button(
                    onClick = {
                        if (rows.isNotEmpty()) rows.removeAt(rows.size - 1)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFF43F5E)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("- Row", color = Color(0xFFF43F5E), fontSize = 11.sp)
                }
            }
            item {
                Button(
                    onClick = {
                        val newColName = "Col ${('A' + headers.size)}"
                        headers.add(newColName)
                        rows.forEach { it.add("") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFF34D399)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("+ Col", color = Color(0xFF34D399), fontSize = 11.sp)
                }
            }
            item {
                Button(
                    onClick = {
                        if (headers.size > 1) {
                            headers.removeAt(headers.size - 1)
                            rows.forEach { if (it.isNotEmpty()) it.removeAt(it.size - 1) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFF43F5E)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("- Col", color = Color(0xFFF43F5E), fontSize = 11.sp)
                }
            }
            item {
                Button(
                    onClick = {
                        // Presets: Project Budget
                        headers.clear()
                        headers.addAll(listOf("Task", "Assignee", "Est Hours", "Rate ($)", "Cost ($)"))
                        rows.clear()
                        rows.add(mutableStateListOf("Architecture Specs", "Dev 1", "20", "80", "=C1*D1"))
                        rows.add(mutableStateListOf("Room DB Integration", "Dev 2", "15", "85", "=C2*D2"))
                        rows.add(mutableStateListOf("Zotero Research Module", "Dev 1", "25", "90", "=C3*D3"))
                        rows.add(mutableStateListOf("Total Budget", "All", "-", "-", "=SUM(E1:E3)"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Preset: Budget", color = Color(0xFFFBBF24), fontSize = 11.sp)
                }
            }
            item {
                Button(
                    onClick = {
                        // Presets: ML Benchmarks
                        headers.clear()
                        headers.addAll(listOf("Model", "Params", "BLEU", "Latency", "Score"))
                        rows.clear()
                        rows.add(mutableStateListOf("Transformer-Base", "65M", "27.3", "42ms", "92.4"))
                        rows.add(mutableStateListOf("BERT-Large", "340M", "28.1", "68ms", "94.8"))
                        rows.add(mutableStateListOf("ResNet-151", "60M", "24.5", "35ms", "88.2"))
                        rows.add(mutableStateListOf("Benchmark Avg", "155M", "=AVG(C1:C3)", "-", "=AVG(E1:E3)"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, Color(0xFFA855F7)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Preset: ML Benchmarks", color = Color(0xFFA855F7), fontSize = 11.sp)
                }
            }
        }

        // Formula & Cell Editor Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val cellCoord = if (activeRowIndex != null && activeColIndex != null) {
                "${('A' + activeColIndex!!)}${activeRowIndex!! + 1}"
            } else "fx"

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8))
            ) {
                Text(
                    text = cellCoord,
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            OutlinedTextField(
                value = rawInputValue,
                onValueChange = { input ->
                    rawInputValue = input
                    val r = activeRowIndex
                    val c = activeColIndex
                    if (r != null && c != null && r in rows.indices && c in rows[r].indices) {
                        rows[r][c] = input
                    }
                },
                placeholder = { Text("Enter text, number, or formula (e.g. =SUM(C1:C3), =C1*D1)", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF34D399),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // Spreadsheet Grid Container
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                // Table Header Row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Row number corner box
                    Surface(
                        color = Color(0xFF1E293B),
                        modifier = Modifier
                            .width(36.dp)
                            .height(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("#", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    headers.forEachIndexed { colIdx, headerName ->
                        val colLetter = ('A' + colIdx).toString()
                        Surface(
                            color = Color(0xFF1E293B),
                            border = BorderStroke(0.5.dp, Color(0xFF334155)),
                            modifier = Modifier
                                .width(130.dp)
                                .height(32.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$colLetter: $headerName", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Table Data Rows
                rows.forEachIndexed { rowIdx, rowList ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Row Index Indicator
                        Surface(
                            color = Color(0xFF1E293B),
                            border = BorderStroke(0.5.dp, Color(0xFF334155)),
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${rowIdx + 1}", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        rowList.forEachIndexed { colIdx, cellValue ->
                            val isSelected = activeRowIndex == rowIdx && activeColIndex == colIdx
                            val evaluatedVal = evaluateTableCell(cellValue, headers, rows, rowIdx, colIdx)

                            Surface(
                                color = if (isSelected) Color(0xFF1E293B) else if (rowIdx % 2 == 0) Color(0xFF0F172A) else Color(0xFF090D16),
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 0.5.dp,
                                    if (isSelected) Color(0xFF34D399) else Color(0xFF334155)
                                ),
                                onClick = {
                                    activeRowIndex = rowIdx
                                    activeColIndex = colIdx
                                    rawInputValue = cellValue
                                },
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(36.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = evaluatedVal,
                                        color = if (cellValue.startsWith("=")) Color(0xFF34D399) else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (cellValue.startsWith("=")) FontWeight.Bold else FontWeight.Normal,
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

// ==========================================
// FORMULA EVALUATION ENGINE
// ==========================================
private fun evaluateTableCell(raw: String, headers: List<String>, rows: List<List<String>>, rowIdx: Int, colIdx: Int): String {
    if (!raw.startsWith("=")) return raw
    val expr = raw.substring(1).trim().uppercase()
    return try {
        when {
            expr.startsWith("SUM(") -> {
                val arg = expr.removePrefix("SUM(").removeSuffix(")")
                val vals = parseValuesForRange(arg, headers, rows)
                val sum = vals.sum()
                if (sum % 1.0 == 0.0) sum.toLong().toString() else String.format(Locale.US, "%.2f", sum)
            }
            expr.startsWith("AVG(") || expr.startsWith("AVERAGE(") -> {
                val arg = expr.removePrefix("AVERAGE(").removePrefix("AVG(").removeSuffix(")")
                val vals = parseValuesForRange(arg, headers, rows)
                if (vals.isEmpty()) "0" else {
                    val avg = vals.average()
                    if (avg % 1.0 == 0.0) avg.toLong().toString() else String.format(Locale.US, "%.2f", avg)
                }
            }
            expr.startsWith("COUNT(") -> {
                val arg = expr.removePrefix("COUNT(").removeSuffix(")")
                val vals = parseValuesForRange(arg, headers, rows)
                vals.size.toString()
            }
            expr.contains("*") -> {
                val parts = expr.split("*")
                if (parts.size == 2) {
                    val v1 = parseSingleCellVal(parts[0].trim(), headers, rows)
                    val v2 = parseSingleCellVal(parts[1].trim(), headers, rows)
                    val res = v1 * v2
                    if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
                } else raw
            }
            else -> raw
        }
    } catch (e: Exception) {
        "#VALUE!"
    }
}

private fun parseSingleCellVal(cellRef: String, headers: List<String>, rows: List<List<String>>): Double {
    if (cellRef.length >= 2 && cellRef[0] in 'A'..'Z') {
        val colIdx = cellRef[0] - 'A'
        val rowNum = cellRef.substring(1).toIntOrNull() ?: 1
        val rIdx = rowNum - 1
        if (rIdx in rows.indices && colIdx in rows[rIdx].indices) {
            val cellStr = rows[rIdx][colIdx].replace("$", "").replace("ms", "").trim()
            return cellStr.toDoubleOrNull() ?: 0.0
        }
    }
    return cellRef.toDoubleOrNull() ?: 0.0
}

private fun parseValuesForRange(rangeStr: String, headers: List<String>, rows: List<List<String>>): List<Double> {
    val result = mutableListOf<Double>()
    if (rangeStr.contains(":")) {
        val parts = rangeStr.split(":")
        val startRef = parts[0].trim()
        val endRef = parts[1].trim()
        if (startRef.length >= 2 && endRef.length >= 2) {
            val startCol = startRef[0] - 'A'
            val startRow = (startRef.substring(1).toIntOrNull() ?: 1) - 1
            val endCol = endRef[0] - 'A'
            val endRow = (endRef.substring(1).toIntOrNull() ?: rows.size) - 1

            for (r in startRow..endRow) {
                if (r in rows.indices) {
                    for (c in startCol..endCol) {
                        if (c in rows[r].indices) {
                            val raw = rows[r][c].replace("$", "").replace("M", "").replace("ms", "").trim()
                            val d = raw.toDoubleOrNull()
                            if (d != null) result.add(d)
                        }
                    }
                }
            }
        }
    } else if (rangeStr.length == 1 && rangeStr[0] in 'A'..'Z') {
        val c = rangeStr[0] - 'A'
        rows.forEach { r ->
            if (c in r.indices) {
                val d = r[c].replace("$", "").replace("M", "").replace("ms", "").trim().toDoubleOrNull()
                if (d != null) result.add(d)
            }
        }
    }
    return result
}

private fun generateMarkdownTable(headers: List<String>, rows: List<List<String>>): String {
    return buildString {
        append("| ").append(headers.joinToString(" | ")).append(" |\n")
        append("| ").append(headers.map { ":---" }.joinToString(" | ")).append(" |\n")
        rows.forEach { row ->
            append("| ").append(row.joinToString(" | ")).append(" |\n")
        }
    }
}

private fun parseMarkdownTable(content: String): Pair<List<String>, List<List<String>>>? {
    val lines = content.split("\n").map { it.trim() }.filter { it.startsWith("|") && it.endsWith("|") }
    if (lines.size < 2) return null
    val headers = lines[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    val dataLines = lines.filterIndexed { index, _ -> index != 1 } // skip divider line
    val dataRows = dataLines.drop(1).map { line ->
        line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }
    return Pair(headers, dataRows)
}

// ==========================================
// TAB 3: ZOTERO ACADEMIC RESEARCH SUITE
// ==========================================
data class ZoteroItem(
    val citeKey: String,
    val title: String,
    val authors: String,
    val venue: String,
    val year: String,
    val doi: String,
    val abstractText: String,
    val collection: String,
    val bibtex: String
)

@Composable
private fun ZoteroResearchTab(
    activeNote: KeepNote?,
    onInsertCitation: (String) -> Unit,
    onAppendBibliography: (String) -> Unit,
    onNavigateToEditor: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCollection by remember { mutableStateOf("All") }
    var showBibTeXDialog by remember { mutableStateOf(false) }
    var bibtexInputState by remember { mutableStateOf("") }

    val defaultLibrary = remember {
        mutableStateListOf(
            ZoteroItem(
                citeKey = "vaswani2017transformer",
                title = "Attention Is All You Need",
                authors = "Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones, Aidan N. Gomez, Łukasz Kaiser, Illia Polosukhin",
                venue = "Advances in Neural Information Processing Systems (NIPS 2017)",
                year = "2017",
                doi = "10.48550/arXiv.1706.03762",
                abstractText = "The dominant sequence transduction models are based on complex recurrent or convolutional neural networks. We propose the Transformer, a model architecture eschewing recurrence and relying entirely on an attention mechanism to draw global dependencies between input and output.",
                collection = "Artificial Intelligence",
                bibtex = "@inproceedings{vaswani2017attention,\n  title={Attention is all you need},\n  author={Vaswani, Ashish and Shazeer, Noam and Parmar, Niki and Uszkoreit, Jakob and Jones, Llion and Gomez, Aidan N and Kaiser, {\\L}ukasz and Polosukhin, Illia},\n  booktitle={NIPS},\n  year={2017}\n}"
            ),
            ZoteroItem(
                citeKey = "he2016resnet",
                title = "Deep Residual Learning for Image Recognition",
                authors = "Kaiming He, Xiangyu Zhang, Shaoqing Ren, Jian Sun",
                venue = "IEEE Conference on Computer Vision and Pattern Recognition (CVPR 2016)",
                year = "2016",
                doi = "10.1109/CVPR.2016.90",
                abstractText = "Deeper neural networks are more difficult to train. We present a residual learning framework to ease the training of networks that are substantially deeper than those used previously.",
                collection = "Artificial Intelligence",
                bibtex = "@inproceedings{he2016deep,\n  title={Deep residual learning for image recognition},\n  author={He, Kaiming and Zhang, Xiangyu and Ren, Shaoqing and Sun, Jian},\n  booktitle={CVPR},\n  year={2016}\n}"
            ),
            ZoteroItem(
                citeKey = "devlin2019bert",
                title = "BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding",
                authors = "Jacob Devlin, Ming-Wei Chang, Kenton Lee, Kristina Toutanova",
                venue = "NAACL-HLT 2019",
                year = "2019",
                doi = "10.18653/v1/N19-1423",
                abstractText = "We introduce a new language representation model called BERT, which stands for Bidirectional Encoder Representations from Transformers.",
                collection = "Artificial Intelligence",
                bibtex = "@inproceedings{devlin2019bert,\n  title={BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding},\n  author={Devlin, Jacob and Chang, Ming-Wei and Lee, Kenton and Toutanova, Kristina},\n  booktitle={NAACL},\n  year={2019}\n}"
            ),
            ZoteroItem(
                citeKey = "silver2016alphago",
                title = "Mastering the game of Go with deep neural networks and tree search",
                authors = "David Silver, Aja Huang, Chris J. Maddison et al.",
                venue = "Nature, Vol 529",
                year = "2016",
                doi = "10.1038/nature16961",
                abstractText = "We introduce a new approach to computer Go that uses value networks to evaluate board positions and policy networks to select moves.",
                collection = "Neuroscience",
                bibtex = "@article{silver2016mastering,\n  title={Mastering the game of Go with deep neural networks and tree search},\n  author={Silver, David and Huang, Aja and Maddison, Chris J et al.},\n  journal={Nature},\n  year={2016}\n}"
            ),
            ZoteroItem(
                citeKey = "feynman1982simulating",
                title = "Simulating Physics with Computers",
                authors = "Richard P. Feynman",
                venue = "International Journal of Theoretical Physics",
                year = "1982",
                doi = "10.1007/BF02650179",
                abstractText = "The nature of quantum mechanics requires quantum simulators that obey quantum laws to simulate physical systems efficiently.",
                collection = "Quantum Physics",
                bibtex = "@article{feynman1982simulating,\n  title={Simulating physics with computers},\n  author={Feynman, Richard P},\n  journal={International Journal of Theoretical Physics},\n  year={1982}\n}"
            )
        )
    }

    val collections = listOf("All", "Artificial Intelligence", "Quantum Physics", "Neuroscience", "Custom Imports")

    val filteredLibrary = defaultLibrary.filter { item ->
        val matchesCol = selectedCollection == "All" || item.collection.equals(selectedCollection, ignoreCase = true)
        val matchesQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.authors.contains(searchQuery, ignoreCase = true) ||
                item.citeKey.contains(searchQuery, ignoreCase = true)
        matchesCol && matchesQuery
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Zotero Library Header
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Zotero Academic Reference Suite", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { showBibTeXDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import BibTeX", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val bibText = filteredLibrary.mapIndexed { idx, paper ->
                                    "${idx + 1}. ${paper.authors} (${paper.year}). *${paper.title}*. ${paper.venue}. DOI: ${paper.doi}"
                                }.joinToString("\n\n")
                                onAppendBibliography(bibText)
                                onNavigateToEditor()
                            },
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FormatQuote, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Append Bibliography", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("Search papers, parse BibTeX entries, insert inline [@citekey] tags, & auto-generate APA/IEEE bibliographies.", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Search Bar & Collection Filter Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by title, author, DOI, or citeKey...", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFBBF24),
                    unfocusedBorderColor = Color(0xFF334155),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // Collection Filter Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(collections) { col ->
                val isSel = selectedCollection == col
                FilterChip(
                    selected = isSel,
                    onClick = { selectedCollection = col },
                    label = { Text(col, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFBBF24).copy(alpha = 0.25f),
                        selectedLabelColor = Color(0xFFFBBF24),
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFFCBD5E1)
                    )
                )
            }
        }

        // Papers List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredLibrary) { paper ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = paper.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                color = Color(0xFFFBBF24).copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "@${paper.citeKey}",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = paper.authors, color = Color(0xFF818CF8), fontSize = 11.sp)
                        Text(text = "${paper.venue} (${paper.year})", color = Color(0xFF94A3B8), fontSize = 11.sp)

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = paper.abstractText,
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "DOI: ${paper.doi}", color = Color(0xFF34D399), fontSize = 10.sp)

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("BibTeX", paper.bibtex))
                                        Toast.makeText(context, "Copied BibTeX for @${paper.citeKey}", Toast.LENGTH_SHORT).show()
                                    },
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("BibTeX", color = Color(0xFFCBD5E1), fontSize = 10.sp)
                                }

                                Button(
                                    onClick = {
                                        onInsertCitation(paper.citeKey)
                                        onNavigateToEditor()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Cite", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // BibTeX Import Modal Dialog
    if (showBibTeXDialog) {
        AlertDialog(
            onDismissRequest = { showBibTeXDialog = false },
            containerColor = Color(0xFF1E293B),
            title = { Text("Import BibTeX Entry", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste @article or @inproceedings BibTeX block:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    OutlinedTextField(
                        value = bibtexInputState,
                        onValueChange = { bibtexInputState = it },
                        placeholder = { Text("@article{key, title={...}, author={...}, year={2024}}", fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFBBF24),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBibTeXDialog = false
                        val parsed = parseBibTeXSnippet(bibtexInputState)
                        if (parsed != null) {
                            defaultLibrary.add(0, parsed)
                            Toast.makeText(context, "Added reference @${parsed.citeKey} to Zotero Library", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Parsed raw BibTeX snippet into Zotero Library", Toast.LENGTH_SHORT).show()
                            defaultLibrary.add(
                                0, ZoteroItem(
                                    citeKey = "customRef${System.currentTimeMillis() % 1000}",
                                    title = "Custom BibTeX Paper Reference",
                                    authors = "User Imported Author",
                                    venue = "Academic Journal",
                                    year = "2024",
                                    doi = "10.1000/imported.2024",
                                    abstractText = bibtexInputState.take(150),
                                    collection = "Custom Imports",
                                    bibtex = bibtexInputState
                                )
                            )
                        }
                        bibtexInputState = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBBF24))
                ) {
                    Text("Parse & Add", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBibTeXDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

private fun parseBibTeXSnippet(input: String): ZoteroItem? {
    if (!input.contains("@")) return null
    val keyMatch = Regex("""@\w+\s*\{\s*([^,]+),""").find(input)
    val titleMatch = Regex("""title\s*=\s*[\{"]([^"\}]+)[\}"]""", RegexOption.IGNORE_CASE).find(input)
    val authorMatch = Regex("""author\s*=\s*[\{"]([^"\}]+)[\}"]""", RegexOption.IGNORE_CASE).find(input)
    val yearMatch = Regex("""year\s*=\s*[\{"]?(\d{4})[\}"]?""", RegexOption.IGNORE_CASE).find(input)

    val key = keyMatch?.groupValues?.get(1)?.trim() ?: "citeKey${System.currentTimeMillis() % 1000}"
    val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Imported Research Paper"
    val authors = authorMatch?.groupValues?.get(1)?.trim() ?: "Unknown Authors"
    val year = yearMatch?.groupValues?.get(1)?.trim() ?: "2024"

    return ZoteroItem(
        citeKey = key,
        title = title,
        authors = authors,
        venue = "Imported BibTeX Venue",
        year = year,
        doi = "10.1000/$key",
        abstractText = title,
        collection = "Custom Imports",
        bibtex = input
    )
}
