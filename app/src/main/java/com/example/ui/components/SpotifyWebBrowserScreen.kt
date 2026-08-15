package com.example.ui.components

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.AppViewModel
import com.example.util.NetworkChecker
import com.example.util.ShortcutUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class for Spotify Offline Song item
data class SpotifyOfflineSong(
    val id: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String,
    val coverArtLocalPath: String,
    val audioFilePath: String,
    val musicStorageUriStr: String,
    val durationSec: Int,
    val fileSizeMs: Long,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyWebBrowserScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("spotify_app_prefs", Context.MODE_PRIVATE) }

    // State Variables
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isAdBlockEnabled by remember { mutableStateOf(prefs.getBoolean("spotify_adblock_enabled", true)) }

    // Current playing track info extracted from Spotify DOM
    var currentTrackTitle by remember { mutableStateOf("No track playing") }
    var currentArtistName by remember { mutableStateOf("Spotify Web") }
    var currentCoverArtUrl by remember { mutableStateOf("") }
    var isWebTrackPlaying by remember { mutableStateOf(false) }
    var trackDurationSec by remember { mutableIntStateOf(0) }
    var trackCurrentTimeSec by remember { mutableIntStateOf(0) }

    // Downloading & Recording Lock State
    var isDownloadingAndRecording by remember { mutableStateOf(false) }
    var recordingProgressSec by remember { mutableIntStateOf(0) }
    var recordingTargetDurationSec by remember { mutableIntStateOf(0) }
    var currentRecordingSongTitle by remember { mutableStateOf("") }
    var currentRecordingArtist by remember { mutableStateOf("") }
    var mediaRecorder: MediaRecorder? by remember { mutableStateOf(null) }
    var currentRecordingFile: File? by remember { mutableStateOf(null) }

    // Offline Library State
    var offlineSongsList by remember { mutableStateOf<List<SpotifyOfflineSong>>(emptyList()) }
    var showOfflineLibraryModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isOfflineModeForced by remember { mutableStateOf(false) }

    // Offline Media Player State
    var playingOfflineSong by remember { mutableStateOf<SpotifyOfflineSong?>(null) }
    var offlineMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isOfflinePlaying by remember { mutableStateOf(false) }
    var offlinePlaybackPosMs by remember { mutableIntStateOf(0) }
    var offlineDurationMs by remember { mutableIntStateOf(0) }

    // Check Network status
    val isNetworkAvailable = remember(context) { NetworkChecker.isInternetAvailable(context) }
    val showOfflineViewOnly = !isNetworkAvailable || isOfflineModeForced

    // Load Offline Songs from SharedPreferences
    fun loadOfflineSongs() {
        val jsonStr = prefs.getString("offline_spotify_songs", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<SpotifyOfflineSong>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SpotifyOfflineSong(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        coverArtUrl = obj.optString("coverArtUrl"),
                        coverArtLocalPath = obj.optString("coverArtLocalPath"),
                        audioFilePath = obj.optString("audioFilePath"),
                        musicStorageUriStr = obj.optString("musicStorageUriStr"),
                        durationSec = obj.optInt("durationSec"),
                        fileSizeMs = obj.optLong("fileSizeMs"),
                        timestamp = obj.optLong("timestamp")
                    )
                )
            }
            offlineSongsList = list
        } catch (e: Exception) {
            Log.e("SpotifyWeb", "Error parsing offline songs", e)
        }
    }

    LaunchedEffect(Unit) {
        loadOfflineSongs()
    }

    // Save offline song to SharedPreferences & phone Music directory
    fun saveOfflineSongToStorage(
        title: String,
        artist: String,
        coverArtUrl: String,
        tempAudioFile: File,
        durationSec: Int
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val sanitizedTitle = title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val sanitizedArtist = artist.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val timestamp = System.currentTimeMillis()
                val id = "spot_${sanitizedTitle}_${sanitizedArtist}_$timestamp"

                // 1. Save Cover Art image locally
                val coverArtDir = File(context.filesDir, "spotify_covers")
                if (!coverArtDir.exists()) coverArtDir.mkdirs()
                val coverFile = File(coverArtDir, "$id.jpg")

                if (coverArtUrl.isNotBlank()) {
                    try {
                        val url = URL(coverArtUrl)
                        val conn = url.openConnection() as HttpURLConnection
                        conn.doInput = true
                        conn.connect()
                        val inputStream: InputStream = conn.inputStream
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        val outStream = FileOutputStream(coverFile)
                        bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                        outStream.flush()
                        outStream.close()
                    } catch (e: Exception) {
                        Log.e("SpotifyWeb", "Error saving cover art image", e)
                    }
                }

                // 2. Save Audio File in App Storage
                val appAudioDir = File(context.filesDir, "spotify_offline_audio")
                if (!appAudioDir.exists()) appAudioDir.mkdirs()
                val destAudioFile = File(appAudioDir, "$id.m4a")
                tempAudioFile.copyTo(destAudioFile, overwrite = true)

                // 3. Save to Phone Music Storage (MediaStore)
                var musicUriStr = ""
                try {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, "$title - $artist.m4a")
                        put(MediaStore.Audio.Media.TITLE, title)
                        put(MediaStore.Audio.Media.ARTIST, artist)
                        put(MediaStore.Audio.Media.ALBUM, "Spotify LifeOS Downloads")
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/m4a")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Spotify_LifeOS")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                    }

                    val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        musicUriStr = uri.toString()
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            destAudioFile.inputStream().use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SpotifyWeb", "Error writing to phone Music storage", e)
                }

                // 4. Update JSON list
                val newSong = SpotifyOfflineSong(
                    id = id,
                    title = title,
                    artist = artist,
                    coverArtUrl = coverArtUrl,
                    coverArtLocalPath = coverFile.absolutePath,
                    audioFilePath = destAudioFile.absolutePath,
                    musicStorageUriStr = musicUriStr,
                    durationSec = durationSec,
                    fileSizeMs = destAudioFile.length(),
                    timestamp = timestamp
                )

                val currentList = offlineSongsList.toMutableList()
                currentList.add(0, newSong)

                val jsonArr = JSONArray()
                for (s in currentList) {
                    val obj = JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("coverArtUrl", s.coverArtUrl)
                        put("coverArtLocalPath", s.coverArtLocalPath)
                        put("audioFilePath", s.audioFilePath)
                        put("musicStorageUriStr", s.musicStorageUriStr)
                        put("durationSec", s.durationSec)
                        put("fileSizeMs", s.fileSizeMs)
                        put("timestamp", s.timestamp)
                    }
                    jsonArr.put(obj)
                }

                prefs.edit().putString("offline_spotify_songs", jsonArr.toString()).apply()

                withContext(Dispatchers.Main) {
                    offlineSongsList = currentList
                    Toast.makeText(
                        context,
                        "Saved '$title' to Life OS & Phone Music Storage! 🎵",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("SpotifyWeb", "Error saving offline song", e)
            }
        }
    }

    // Helper: Check if a track is already downloaded
    fun isTrackDownloaded(title: String, artist: String): SpotifyOfflineSong? {
        if (title.isBlank() || title == "No track playing") return null
        val cleanTitle = title.trim().lowercase()
        val cleanArtist = artist.trim().lowercase()
        return offlineSongsList.firstOrNull {
            it.title.trim().lowercase() == cleanTitle ||
            (it.title.trim().lowercase().contains(cleanTitle) && it.artist.trim().lowercase().contains(cleanArtist))
        }
    }

    // Stop MediaRecorder
    fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e("SpotifyWeb", "Error stopping MediaRecorder", e)
        }
        mediaRecorder = null
    }

    // Start Download Flow: Plays track completely once, locking controls
    fun startSongDownloadAndRecording(title: String, artist: String, coverUrl: String, duration: Int) {
        if (isDownloadingAndRecording) {
            Toast.makeText(context, "Download already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        isDownloadingAndRecording = true
        currentRecordingSongTitle = title
        currentRecordingArtist = artist
        recordingTargetDurationSec = if (duration > 0) duration else 180
        recordingProgressSec = 0

        Toast.makeText(
            context,
            "Starting Download & Audio Recording! Song will play once to capture. Controls locked.",
            Toast.LENGTH_LONG
        ).show()

        // Inject JS to restart track from 0 and lock player controls
        webViewInstance?.evaluateJavascript(
            """
            (function() {
                var audio = document.querySelector('audio');
                if (audio) {
                    audio.currentTime = 0;
                    audio.play();
                }
                var playBtn = document.querySelector('button[data-testid="control-button-playpause"]');
                var skipBtn = document.querySelector('button[data-testid="control-button-skip-forward"]');
                if (playBtn) playBtn.style.pointerEvents = 'none';
                if (skipBtn) skipBtn.style.pointerEvents = 'none';
            })();
            """.trimIndent(),
            null
        )

        // Initialize MediaRecorder for recording system/mic audio
        try {
            val audioDir = File(context.cacheDir, "temp_downloads")
            if (!audioDir.exists()) audioDir.mkdirs()
            val tempFile = File(audioDir, "temp_record_${System.currentTimeMillis()}.m4a")
            currentRecordingFile = tempFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(192000)
            recorder.setOutputFile(tempFile.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
        } catch (e: Exception) {
            Log.e("SpotifyWeb", "Failed to initialize MediaRecorder", e)
        }

        // Timer monitor loop for recording progress
        scope.launch {
            var elapsed = 0
            val maxSec = recordingTargetDurationSec
            while (isDownloadingAndRecording && elapsed < maxSec + 5) {
                delay(1000)
                elapsed++
                recordingProgressSec = elapsed

                // Check web audio current time or completion
                if (trackCurrentTimeSec >= recordingTargetDurationSec - 1 || (!isWebTrackPlaying && elapsed > 10)) {
                    break
                }
            }

            // Finish recording
            stopRecording()
            isDownloadingAndRecording = false

            // Restore controls in JS
            withContext(Dispatchers.Main) {
                webViewInstance?.evaluateJavascript(
                    """
                    (function() {
                        var playBtn = document.querySelector('button[data-testid="control-button-playpause"]');
                        var skipBtn = document.querySelector('button[data-testid="control-button-skip-forward"]');
                        if (playBtn) playBtn.style.pointerEvents = 'auto';
                        if (skipBtn) skipBtn.style.pointerEvents = 'auto';
                    })();
                    """.trimIndent(),
                    null
                )
            }

            // Save recorded file if exists
            val recordedFile = currentRecordingFile
            if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                saveOfflineSongToStorage(
                    title = currentRecordingSongTitle,
                    artist = currentRecordingArtist,
                    coverArtUrl = coverUrl,
                    tempAudioFile = recordedFile,
                    durationSec = recordingProgressSec
                )
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed or audio empty.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Offline Media Player Handler
    fun playOfflineTrack(song: SpotifyOfflineSong) {
        try {
            offlineMediaPlayer?.stop()
            offlineMediaPlayer?.release()

            val mp = MediaPlayer().apply {
                val file = File(song.audioFilePath)
                if (file.exists()) {
                    setDataSource(file.absolutePath)
                } else if (song.musicStorageUriStr.isNotBlank()) {
                    setDataSource(context, Uri.parse(song.musicStorageUriStr))
                }
                prepare()
                start()
            }

            offlineMediaPlayer = mp
            playingOfflineSong = song
            isOfflinePlaying = true
            offlineDurationMs = mp.duration

            mp.setOnCompletionListener {
                isOfflinePlaying = false
                offlinePlaybackPosMs = 0
            }
        } catch (e: Exception) {
            Log.e("SpotifyWeb", "Error playing offline track", e)
            Toast.makeText(context, "Error playing track: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle Play/Pause Offline
    fun toggleOfflinePlayback() {
        val mp = offlineMediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            isOfflinePlaying = false
        } else {
            mp.start()
            isOfflinePlaying = true
        }
    }

    // Update position ticker for offline player
    LaunchedEffect(isOfflinePlaying) {
        while (isOfflinePlaying) {
            offlineMediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    offlinePlaybackPosMs = mp.currentPosition
                }
            }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            try {
                offlineMediaPlayer?.stop()
                offlineMediaPlayer?.release()
            } catch (_: Exception) {}
            // Preserve Spotify background audio playback across all screens and desktop multi-window mode
        }
    }

    BackHandler {
        if (showOfflineLibraryModal) {
            showOfflineLibraryModal = false
        } else if (webViewInstance?.canGoBack() == true && !showOfflineViewOnly) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = Color(0xFF121212),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { onBack() },
                            modifier = Modifier.testTag("spotify_back_btn")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        // Spotify Logo Icon + Title
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1DB954)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = "Spotify",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    "Spotify Web",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    if (showOfflineViewOnly) "Offline Mode" else if (isAdBlockEnabled) "AdBlock Active 🛡️" else "Online Mode",
                                    color = if (showOfflineViewOnly) Color(0xFFFF9800) else Color(0xFF1DB954),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Action Controls: Offline Library, AdBlock Toggle, Reload/Home
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Offline Songs Library Button with badge
                        BadgedBox(
                            badge = {
                                if (offlineSongsList.isNotEmpty()) {
                                    Badge(
                                        containerColor = Color(0xFF1DB954),
                                        contentColor = Color.Black
                                    ) {
                                        Text("${offlineSongsList.size}")
                                    }
                                }
                            }
                        ) {
                            IconButton(
                                onClick = { showOfflineLibraryModal = true },
                                modifier = Modifier.testTag("spotify_offline_library_btn")
                            ) {
                                Icon(
                                    Icons.Default.FolderSpecial,
                                    contentDescription = "Offline Library",
                                    tint = Color(0xFF1DB954)
                                )
                            }
                        }

                        // AdBlocker Toggle Button
                        IconButton(
                            onClick = {
                                isAdBlockEnabled = !isAdBlockEnabled
                                prefs.edit().putBoolean("spotify_adblock_enabled", isAdBlockEnabled).apply()
                                webViewInstance?.reload()
                                Toast.makeText(
                                    context,
                                    if (isAdBlockEnabled) "AdBlocker Enabled 🛡️" else "AdBlocker Disabled",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.testTag("spotify_adblock_toggle_btn")
                        ) {
                            Icon(
                                if (isAdBlockEnabled) Icons.Default.Shield else Icons.Default.Security,
                                contentDescription = "Toggle AdBlock",
                                tint = if (isAdBlockEnabled) Color(0xFF1DB954) else Color.Gray
                            )
                        }

                        // Create Home Screen Shortcut Button
                        IconButton(
                            onClick = {
                                val ok = ShortcutUtils.createSpotifyShortcut(context, forcePinPrompt = true)
                                Toast.makeText(
                                    context,
                                    if (ok) "Spotify shortcut added to Home Screen! 📲" else "Could not add shortcut",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.testTag("spotify_add_shortcut_btn")
                        ) {
                            Icon(
                                Icons.Default.BookmarkAdd,
                                contentDescription = "Add Shortcut",
                                tint = Color.LightGray
                            )
                        }

                        // Open in Spotify App or External Browser (Lossless hardware DRM)
                        IconButton(
                            onClick = {
                                val currentUrl = webViewInstance?.url ?: "https://open.spotify.com"
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Opening in browser...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("spotify_open_external_btn")
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Open in Spotify App / Browser",
                                tint = Color(0xFF1DB954)
                            )
                        }

                        // Home / Refresh Button
                        IconButton(
                            onClick = {
                                if (isOfflineModeForced) {
                                    isOfflineModeForced = false
                                }
                                webViewInstance?.loadUrl("https://open.spotify.com")
                            },
                            modifier = Modifier.testTag("spotify_home_btn")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Active Track Player / Download Action Bar at bottom
            if (!showOfflineViewOnly && currentTrackTitle != "No track playing") {
                val downloadedItem = isTrackDownloaded(currentTrackTitle, currentArtistName)

                Surface(
                    color = Color(0xFF181818),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Downloading Lock Banner
                        if (isDownloadingAndRecording) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE91E63))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "Recording Track Audio... Controls Locked (${recordingProgressSec}s / ${recordingTargetDurationSec}s)",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Track Thumbnail & Title
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF282828)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF1DB954)
                                    )
                                }

                                Column {
                                    Text(
                                        currentTrackTitle,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        currentArtistName,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Download OR Play Offline Button
                            if (downloadedItem != null) {
                                // Already Downloaded -> Show PLAY button
                                Button(
                                    onClick = {
                                        playOfflineTrack(downloadedItem)
                                        showOfflineLibraryModal = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("spotify_play_offline_btn")
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play Offline",
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Play Offline", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                // NOT Downloaded -> Show DOWNLOAD button
                                Button(
                                    onClick = {
                                        startSongDownloadAndRecording(
                                            title = currentTrackTitle,
                                            artist = currentArtistName,
                                            coverUrl = currentCoverArtUrl,
                                            duration = trackDurationSec
                                        )
                                    },
                                    enabled = !isDownloadingAndRecording,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("spotify_download_song_btn")
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (isDownloadingAndRecording) "Recording..." else "Download",
                                        color = Color.Black,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF121212))
        ) {
            if (showOfflineViewOnly) {
                // OFFLINE MODE: Show full screen Offline Songs List & Offline Music Player
                OfflineSpotifyView(
                    songs = offlineSongsList,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    playingSong = playingOfflineSong,
                    isPlaying = isOfflinePlaying,
                    currentPosMs = offlinePlaybackPosMs,
                    durationMs = offlineDurationMs,
                    onPlayTrack = { playOfflineTrack(it) },
                    onTogglePlay = { toggleOfflinePlayback() },
                    onSeekTo = { offlineMediaPlayer?.seekTo(it) },
                    onDeleteTrack = { song ->
                        try {
                            File(song.audioFilePath).delete()
                            File(song.coverArtLocalPath).delete()
                        } catch (_: Exception) {}
                        offlineSongsList = offlineSongsList.filterNot { it.id == song.id }
                        val jsonArr = JSONArray()
                        for (s in offlineSongsList) {
                            val obj = JSONObject().apply {
                                put("id", s.id)
                                put("title", s.title)
                                put("artist", s.artist)
                                put("coverArtUrl", s.coverArtUrl)
                                put("coverArtLocalPath", s.coverArtLocalPath)
                                put("audioFilePath", s.audioFilePath)
                                put("musicStorageUriStr", s.musicStorageUriStr)
                                put("durationSec", s.durationSec)
                                put("fileSizeMs", s.fileSizeMs)
                                put("timestamp", s.timestamp)
                            }
                            jsonArr.put(obj)
                        }
                        prefs.edit().putString("offline_spotify_songs", jsonArr.toString()).apply()
                    },
                    onSwitchOnline = {
                        isOfflineModeForced = false
                        webViewInstance?.loadUrl("https://open.spotify.com")
                    }
                )
            } else {
                // ONLINE MODE: Spotify WebView + AdBlocker + Track Metadata Extraction JS
                AndroidView<WebView>(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                allowFileAccess = true
                                allowContentAccess = true
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                                setRenderPriority(WebSettings.RenderPriority.HIGH)
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                            // JS Bridge for track metadata callback
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun updateTrackInfo(title: String, artist: String, coverUrl: String, playing: Boolean, currentTime: Int, duration: Int) {
                                        scope.launch(Dispatchers.Main) {
                                            if (title.isNotBlank()) currentTrackTitle = title
                                            if (artist.isNotBlank()) currentArtistName = artist
                                            if (coverUrl.isNotBlank()) currentCoverArtUrl = coverUrl
                                            isWebTrackPlaying = playing
                                            trackCurrentTimeSec = currentTime
                                            if (duration > 0) trackDurationSec = duration
                                        }
                                    }
                                },
                                "SpotifyBridge"
                            )

                            webViewClient = object : WebViewClient() {
                                private fun injectWindowsSpoofAndAntiPremium(view: WebView?) {
                                    val script = """
                                    (function() {
                                        // 1. Windows Browser Device & Modern Chrome 134 Engine Spoofing
                                        try {
                                            var modernUa = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36';
                                            var modernAppVersion = '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36';

                                            Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
                                            Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true });
                                            Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
                                            Object.defineProperty(navigator, 'userAgent', { get: function() { return modernUa; }, configurable: true });
                                            Object.defineProperty(navigator, 'appVersion', { get: function() { return modernAppVersion; }, configurable: true });

                                            var brandList = [
                                                { brand: 'Chromium', version: '134' },
                                                { brand: 'Google Chrome', version: '134' },
                                                { brand: 'Not:A-Brand', version: '24' }
                                            ];

                                            var fullVersionList = [
                                                { brand: 'Chromium', version: '134.0.6998.35' },
                                                { brand: 'Google Chrome', version: '134.0.6998.35' },
                                                { brand: 'Not:A-Brand', version: '24.0.0.0' }
                                            ];

                                            Object.defineProperty(navigator, 'userAgentData', {
                                                get: function() {
                                                    return {
                                                        brands: brandList,
                                                        mobile: false,
                                                        platform: 'Windows',
                                                        getHighEntropyValues: function(hints) {
                                                            return Promise.resolve({
                                                                architecture: 'x86',
                                                                bitness: '64',
                                                                brands: brandList,
                                                                fullVersionList: fullVersionList,
                                                                mobile: false,
                                                                model: '',
                                                                platform: 'Windows',
                                                                platformVersion: '15.0.0',
                                                                uaFullVersion: '134.0.6998.35'
                                                            });
                                                        }
                                                    };
                                                },
                                                configurable: true
                                            });

                                            // Polyfill EME (Encrypted Media Extensions) to resolve Spotify DRM & "Unsupported Browser" checks
                                            if (!navigator.requestMediaKeySystemAccess || !window.__spotifyEmeShimmed) {
                                                window.__spotifyEmeShimmed = true;
                                                var origReq = navigator.requestMediaKeySystemAccess;
                                                navigator.requestMediaKeySystemAccess = function(keySystem, supportedConfigurations) {
                                                    if (keySystem === 'com.widevine.alpha' || keySystem === 'org.w3.clearkey' || (keySystem && keySystem.indexOf('widevine') !== -1)) {
                                                        return Promise.resolve({
                                                            keySystem: keySystem,
                                                            createMediaKeys: function() {
                                                                return Promise.resolve({
                                                                    createSession: function() {
                                                                        return {
                                                                            generateRequest: function() { return Promise.resolve(); },
                                                                            load: function() { return Promise.resolve(true); },
                                                                            update: function() { return Promise.resolve(); },
                                                                            close: function() { return Promise.resolve(); },
                                                                            remove: function() { return Promise.resolve(); },
                                                                            closed: new Promise(function() {}),
                                                                            keyStatuses: new Map(),
                                                                            addEventListener: function() {},
                                                                            removeEventListener: function() {},
                                                                            dispatchEvent: function() { return true; }
                                                                        };
                                                                    },
                                                                    setServerCertificate: function() { return Promise.resolve(true); }
                                                                });
                                                            },
                                                            getConfiguration: function() {
                                                                return (supportedConfigurations && supportedConfigurations[0]) || {
                                                                    initDataTypes: ['cenc', 'keyids', 'webm'],
                                                                    audioCapabilities: [
                                                                        { contentType: 'audio/mp4; codecs="mp4a.40.2"' },
                                                                        { contentType: 'audio/webm; codecs="opus"' }
                                                                    ]
                                                                };
                                                            }
                                                        });
                                                    }
                                                    if (origReq) return origReq.apply(navigator, arguments);
                                                    return Promise.reject(new Error('Unsupported keySystem'));
                                                };
                                            }

                                            // Media capabilities & codecs polyfills for audio playback
                                            if (navigator.mediaCapabilities) {
                                                navigator.mediaCapabilities.decodingInfo = function(config) {
                                                    return Promise.resolve({
                                                        supported: true,
                                                        smooth: true,
                                                        powerEfficient: true,
                                                        keySystemAccess: {
                                                            keySystem: 'com.widevine.alpha',
                                                            createMediaKeys: function() { return Promise.resolve({}); }
                                                        }
                                                    });
                                                };
                                            }

                                            if (window.MediaSource && MediaSource.isTypeSupported) {
                                                var origIsType = MediaSource.isTypeSupported;
                                                MediaSource.isTypeSupported = function(t) {
                                                    if (t && (t.includes('audio') || t.includes('webm') || t.includes('mp4') || t.includes('aac') || t.includes('opus') || t.includes('ogg') || t.includes('mpeg'))) {
                                                        return true;
                                                    }
                                                    return origIsType ? origIsType.call(MediaSource, t) : true;
                                                };
                                            }

                                            if (window.HTMLMediaElement && HTMLMediaElement.prototype.canPlayType) {
                                                var origCanPlay = HTMLMediaElement.prototype.canPlayType;
                                                HTMLMediaElement.prototype.canPlayType = function(t) {
                                                    if (t && (t.includes('audio') || t.includes('mp4') || t.includes('webm') || t.includes('ogg') || t.includes('mpeg') || t.includes('aac') || t.includes('opus'))) {
                                                        return 'probably';
                                                    }
                                                    return origCanPlay ? origCanPlay.call(this, t) : 'maybe';
                                                };
                                            }
                                        } catch(e) {}

                                        // 2. High-Performance CSS injection for blocking Ads, App Download Modals & Unsupported Browser Banners
                                        try {
                                            if (!document.getElementById('anti-premium-style')) {
                                                var style = document.createElement('style');
                                                style.id = 'anti-premium-style';
                                                style.innerHTML = `
                                                    /* App Download & Store links removal */
                                                    a[href*="/download"],
                                                    a[href*="spotify.com/download"],
                                                    a[href*="open.spotify.com/download"],
                                                    a[href*="play.google.com/store/apps/details?id=com.spotify"],
                                                    a[href*="apps.apple.com"],
                                                    a[href*="itunes.apple.com"],
                                                    a[href*="spotify.link"],
                                                    a[href*="download.spotify.com"],
                                                    [data-testid="install-app-button"],
                                                    [data-testid="download-app-button"],
                                                    [data-testid="top-bar-install-button"],
                                                    [data-testid="top-bar-download-button"],
                                                    [data-testid="navigation-item-download"],
                                                    [data-testid="navigation-item-install"],
                                                    [data-testid="navigation-item-download-app"],
                                                    [data-testid="navigation-item-install-app"],
                                                    [data-testid="smart-banner"],
                                                    [data-testid="app-banner"],
                                                    [data-testid="download-banner"],
                                                    [data-testid="mobile-app-banner"],
                                                    [data-testid="open-in-app"],
                                                    [data-testid="open-app-banner"],
                                                    [data-testid="app-upsell-banner"],
                                                    [data-testid="native-app-prompt"],
                                                    [data-testid="download-desktop-app-button"],
                                                    [data-testid="install-desktop-app-button"],
                                                    [data-testid="get-app-button"],
                                                    [data-testid="open-app-button"],
                                                    [data-testid="install-app-modal"],
                                                    [data-testid="download-app-modal"],
                                                    [data-testid="get-app-modal"],
                                                    [data-testid="app-modal"],
                                                    [data-testid="modal-install-app"],
                                                    [data-testid="modal-download-app"],
                                                    [data-testid="dialog-install-app"],
                                                    [data-testid="dialog-download-app"],
                                                    [data-testid="mobile-web-modal"],
                                                    [data-testid="mobile-banner"],
                                                    .main-topBar-downloadApp,
                                                    .main-topBar-InstallApp,
                                                    .main-topBar-installApp,
                                                    .smart-banner,
                                                    .app-banner,
                                                    .download-banner,
                                                    [aria-label*="Install App" i],
                                                    [aria-label*="Download App" i],
                                                    [aria-label*="Install Spotify" i],
                                                    [aria-label*="Download Spotify" i],
                                                    [aria-label*="Get the app" i],
                                                    [aria-label*="Get app" i],
                                                    [aria-label*="Open App" i],
                                                    [aria-label*="Open in app" i],
                                                    div[class*="SmartBanner"],
                                                    div[class*="smartBanner"],
                                                    div[class*="DownloadBanner"],
                                                    div[class*="downloadBanner"],
                                                    div[class*="AppBanner"],
                                                    div[class*="appBanner"],
                                                    div[class*="InstallBanner"],
                                                    div[class*="installBanner"],
                                                    div[class*="GetApp"],
                                                    div[class*="getApp"],
                                                    div[class*="OpenInApp"],
                                                    div[class*="openInApp"],
                                                    div[class*="InstallApp"],
                                                    div[class*="installApp"],
                                                    div[class*="DownloadApp"],
                                                    div[class*="downloadApp"],

                                                    /* Premium Upgrades & Ads */
                                                    a[href*="/premium"],
                                                    a[href*="/upgrade"],
                                                    [data-testid="premium-upgrade-button"],
                                                    [data-testid="upgrade-button"],
                                                    [data-testid="top-bar-upgrade-button"],
                                                    .main-topBar-upgradeButton,
                                                    .main-actionButtons-upgrade,
                                                    [data-testid="billboard-banner"],
                                                    [data-testid="ad-indicator"],
                                                    .ad-unit,
                                                    .top-bar-ad-banner,
                                                    .LeaderboardAd,
                                                    .spotlight-ad,
                                                    iframe[src*="doubleclick"],
                                                    iframe[src*="adservice"],
                                                    div[class*="PremiumBanner"],
                                                    div[class*="premiumBanner"],
                                                    div[class*="UpgradeButton"],
                                                    section[data-testid="premium-upsell"],
                                                    [data-testid="navigation-item-premium"],
                                                    [data-testid="user-widget-link-upgrade"],
                                                    div[data-testid="now-playing-bar-ad-banner"],

                                                    /* Unsupported Browser & Protected Content Warning Removal */
                                                    [data-testid="unsupported-browser-banner"],
                                                    [data-testid="unsupported-browser-page"],
                                                    [data-testid="browser-not-supported"],
                                                    .browser-not-supported,
                                                    #unsupported-browser,
                                                    div[class*="UnsupportedBrowser"],
                                                    div[class*="unsupportedBrowser"],
                                                    div[class*="Unsupported"],
                                                    div[class*="ProtectedContent"],
                                                    div[class*="protectedContent"],
                                                    div[class*="EmeError"],
                                                    div[class*="BrowserWarning"] {
                                                        display: none !important;
                                                        visibility: hidden !important;
                                                        height: 0 !important;
                                                        width: 0 !important;
                                                        opacity: 0 !important;
                                                        pointer-events: none !important;
                                                    }
                                                `;
                                                (document.head || document.documentElement).appendChild(style);
                                            }
                                        } catch(e) {}

                                        // 3. Single-instance Poller for track info and prompt dismissal
                                        function pollTrackInfo() {
                                            try {
                                                var titleEl = document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-title"] a') ||
                                                              document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-title"]') ||
                                                              document.querySelector('[data-testid="now-playing-widget"] span') ||
                                                              document.querySelector('.now-playing-bar span');
                                                var artistEl = document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-artist"] a') ||
                                                               document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-artist"]') ||
                                                               document.querySelector('[data-testid="context-item-info-subtitles"] a');
                                                var imgEl = document.querySelector('[data-testid="now-playing-widget"] img') ||
                                                            document.querySelector('.cover-art img');
                                                var audioEl = document.querySelector('audio');

                                                var title = titleEl ? titleEl.innerText : (document.title ? document.title.split('•')[0].trim() : 'No track playing');
                                                var artist = artistEl ? artistEl.innerText : (document.title && document.title.indexOf('•') !== -1 ? document.title.split('•')[1].replace('| Spotify','').trim() : 'Spotify Web');
                                                var coverUrl = imgEl ? imgEl.src : '';
                                                var isPlaying = audioEl ? !audioEl.paused : false;
                                                var currentTime = audioEl ? Math.floor(audioEl.currentTime) : 0;
                                                var duration = audioEl ? Math.floor(audioEl.duration) : 0;

                                                if (window.SpotifyBridge) {
                                                    window.SpotifyBridge.updateTrackInfo(title, artist, coverUrl, isPlaying, currentTime, duration);
                                                }

                                                // Auto dismiss prompt buttons if any appear
                                                var dismissButtons = document.querySelectorAll('button[aria-label="Dismiss"], button[data-testid="toast-action-button"], button[data-testid="close-button"]');
                                                dismissButtons.forEach(function(b) { if (b) b.click(); });
                                            } catch(e) {}
                                        }

                                        if (!window.__spotifyPollerRunning) {
                                            window.__spotifyPollerRunning = true;
                                            setInterval(pollTrackInfo, 1500);
                                        }
                                    })();
                                    """.trimIndent()

                                    view?.evaluateJavascript(script, null)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    injectWindowsSpoofAndAntiPremium(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    injectWindowsSpoofAndAntiPremium(view)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val urlStr = request?.url?.toString()?.lowercase() ?: ""
                                    if (urlStr.contains("play.google.com") ||
                                        urlStr.contains("apps.apple.com") ||
                                        urlStr.contains("itunes.apple.com") ||
                                        urlStr.startsWith("market://")
                                    ) {
                                        return true
                                    }
                                    if (urlStr.startsWith("spotify:")) {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                            return true
                                        } catch (_: Exception) {}
                                    }
                                    return super.shouldOverrideUrlLoading(view, request)
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: ""
                                    if (isAdBlockEnabled) {
                                        val adDomains = listOf(
                                            "doubleclick.net", "googlesyndication.com", "pagead2.googlesyndication.com",
                                            "adservice.google.com", "scorecardresearch.com"
                                        )
                                        if (adDomains.any { reqUrl.contains(it, ignoreCase = true) }) {
                                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            loadUrl("https://open.spotify.com")
                            webViewInstance = this
                        }
                    },
                    onRelease = { wv ->
                        try {
                            wv.stopLoading()
                            wv.onPause()
                            wv.pauseTimers()
                            wv.removeAllViews()
                            wv.destroy()
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF121212)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF1DB954))
                            Text(
                                "Loading Spotify Web Application...",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet for Offline Library
    if (showOfflineLibraryModal) {
        ModalBottomSheet(
            onDismissRequest = { showOfflineLibraryModal = false },
            containerColor = Color(0xFF181818),
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = Color(0xFF1DB954)
                        )
                        Text(
                            "Offline Songs Library (${offlineSongsList.size})",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = { showOfflineLibraryModal = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OfflineSpotifyView(
                    songs = offlineSongsList,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    playingSong = playingOfflineSong,
                    isPlaying = isOfflinePlaying,
                    currentPosMs = offlinePlaybackPosMs,
                    durationMs = offlineDurationMs,
                    onPlayTrack = { playOfflineTrack(it) },
                    onTogglePlay = { toggleOfflinePlayback() },
                    onSeekTo = { offlineMediaPlayer?.seekTo(it) },
                    onDeleteTrack = { song ->
                        try {
                            File(song.audioFilePath).delete()
                            File(song.coverArtLocalPath).delete()
                        } catch (_: Exception) {}
                        offlineSongsList = offlineSongsList.filterNot { it.id == song.id }
                        val jsonArr = JSONArray()
                        for (s in offlineSongsList) {
                            val obj = JSONObject().apply {
                                put("id", s.id)
                                put("title", s.title)
                                put("artist", s.artist)
                                put("coverArtUrl", s.coverArtUrl)
                                put("coverArtLocalPath", s.coverArtLocalPath)
                                put("audioFilePath", s.audioFilePath)
                                put("musicStorageUriStr", s.musicStorageUriStr)
                                put("durationSec", s.durationSec)
                                put("fileSizeMs", s.fileSizeMs)
                                put("timestamp", s.timestamp)
                            }
                            jsonArr.put(obj)
                        }
                        prefs.edit().putString("offline_spotify_songs", jsonArr.toString()).apply()
                    },
                    onSwitchOnline = {
                        showOfflineLibraryModal = false
                        isOfflineModeForced = false
                        webViewInstance?.loadUrl("https://open.spotify.com")
                    }
                )
            }
        }
    }
}

// Composable Component: Offline Songs View & Built-in Offline Music Player
@Composable
fun OfflineSpotifyView(
    songs: List<SpotifyOfflineSong>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    playingSong: SpotifyOfflineSong?,
    isPlaying: Boolean,
    currentPosMs: Int,
    durationMs: Int,
    onPlayTrack: (SpotifyOfflineSong) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onDeleteTrack: (SpotifyOfflineSong) -> Unit,
    onSwitchOnline: () -> Unit
) {
    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search offline songs...", color = Color.Gray, fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF1DB954),
                unfocusedBorderColor = Color(0xFF282828),
                focusedContainerColor = Color(0xFF282828),
                unfocusedContainerColor = Color(0xFF181818),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("spotify_search_offline_input")
        )

        if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text("No offline songs downloaded yet.", color = Color.Gray, fontSize = 14.sp)
                    Text(
                        "Click 'Download' while playing a song in Spotify Web to save it for offline playback!",
                        color = Color.DarkGray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSongs) { song ->
                    val isCurrent = playingSong?.id == song.id

                    Surface(
                        color = if (isCurrent) Color(0xFF282828) else Color(0xFF181818),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayTrack(song) }
                            .testTag("spotify_offline_song_item_${song.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Cover image or icon
                                val coverFile = remember(song.coverArtLocalPath) { File(song.coverArtLocalPath) }
                                if (coverFile.exists()) {
                                    val bitmap = remember(song.coverArtLocalPath) {
                                        try {
                                            BitmapFactory.decodeFile(coverFile.absolutePath)
                                        } catch (_: Exception) { null }
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF282828)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF1DB954))
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF282828)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF1DB954))
                                    }
                                }

                                Column {
                                    Text(
                                        song.title,
                                        color = if (isCurrent) Color(0xFF1DB954) else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${song.artist} • Saved in Music storage",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { onPlayTrack(song) }) {
                                    Icon(
                                        if (isCurrent && isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                        contentDescription = "Play",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                IconButton(onClick = { onDeleteTrack(song) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Active Offline Music Player Bar
        if (playingSong != null) {
            Surface(
                color = Color(0xFF282828),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                playingSong.title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                playingSong.artist,
                                color = Color(0xFF1DB954),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = onTogglePlay) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    if (durationMs > 0) {
                        Slider(
                            value = currentPosMs.toFloat(),
                            onValueChange = { onSeekTo(it.toInt()) },
                            valueRange = 0f..durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF1DB954),
                                activeTrackColor = Color(0xFF1DB954),
                                inactiveTrackColor = Color.Gray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatTimeMs(currentPosMs), color = Color.Gray, fontSize = 10.sp)
                            Text(formatTimeMs(durationMs), color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", min, sec)
}
