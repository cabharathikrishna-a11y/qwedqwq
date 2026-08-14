package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

object YouTubeUrlParser {
    /**
     * Finds and extracts YouTube video IDs from a block of text.
     */
    fun findYouTubeVideoIds(text: String): List<String> {
        val regex = "(?:https?:\\/\\/)?(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)\\/(?:watch\\?v=|embed\\/|shorts\\/|v\\/)?([a-zA-Z0-9_-]{11})".toRegex()
        return regex.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }
}

@Composable
fun YouTubeThumbnailView(
    videoId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Standard YouTube high-quality thumbnail URL
    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

    Card(
        modifier = modifier
            .width(160.dp)
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF13141C))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Video Thumbnail Image
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "YouTube Thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Dark semi-transparent overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            // Play icon in the center
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.Red.copy(alpha = 0.85f), shape = RoundedCornerShape(50))
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play YouTube Video",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Small Youtube badge in bottom-right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "YouTube",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun YouTubeLinkParserAndRenderer(
    text: String,
    modifier: Modifier = Modifier
) {
    val videoIds = remember(text) { YouTubeUrlParser.findYouTubeVideoIds(text) }
    if (videoIds.isEmpty()) return

    var activeVideoId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = "Detected YouTube Video${if (videoIds.size > 1) "s" else ""}:",
            color = Color(0xFF00FFCC),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // Row of detected videos
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            videoIds.forEach { videoId ->
                YouTubeThumbnailView(
                    videoId = videoId,
                    onClick = { activeVideoId = videoId }
                )
            }
        }
    }

    activeVideoId?.let { videoId ->
        YouTubePlayerDialog(
            videoId = videoId,
            onDismiss = { activeVideoId = null }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerDialog(
    videoId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var selectedSpeed by remember { mutableFloatStateOf(1.0f) }
    var adBlockActive by remember { mutableStateOf(true) }

    var showDownloadModal by remember { mutableStateOf(false) }
    var showOfflineModal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        com.example.util.UnifiedMediaNotificationManager.actionCommands.collect { cmd ->
            when (cmd) {
                is com.example.util.MediaActionCommand.PlayPause -> {
                    webViewInstance?.evaluateJavascript("var v = document.querySelector('video'); if (v) { if (v.paused) v.play(); else v.pause(); }", null)
                }
                is com.example.util.MediaActionCommand.Rewind -> {
                    webViewInstance?.evaluateJavascript("var v = document.querySelector('video'); if (v) v.currentTime = Math.max(0, v.currentTime - 10);", null)
                }
                is com.example.util.MediaActionCommand.FastForward -> {
                    webViewInstance?.evaluateJavascript("var v = document.querySelector('video'); if (v) v.currentTime = Math.min(v.duration || 999999, v.currentTime + 10);", null)
                }
                is com.example.util.MediaActionCommand.ChangeSpeed -> {
                    selectedSpeed = cmd.speed
                }
                is com.example.util.MediaActionCommand.NextOrSkipAd -> {
                    webViewInstance?.evaluateJavascript("var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern'); if (skipBtn) skipBtn.click();", null)
                }
                is com.example.util.MediaActionCommand.Stop -> {
                    webViewInstance?.evaluateJavascript("var v = document.querySelector('video'); if (v) v.pause();", null)
                }
            }
        }
    }

    LaunchedEffect(selectedSpeed, videoId) {
        com.example.util.UnifiedMediaNotificationManager.updateState(
            context = context,
            title = "YouTube Video ($videoId)",
            subtitle = "YouTube • AdBlock Active 🛡️",
            playing = true,
            speed = selectedSpeed,
            playerType = com.example.util.ActivePlayerType.YOUTUBE
        )
    }

    val speedInjectionJs = remember(selectedSpeed, adBlockActive) {
        """
        (function() {
            window.__targetSpeed = ${selectedSpeed}f;
            window.__adBlockActive = ${adBlockActive};
            window.__allowBgPlay = true;

            try {
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            } catch(e) {}

            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function(evt) {
                window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });

            if (${adBlockActive}) {
                var styleId = 'yt-dialog-adblock-styles';
                if (!document.getElementById(styleId)) {
                    var s = document.createElement('style');
                    s.id = styleId;
                    s.textContent = '.video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-skip-button-slot, .ad-container, .ad-div { display: none !important; }';
                    (document.head || document.documentElement).appendChild(s);
                }
            }

            function applySpeedAndAds() {
                var speed = window.__targetSpeed || 1.0;
                var videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    if (v) {
                        var isAd = v.classList.contains('ad-showing') || (v.closest && v.closest('.ad-interrupting'));
                        if (isAd && window.__adBlockActive) {
                            v.muted = true;
                            v.playbackRate = 16.0;
                            if (v.duration && !isNaN(v.duration)) v.currentTime = v.duration - 0.05;
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
                            if (skipBtn) skipBtn.click();
                        } else if (!isAd) {
                            if (Math.abs(v.playbackRate - speed) > 0.02) {
                                v.playbackRate = speed;
                            }
                        }
                    }
                });
            }

            applySpeedAndAds();
            if (!window.__dialogSpeedInterval) {
                window.__dialogSpeedInterval = setInterval(applySpeedAndAds, 300);
            }

            if (!window.__bgPlayMonitor) {
                window.__bgPlayMonitor = setInterval(function() {
                    var v = document.querySelector('video');
                    if (v && v.paused && !v.ended) {
                        try { v.play(); } catch(err) {}
                    }
                }, 800);
            }
        })();
        """.trimIndent()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF13141C),
            border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with Speed Badge & Close Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "YouTube Player",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = Color(0xFFFF0000).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (selectedSpeed % 1f == 0f) "${selectedSpeed.toInt()}x ⚡" else String.format("%.2fx ⚡", selectedSpeed),
                                color = Color(0xFFFF4D4D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (adBlockActive) {
                            Surface(
                                color = Color(0xFF00E676).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "AdBlock 🛡️",
                                    color = Color(0xFF00E676),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Player",
                            tint = Color.White
                        )
                    }
                }

                // WebView embedding video in 16:9
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(speedInjectionJs, null)
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                settings.apply {
                                    javaScriptEnabled = true
                                    mediaPlaybackRequiresUserGesture = false
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                                }
                                loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&fs=1")
                            }
                        },
                        update = { webView ->
                            webView.evaluateJavascript(speedInjectionJs, null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // High Speed Playback Chips Row (including 2.5x, 3x, 3.5x, 4x)
                Text(
                    text = "High-Speed Playback Rate:",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f).forEach { speed ->
                        val isSelected = Math.abs(selectedSpeed - speed) < 0.01f
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedSpeed = speed
                                webViewInstance?.evaluateJavascript(speedInjectionJs, null)
                            },
                            label = {
                                Text(
                                    text = if (speed % 1f == 0f) "${speed.toInt()}x" else String.format("%.1fx", speed),
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFF0000),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF22222E),
                                labelColor = Color.LightGray
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Download & Share Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showDownloadModal = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download ⬇️", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            com.example.util.YouTubeMediaDownloader.shareYouTubeLink(context, videoId)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share Link", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Link 🔗", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDownloadModal) {
        val scope = rememberCoroutineScope()
        var selectedFormat by remember { mutableStateOf("MP3 (320kbps)") }
        var isDownloading by remember { mutableStateOf(false) }
        var progressPct by remember { mutableIntStateOf(0) }

        val qualityOptions = listOf(
            "MP3 (320kbps)" to "🎵 MP3 Audio (320 kbps High Quality)",
            "MP3 (256kbps)" to "🎵 MP3 Audio (256 kbps Standard)",
            "MP3 (128kbps)" to "🎵 MP3 Audio (128 kbps Compact)",
            "M4A (160kbps)" to "🎧 M4A Audio (160 kbps AAC)",
            "1080p Full HD" to "🎬 1080p Full HD Video",
            "720p HD" to "🎬 720p HD Video",
            "480p SD" to "🎬 480p SD Video",
            "360p Low" to "🎬 360p Low-Data Video"
        )

        AlertDialog(
            onDismissRequest = { if (!isDownloading) showDownloadModal = false },
            containerColor = Color(0xFF14141E),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = "Download", tint = Color(0xFFB388FF))
                    Text("Download Audio / Video ($videoId)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select your preferred audio/video format and quality to save offline:", color = Color.LightGray, fontSize = 12.sp)

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        qualityOptions.forEach { (fmtKey, fmtLabel) ->
                            val isSelected = selectedFormat == fmtKey
                            Surface(
                                color = if (isSelected) Color(0xFF311B92) else Color(0xFF1E1E2A),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFFB388FF) else Color.Gray.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedFormat = fmtKey }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(fmtLabel, color = Color.White, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                    RadioButton(selected = isSelected, onClick = { selectedFormat = fmtKey }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFB388FF)))
                                }
                            }
                        }
                    }

                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { progressPct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFB388FF),
                            trackColor = Color.DarkGray
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDownloading = true
                        progressPct = 0
                        scope.launch {
                            val (success, msg) = com.example.util.YouTubeMediaDownloader.downloadMedia(
                                context = context,
                                rawUrlOrId = videoId,
                                qualityFormat = selectedFormat,
                                onProgress = { pct -> progressPct = pct }
                            )
                            isDownloading = false
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            if (success) showDownloadModal = false
                        }
                    },
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                ) {
                    Text(if (isDownloading) "Downloading..." else "Download Now ⬇️", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadModal = false }, enabled = !isDownloading) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}
