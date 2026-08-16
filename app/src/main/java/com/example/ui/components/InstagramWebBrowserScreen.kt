package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.AppViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramWebBrowserScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showAntiGramInfoDialog by remember { mutableStateOf(false) }
    var showControlsOverlay by remember { mutableStateOf(true) }

    val reelsBlocked by viewModel.instagramReelsBlocked.collectAsState()
    val storiesBlocked by viewModel.instagramStoriesBlocked.collectAsState()
    val messagesBlocked by viewModel.instagramMessagesBlocked.collectAsState()
    val exploreBlocked by viewModel.instagramExploreBlocked.collectAsState()
    val notificationsBlocked by viewModel.instagramNotificationsBlocked.collectAsState()
    val allowSharedReels by viewModel.instagramAllowSharedReels.collectAsState()

    val prefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    // Timer States (Persisted with target end timestamp)
    var showTimerSetupModal by remember { mutableStateOf(false) }
    var sessionTimerMinutes by remember { mutableIntStateOf(0) }
    var remainingSeconds by remember {
        val targetMs = prefs.getLong("antigram_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        val secs = if (targetMs > now) ((targetMs - now) / 1000).toInt() else 0
        mutableIntStateOf(secs)
    }
    var isTimerActive by remember {
        val targetMs = prefs.getLong("antigram_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        mutableStateOf(targetMs > now)
    }
    var showTimerExpiredModal by remember {
        val targetMs = prefs.getLong("antigram_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        mutableStateOf(targetMs in 1..now)
    }

    // Fallback URL: If both Reels and Stories are blocked, hide Home feed and go directly to DMs!
    val homeOrDmUrl = remember(reelsBlocked, storiesBlocked) {
        if (reelsBlocked && storiesBlocked) {
            "https://www.instagram.com/direct/inbox/"
        } else {
            "https://www.instagram.com/"
        }
    }

    DisposableEffect(Unit) {
        com.example.util.AppBlockHelper.isAntiGramWebAppOpen = true
        onDispose {
            com.example.util.AppBlockHelper.isAntiGramWebAppOpen = false
            try {
                webViewInstance?.stopLoading()
                webViewInstance?.onPause()
                webViewInstance?.pauseTimers()
                webViewInstance?.removeAllViews()
                webViewInstance?.destroy()
                webViewInstance = null
            } catch (_: Exception) {}
        }
    }

    // Countdown Timer logic
    LaunchedEffect(isTimerActive) {
        while (isTimerActive) {
            val targetMs = prefs.getLong("antigram_timer_end_ms", 0L)
            val now = System.currentTimeMillis()
            if (targetMs <= 0L) {
                isTimerActive = false
                break
            }
            val leftSecs = ((targetMs - now) / 1000).toInt()
            if (leftSecs <= 0) {
                remainingSeconds = 0
                isTimerActive = false
                showTimerExpiredModal = true
                prefs.edit().putLong("antigram_timer_end_ms", 0L).apply()
            } else {
                remainingSeconds = leftSecs
                delay(1000L)
            }
        }
    }

    val handleInstagramBack: () -> Unit = remember(reelsBlocked, storiesBlocked) {
        {
            val currentUrl = webViewInstance?.url ?: ""
            val uriPath = try {
                val uri = java.net.URI(currentUrl)
                uri.path ?: ""
            } catch (e: Exception) {
                ""
            }

            val isHome = uriPath.isEmpty() || uriPath == "/" || uriPath == "/index.html"
            val isDirectInbox = uriPath.startsWith("/direct") || uriPath == "/direct/inbox/"
            val isHomeBlockedAndAtDm = (reelsBlocked && storiesBlocked) && isDirectInbox

            if (isHome || isHomeBlockedAndAtDm || isDirectInbox) {
                onBack()
            } else if (webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else {
                onBack()
            }
        }
    }

    BackHandler(enabled = true) {
        handleInstagramBack()
    }

    val antiGramJs = remember(reelsBlocked, storiesBlocked, messagesBlocked, exploreBlocked, notificationsBlocked, allowSharedReels) {
        com.example.util.PersistentWebMediaManager.buildAntiGramScript(
            reelsBlocked = reelsBlocked,
            storiesBlocked = storiesBlocked,
            messagesBlocked = messagesBlocked,
            exploreBlocked = exploreBlocked,
            notificationsBlocked = notificationsBlocked,
            allowSharedReels = allowSharedReels,
            homeOrDmUrl = homeOrDmUrl
        )
    }

    fun startTimer(minutes: Int) {
        sessionTimerMinutes = minutes
        if (minutes > 0) {
            val targetMs = System.currentTimeMillis() + (minutes * 60 * 1000L)
            prefs.edit().putLong("antigram_timer_end_ms", targetMs).apply()
            remainingSeconds = minutes * 60
            isTimerActive = true
        } else {
            prefs.edit().putLong("antigram_timer_end_ms", -1L).apply()
            isTimerActive = false
        }
        showTimerSetupModal = false
        showTimerExpiredModal = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        // Native standalone WebView consuming 100% full screen
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("instagram_webview"),
            factory = { ctx ->
                com.example.util.PersistentWebMediaManager.getOrCreateInstagramWebView(
                    context = ctx,
                    reelsBlocked = reelsBlocked,
                    storiesBlocked = storiesBlocked,
                    messagesBlocked = messagesBlocked,
                    exploreBlocked = exploreBlocked,
                    notificationsBlocked = notificationsBlocked,
                    allowSharedReels = allowSharedReels,
                    onPageStartedCallback = { isLoading = true },
                    onPageFinishedCallback = { isLoading = false }
                ).also {
                    webViewInstance = it
                }
            },
            update = { webView ->
                webViewInstance = webView
            },
            onRelease = { wv ->
                com.example.util.PersistentWebMediaManager.detachFromParent(wv)
            }
        )

        // Loading Progress Indicator
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color(0xFFE1306C),
                trackColor = Color(0xFF222222)
            )
        }

        // Minimalist Floating Exit & Refresh Pill Overlay
        AnimatedVisibility(
            visible = showControlsOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // Active Session Timer Pill
                if (isTimerActive && remainingSeconds > 0) {
                    val minutes = remainingSeconds / 60
                    val seconds = remainingSeconds % 60
                    val timeString = "%02d:%02d".format(minutes, seconds)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE1306C).copy(alpha = 0.25f))
                            .clickable { showTimerSetupModal = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Timer",
                            tint = Color(0xFFE1306C),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = timeString,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    IconButton(
                        onClick = { showTimerSetupModal = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("instagram_floating_timer_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Set Timer",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                IconButton(
                    onClick = handleInstagramBack,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("instagram_floating_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { webViewInstance?.reload() },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("instagram_floating_refresh_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { showAntiGramInfoDialog = true },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("instagram_floating_info_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color(0xFFE1306C),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("instagram_floating_exit_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit to LifeOS",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    // 1. Initial Session Timer Setup Pop-up
    if (showTimerSetupModal) {
        AlertDialog(
            onDismissRequest = { showTimerSetupModal = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = Color(0xFFE1306C),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Instagram App Usage Limit ⏱️",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Set an app usage time limit for this Instagram session to keep track of your time:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 5, 10, 15).forEach { min ->
                            Button(
                                onClick = { startTimer(min) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("timer_option_${min}m"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE1306C).copy(alpha = 0.2f),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE1306C).copy(alpha = 0.6f)),
                                    width = 1.dp
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "${min}M",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (prefs.getLong("antigram_timer_end_ms", 0L) == 0L) {
                            prefs.edit().putLong("antigram_timer_end_ms", -1L).apply()
                        }
                        showTimerSetupModal = false
                    },
                    modifier = Modifier.testTag("timer_setup_close_btn")
                ) {
                    Text("Close (No Timer)", color = Color.Gray, fontSize = 12.sp)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 2. Timer Expired Pop-up (Asks again to extend or exit)
    if (showTimerExpiredModal) {
        AlertDialog(
            onDismissRequest = { showTimerExpiredModal = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Session Time's Up! ⌛",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Your $sessionTimerMinutes minute Instagram session has ended. Would you like to extend session or exit to LifeOS?",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    Text(
                        text = "Extend session by:",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 5, 10, 15).forEach { min ->
                            Button(
                                onClick = { startTimer(min) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .testTag("timer_extend_${min}m"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF333333),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "+${min}M",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                    modifier = Modifier.testTag("timer_expired_exit_btn")
                ) {
                    Text("Exit Instagram", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTimerExpiredModal = false },
                    modifier = Modifier.testTag("timer_expired_close_btn")
                ) {
                    Text("Close", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // 3. Info Dialog
    if (showAntiGramInfoDialog) {
        AlertDialog(
            onDismissRequest = { showAntiGramInfoDialog = false },
            title = {
                Text(
                    text = "AntiGram Protection 🛡️",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Native AntiGram view active with custom filtering:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "• Reels: " + (if (reelsBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                "• Stories: " + (if (storiesBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                "• Explore Search: " + (if (exploreBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                "• Notifications Tab: " + (if (notificationsBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                "• Direct Messages: " + (if (messagesBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                "• Mode: " + (if (reelsBlocked && storiesBlocked) "Direct Messages Only (Home Feed Hidden) 💬" else "Feed View 📱") + "\n" +
                                "• Standalone full screen with session timer support ⏱️",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAntiGramInfoDialog = false },
                    modifier = Modifier.testTag("antigram_info_dialog_ok_btn")
                ) {
                    Text("OK", color = Color(0xFFE1306C), fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

