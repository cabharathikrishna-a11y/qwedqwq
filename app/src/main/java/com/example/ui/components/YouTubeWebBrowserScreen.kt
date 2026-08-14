package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.AppViewModel
import com.example.util.ActivePlayerType
import com.example.util.MediaActionCommand
import com.example.util.PersistentWebMediaManager
import com.example.util.UnifiedMediaNotificationManager
import com.example.util.YouTubeMediaDownloader
import com.example.util.YtDownloadItem
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebBrowserScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showAntiTubeInfoDialog by remember { mutableStateOf(false) }
    var showControlsOverlay by remember { mutableStateOf(true) }

    val shortsBlocked by viewModel.youtubeShortsBlocked.collectAsState()
    val allowSubscribedShorts by viewModel.youtubeAllowSubscribedShorts.collectAsState()
    val blockHomeFeed by viewModel.youtubeBlockHomeFeed.collectAsState()
    val searchBlocked by viewModel.youtubeSearchBlocked.collectAsState()
    val commentsBlocked by viewModel.youtubeCommentsBlocked.collectAsState()

    val prefs = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    var isAdBlockEnabled by remember {
        mutableStateOf(prefs.getBoolean("youtube_adblock_enabled", true))
    }

    var currentPlaybackSpeed by remember {
        mutableFloatStateOf(prefs.getFloat("youtube_playback_speed", 1.0f))
    }

    var showSpeedSelectorModal by remember { mutableStateOf(false) }

    // YT Active Media & Video Playback States (Source of Truth)
    val isYoutubeVideoActive by PersistentWebMediaManager.isYoutubeVideoActive.collectAsState()
    val isYoutubePlaying by PersistentWebMediaManager.isYoutubePlaying.collectAsState()
    val liveVideoUrl by PersistentWebMediaManager.youtubeCurrentVideoUrl.collectAsState()

    // YT Download & Offline States
    var showDownloadModal by remember { mutableStateOf(false) }
    var showOfflineModal by remember { mutableStateOf(false) }
    var downloadUrlInput by remember { mutableStateOf("") }
    var selectedDownloadFormat by remember { mutableStateOf("MP3 (320kbps)") }
    var isDownloadingYt by remember { mutableStateOf(false) }
    var downloadProgressPct by remember { mutableIntStateOf(0) }
    var detectedVideoDuration by remember { mutableLongStateOf(0L) }
    var isFetchingDuration by remember { mutableStateOf(false) }
    var offlineLibraryItems by remember { mutableStateOf<List<YtDownloadItem>>(emptyList()) }
    var playingOfflineItem by remember { mutableStateOf<YtDownloadItem?>(null) }
    val scope = rememberCoroutineScope()

    // Fetch video length dynamically when download modal opens or URL changes
    LaunchedEffect(showDownloadModal, downloadUrlInput) {
        if (showDownloadModal) {
            val liveDuration = PersistentWebMediaManager.youtubeDurationSeconds.value
            if (liveDuration > 0) {
                detectedVideoDuration = liveDuration
            }
            PersistentWebMediaManager.queryActiveVideoDuration { dur ->
                if (dur > 0) detectedVideoDuration = dur
            }
            val videoId = YouTubeMediaDownloader.extractVideoId(downloadUrlInput)
            if (videoId != null && detectedVideoDuration <= 0) {
                isFetchingDuration = true
                val fetched = YouTubeMediaDownloader.fetchVideoDurationSeconds(videoId)
                if (fetched > 0) {
                    detectedVideoDuration = fetched
                }
                isFetchingDuration = false
            }
        }
    }

    // Timer States (Persisted with target end timestamp)
    var showTimerSetupModal by remember { mutableStateOf(false) }
    var sessionTimerMinutes by remember { mutableIntStateOf(0) }
    var remainingSeconds by remember {
        val targetMs = prefs.getLong("antitube_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        val secs = if (targetMs > now) ((targetMs - now) / 1000).toInt() else 0
        mutableIntStateOf(secs)
    }
    var isTimerActive by remember {
        val targetMs = prefs.getLong("antitube_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        mutableStateOf(targetMs > now)
    }
    var showTimerExpiredModal by remember {
        val targetMs = prefs.getLong("antitube_timer_end_ms", 0L)
        val now = System.currentTimeMillis()
        mutableStateOf(targetMs in 1..now)
    }

    // Start URL: If Home feed is blocked, go directly to Subscriptions feed!
    val startUrl = remember(blockHomeFeed) {
        if (blockHomeFeed) {
            "https://www.youtube.com/feed/subscriptions"
        } else {
            "https://www.youtube.com/"
        }
    }

    DisposableEffect(Unit) {
        com.example.util.AppBlockHelper.isAntiTubeWebAppOpen = true
        onDispose {
            com.example.util.AppBlockHelper.isAntiTubeWebAppOpen = false
            // Preserve background video and audio playback across screens and floating PiP overlay
        }
    }

    // Countdown Timer logic
    LaunchedEffect(isTimerActive) {
        while (isTimerActive) {
            val targetMs = prefs.getLong("antitube_timer_end_ms", 0L)
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
                prefs.edit().putLong("antitube_timer_end_ms", 0L).apply()
            } else {
                remainingSeconds = leftSecs
                delay(1000L)
            }
        }
    }

    val handleYouTubeBack: () -> Unit = remember(blockHomeFeed) {
        {
            val currentUrl = webViewInstance?.url ?: ""
            val uriPath = try {
                val uri = java.net.URI(currentUrl)
                uri.path ?: ""
            } catch (e: Exception) {
                ""
            }

            val isHome = uriPath.isEmpty() || uriPath == "/" || uriPath == "/index.html"
            val isSubscriptions = uriPath.startsWith("/feed/subscriptions") || uriPath == "/feed/subscriptions"
            val isHomeBlockedAndAtSubscriptions = blockHomeFeed && isSubscriptions

            if (isHome || isSubscriptions || isHomeBlockedAndAtSubscriptions) {
                onBack()
            } else if (webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else {
                onBack()
            }
        }
    }

    BackHandler(enabled = true) {
        handleYouTubeBack()
    }

    // Subscribe to notification controls (Play, Pause, FastForward, Rewind, Speed, Next/Skip Ad, Stop)
    LaunchedEffect(Unit) {
        UnifiedMediaNotificationManager.actionCommands.collect { cmd ->
            when (cmd) {
                is MediaActionCommand.PlayPause -> {
                    webViewInstance?.evaluateJavascript(
                        "var v = document.querySelector('video'); if (v) { if (v.paused) v.play(); else v.pause(); }",
                        null
                    )
                }
                is MediaActionCommand.Rewind -> {
                    webViewInstance?.evaluateJavascript(
                        "var v = document.querySelector('video'); if (v) v.currentTime = Math.max(0, v.currentTime - 10);",
                        null
                    )
                }
                is MediaActionCommand.FastForward -> {
                    webViewInstance?.evaluateJavascript(
                        "var v = document.querySelector('video'); if (v) v.currentTime = Math.min(v.duration || 999999, v.currentTime + 10);",
                        null
                    )
                }
                is MediaActionCommand.ChangeSpeed -> {
                    currentPlaybackSpeed = cmd.speed
                    prefs.edit().putFloat("youtube_playback_speed", cmd.speed).apply()
                    webViewInstance?.evaluateJavascript(
                        "window.__targetPlaybackSpeed = ${cmd.speed}f; window.__applyPlaybackRate();",
                        null
                    )
                }
                is MediaActionCommand.NextOrSkipAd -> {
                    webViewInstance?.evaluateJavascript(
                        "var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern'); if (skipBtn) { skipBtn.click(); } else { var nextBtn = document.querySelector('.ytp-next-button'); if (nextBtn) nextBtn.click(); }",
                        null
                    )
                }
                is MediaActionCommand.Stop -> {
                    webViewInstance?.evaluateJavascript(
                        "var v = document.querySelector('video'); if (v) v.pause();",
                        null
                    )
                }
            }
        }
    }

    LaunchedEffect(currentPlaybackSpeed) {
        val title = webViewInstance?.title ?: "YouTube Video"
        val cleanTitle = if (title.isBlank() || title.contains("m.youtube")) "YouTube Player" else title.replace("- YouTube", "").trim()
        UnifiedMediaNotificationManager.updateState(
            context = context,
            title = cleanTitle,
            subtitle = "YouTube • AdBlock Active 🛡️",
            playing = true,
            speed = currentPlaybackSpeed,
            playerType = ActivePlayerType.YOUTUBE
        )
    }

    val antiTubeJs = remember(shortsBlocked, allowSubscribedShorts, blockHomeFeed, searchBlocked, commentsBlocked, isAdBlockEnabled, currentPlaybackSpeed) {
        """
        (function() {
            try {
                var h = (window.location && window.location.hostname) ? window.location.hostname : '';
                var u = (window.location && window.location.href) ? window.location.href : '';
                if (h.indexOf('accounts.google') !== -1 || h.indexOf('accounts.youtube') !== -1 || h.indexOf('myaccount.google') !== -1 || h.indexOf('ssl.gstatic') !== -1 || u.indexOf('ServiceLogin') !== -1 || u.indexOf('signin') !== -1) {
                    return;
                }
            } catch(e) {}

            window.__targetPlaybackSpeed = ${currentPlaybackSpeed}f;
            window.__adBlockEnabled = ${isAdBlockEnabled};
            window.__allowBgPlay = true;

            try {
                if (!window.__winDeviceSpoofed) {
                    window.__winDeviceSpoofed = true;
                    Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
                    Object.defineProperty(navigator, 'vendor', { get: function() { return 'Google Inc.'; }, configurable: true });
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
                    Object.defineProperty(navigator, 'userAgent', { get: function() { return 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'; }, configurable: true });
                    Object.defineProperty(navigator, 'appVersion', { get: function() { return '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36'; }, configurable: true });
                    if (navigator.userAgentData) {
                        Object.defineProperty(navigator, 'userAgentData', {
                            get: function() {
                                return {
                                    brands: [
                                        { brand: 'Not;A=Brand', version: '24' },
                                        { brand: 'Chromium', version: '128' },
                                        { brand: 'Google Chrome', version: '128' }
                                    ],
                                    mobile: false,
                                    platform: 'Windows',
                                    getHighEntropyValues: function() {
                                        return Promise.resolve({
                                            architecture: 'x86',
                                            bitness: '64',
                                            model: '',
                                            platform: 'Windows',
                                            platformVersion: '15.0.0',
                                            uaFullVersion: '128.0.0.0'
                                        });
                                    }
                                };
                            },
                            configurable: true
                        });
                    }
                }
            } catch(e) {}

            try {
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            } catch(e) {}

            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function(evt) {
                window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });

            if (!window.__bgPlayMonitor) {
                window.__bgPlayMonitor = setInterval(function() {
                    var v = document.querySelector('video');
                    if (v && v.paused && !v.ended && window.__allowBgPlay) {
                        try { v.play(); } catch(err) {}
                    }
                }, 800);
            }

            var oldStyle = document.getElementById('antitube-styles');
            if (oldStyle) oldStyle.remove();

            var style = document.createElement('style');
            style.id = 'antitube-styles';
            var css = `
                video {
                    max-height: 100vh !important;
                    object-fit: contain !important;
                }
            `;

            ${if (isAdBlockEnabled) """
                css += `
                    .video-ads,
                    .ytp-ad-module,
                    .ytp-ad-overlay-container,
                    .ytp-ad-image-overlay,
                    .ytp-ad-text-overlay,
                    .ytp-ad-skip-button-slot,
                    .ad-container,
                    .ad-div,
                    #player-ads,
                    ytd-promoted-sparkles-web-renderer,
                    ytm-promoted-sparkles-web-renderer,
                    ytd-display-ad-renderer,
                    ytm-companion-ad-renderer,
                    ytm-promoted-video-renderer,
                    ytd-banner-promo-renderer,
                    ytd-statement-banner-renderer,
                    ytd-in-feed-ad-layout-renderer,
                    ytm-in-feed-ad-layout-renderer,
                    .sparkles-light-cta,
                    #masthead-ad,
                    ytd-ad-slot-renderer,
                    ytm-ad-slot-renderer,
                    .ad-showing,
                    .ad-interrupting,
                    ytm-promoted-item-renderer,
                    .ytp-ad-overlay-open {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (shortsBlocked) """
                css += `
                    a[href*="/shorts/"],
                    ytm-reel-shelf-renderer,
                    ytd-reel-shelf-renderer,
                    ytm-shorts-lockup-view-model,
                    .pivot-shorts,
                    [aria-label*="Shorts"] {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (blockHomeFeed) """
                css += `
                    ytd-browse[page-subtype="home"],
                    ytm-browse[page-subtype="home"],
                    a[aria-label*="Home"] {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (searchBlocked) """
                css += `
                    a[href*="/results"],
                    .header-bar-search,
                    ytd-searchbox,
                    button[aria-label="Search"],
                    button[aria-label="Search YouTube"],
                    c3-icon[type="search"] {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (commentsBlocked) """
                css += `
                    #comments,
                    ytm-comments-entry-point-header-renderer,
                    ytd-comments {
                        display: none !important;
                    }
                `;
            """ else ""}

            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);

            window.__applyPlaybackRate = function() {
                var targetSpeed = window.__targetPlaybackSpeed || 1.0;
                var videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    if (!v) return;
                    var isAd = (v.closest && (v.closest('.ad-interrupting') || v.closest('.ad-showing'))) || v.classList.contains('ad-showing') || document.querySelector('.ytp-ad-player-overlay');
                    if (!isAd && Math.abs(v.playbackRate - targetSpeed) > 0.01) {
                        v.playbackRate = targetSpeed;
                    }
                    if (!v.__speedListenersBound) {
                        v.__speedListenersBound = true;
                        ['play', 'playing', 'ratechange', 'loadedmetadata'].forEach(function(evt) {
                            v.addEventListener(evt, function() {
                                var desired = window.__targetPlaybackSpeed || 1.0;
                                var ad = (v.closest && (v.closest('.ad-interrupting') || v.closest('.ad-showing'))) || v.classList.contains('ad-showing') || document.querySelector('.ytp-ad-player-overlay');
                                if (!ad && Math.abs(v.playbackRate - desired) > 0.01) {
                                    v.playbackRate = desired;
                                }
                            });
                        });
                    }
                });
            };

            window.__handleAdsAndSpeed = function() {
                if (window.__adBlockEnabled) {
                    var skipSelectors = [
                        '.ytp-ad-skip-button',
                        '.ytp-skip-ad-button',
                        '.ytp-ad-skip-button-modern',
                        '.ytp-ad-skip-button-slot',
                        '.ytp-ad-overlay-close-button',
                        'button.ytp-ad-skip-button',
                        '.ytp-ad-text.ytp-ad-skip-button-text',
                        'button.ytp-ad-skip-button-modern',
                        '.ytp-ad-preview-container'
                    ];
                    skipSelectors.forEach(function(sel) {
                        var btn = document.querySelector(sel);
                        if (btn) {
                            try { btn.click(); } catch(e) {}
                        }
                    });

                    var adOverlay = document.querySelector('.ad-interrupting, .ad-showing, .ytp-ad-player-overlay');
                    var videos = document.querySelectorAll('video');
                    videos.forEach(function(v) {
                        if (adOverlay || v.classList.contains('ad-showing') || (v.closest && v.closest('.ad-interrupting'))) {
                            v.muted = true;
                            v.playbackRate = 16.0;
                            if (v.duration && !isNaN(v.duration) && v.currentTime < v.duration) {
                                v.currentTime = v.duration - 0.05;
                            }
                        }
                    });
                }

                window.__applyPlaybackRate();
            };

            function applyRouteGuards() {
                var path = window.location.pathname || '';

                if (${blockHomeFeed} && (path === '/' || path === '' || path === '/index.html')) {
                    window.location.href = '${startUrl}';
                    return;
                }

                if (${shortsBlocked} && path.startsWith('/shorts')) {
                    if (${allowSubscribedShorts}) {
                        if (!window.__shortLockAttached) {
                            window.__shortLockAttached = true;
                            window.addEventListener('touchstart', function(e) {
                                window.__touchStartY = e.touches[0].clientY;
                            }, { passive: true });
                            window.addEventListener('touchend', function(e) {
                                if (!window.__touchStartY) return;
                                var diff = window.__touchStartY - e.changedTouches[0].clientY;
                                if (Math.abs(diff) > 120) {
                                    window.location.href = '${startUrl}';
                                }
                            }, { passive: true });
                        }
                    } else {
                        window.location.href = '${startUrl}';
                        return;
                    }
                }

                if (${searchBlocked} && (path.startsWith('/results') || path.startsWith('/search'))) {
                    window.location.href = '${startUrl}';
                    return;
                }
            }

            applyRouteGuards();
            window.__handleAdsAndSpeed();

            if (!window.__antiTubeObserver) {
                window.__antiTubeObserver = new MutationObserver(function() {
                    applyRouteGuards();
                    window.__handleAdsAndSpeed();
                });
                window.__antiTubeObserver.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true
                });
            }

            if (!window.__antiTubeInterval) {
                window.__antiTubeInterval = setInterval(function() {
                    window.__handleAdsAndSpeed();
                }, 250);
            }
        })();
        """.trimIndent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("youtube_web_browser_container")
    ) {
        AndroidView<WebView>(
            factory = { ctx ->
                PersistentWebMediaManager.getOrCreateYouTubeWebView(
                    context = ctx,
                    shortsBlocked = shortsBlocked,
                    allowSubscribedShorts = allowSubscribedShorts,
                    blockHomeFeed = blockHomeFeed,
                    searchBlocked = searchBlocked,
                    commentsBlocked = commentsBlocked,
                    isAdBlockEnabled = isAdBlockEnabled,
                    currentPlaybackSpeed = currentPlaybackSpeed,
                    onPageStartedCallback = { isLoading = true },
                    onPageFinishedCallback = { isLoading = false }
                ).also {
                    webViewInstance = it
                }
            },
            update = { view ->
                view.evaluateJavascript(antiTubeJs, null)
            },
            onRelease = { wv ->
                PersistentWebMediaManager.detachFromParent(wv)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading Indicator
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color(0xFFFF0000),
                trackColor = Color.DarkGray.copy(alpha = 0.3f)
            )
        }

        // Top Floating Control Bar
        AnimatedVisibility(
            visible = showControlsOverlay,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = Color(0xFF121216).copy(alpha = 0.95f),
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.3f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = handleYouTubeBack,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("youtube_floating_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "YouTube Icon",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "YouTube",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (blockHomeFeed) "Subscriptions 📺" else "Clean Feed 📱",
                                color = Color.Gray,
                                fontSize = 9.sp
                            )
                        }
                    }

                    // Quick Control Pills: Speed Selector & AdBlock Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Playback Speed Pill Button
                        Surface(
                            color = Color(0xFF22222E),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showSpeedSelectorModal = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = "Playback Speed",
                                    tint = Color(0xFFFF4D4D),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (currentPlaybackSpeed % 1f == 0f) "${currentPlaybackSpeed.toInt()}x" else String.format("%.2fx", currentPlaybackSpeed),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // AdBlock Pill Button
                        Surface(
                            color = if (isAdBlockEnabled) Color(0xFF1B382B) else Color(0xFF2A2A33),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isAdBlockEnabled) Color(0xFF00E676).copy(alpha = 0.6f) else Color.Gray.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    isAdBlockEnabled = !isAdBlockEnabled
                                    prefs.edit().putBoolean("youtube_adblock_enabled", isAdBlockEnabled).apply()
                                    webViewInstance?.evaluateJavascript("window.__adBlockEnabled = $isAdBlockEnabled; window.__handleAdsAndSpeed();", null)
                                    Toast.makeText(context, if (isAdBlockEnabled) "AdBlock Enabled 🛡️" else "AdBlock Disabled", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAdBlockEnabled) Icons.Default.Shield else Icons.Default.Block,
                                    contentDescription = "AdBlock",
                                    tint = if (isAdBlockEnabled) Color(0xFF00E676) else Color.LightGray,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (isAdBlockEnabled) "AdBlock" else "Ads ON",
                                    color = if (isAdBlockEnabled) Color(0xFF00E676) else Color.LightGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // YT Download Pill Button (Visible ONLY when a video is played or paused, NOT on home screen / feeds)
                        val isWatchUrl = remember(webViewInstance?.url, liveVideoUrl) {
                            val u = webViewInstance?.url ?: liveVideoUrl
                            u.contains("/watch") || u.contains("/shorts/") || u.contains("youtu.be/")
                        }
                        val showDownloadButton = isYoutubeVideoActive || isYoutubePlaying || isWatchUrl

                        if (showDownloadButton) {
                            Surface(
                                color = Color(0xFF2A1F3D),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFB388FF).copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        downloadUrlInput = (webViewInstance?.url ?: liveVideoUrl).ifBlank { startUrl }
                                        showDownloadModal = true
                                    }
                                    .testTag("youtube_download_topbar_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download MP3 / Video",
                                        tint = Color(0xFFB388FF),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Download",
                                        color = Color(0xFFE1BEE7),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Offline Downloads Library Pill Button
                        Surface(
                            color = Color(0xFF1F2B3D),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF80D8FF).copy(alpha = 0.6f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    offlineLibraryItems = com.example.util.YouTubeMediaDownloader.getDownloadedLibrary(context)
                                    showOfflineModal = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderSpecial,
                                    contentDescription = "Offline Library",
                                    tint = Color(0xFF80D8FF),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Offline",
                                    color = Color(0xFFB3E5FC),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Share YT Link Pill Button
                        Surface(
                            color = Color(0xFF1E3A2F),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF69F0AE).copy(alpha = 0.6f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val currentUrl = webViewInstance?.url ?: startUrl
                                    val currentTitle = webViewInstance?.title ?: "YouTube"
                                    com.example.util.YouTubeMediaDownloader.shareYouTubeLink(context, currentUrl, currentTitle)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Link",
                                    tint = Color(0xFF69F0AE),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Share",
                                    color = Color(0xFFB9F6CA),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Float Mini Video Player (Picture-in-Picture) Pill Button
                        Surface(
                            color = Color(0xFF4A1525),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.8f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    PersistentWebMediaManager.setYoutubePipActive(true)
                                    Toast.makeText(context, "YouTube: Floating Mini Player Active 📺", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                                .testTag("youtube_float_pip_topbar_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPicture,
                                    contentDescription = "Float Mini Player",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Float PiP",
                                    color = Color(0xFFFFCDD2),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Timer Indicator
                        if (isTimerActive && remainingSeconds > 0) {
                            val mins = remainingSeconds / 60
                            val secs = remainingSeconds % 60
                            Surface(
                                color = Color(0xFFFF0000).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Timer",
                                        tint = Color(0xFFFF4D4D),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = String.format("%02d:%02d", mins, secs),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { showAntiTubeInfoDialog = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "AntiTube Info",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { showControlsOverlay = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Minimize Bar",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Floating Show-Bar Button when minimized
        if (!showControlsOverlay) {
            FloatingActionButton(
                onClick = { showControlsOverlay = true },
                containerColor = Color(0xFFFF0000),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Controls",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Speed & AdBlock Controls Modal
        if (showSpeedSelectorModal) {
            val presetSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f, 3.25f, 3.5f, 3.75f, 4.0f, 4.5f, 5.0f)

            AlertDialog(
                onDismissRequest = { showSpeedSelectorModal = false },
                containerColor = Color(0xFF14141A),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Speed Controls",
                            tint = Color(0xFFFF4D4D)
                        )
                        Text(
                            text = "High-Speed Playback & Ads",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Select high speed playback (2.5x, 3x, 3.5x, 4x, etc.) or toggle automatic video ad blocking.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        // Current speed display badge
                        Surface(
                            color = Color(0xFF22222E),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Active Playback Speed:", color = Color.Gray, fontSize = 12.sp)
                                Text(
                                    text = if (currentPlaybackSpeed % 1f == 0f) "${currentPlaybackSpeed.toInt()}.0x Speed ⚡" else String.format("%.2fx Speed ⚡", currentPlaybackSpeed),
                                    color = Color(0xFFFF4D4D),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Text("High-Speed Shortcuts (2.5x - 4.0x):", color = Color(0xFFFF0000), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(2.5f, 3.0f, 3.5f, 4.0f).forEach { speed ->
                                val isSelected = Math.abs(currentPlaybackSpeed - speed) < 0.01f
                                Surface(
                                    color = if (isSelected) Color(0xFFFF0000) else Color(0xFF222230),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isSelected) Color.White else Color(0xFFFF0000).copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            currentPlaybackSpeed = speed
                                            prefs.edit().putFloat("youtube_playback_speed", speed).apply()
                                            webViewInstance?.evaluateJavascript("window.__targetPlaybackSpeed = ${speed}f; window.__applyPlaybackRate();", null)
                                            Toast.makeText(context, "Playback speed set to ${if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"} ⚡", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        Text("All Speed Presets:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presetSpeeds) { speed ->
                                val isSelected = Math.abs(currentPlaybackSpeed - speed) < 0.01f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        currentPlaybackSpeed = speed
                                        prefs.edit().putFloat("youtube_playback_speed", speed).apply()
                                        webViewInstance?.evaluateJavascript("window.__targetPlaybackSpeed = ${speed}f; window.__applyPlaybackRate();", null)
                                    },
                                    label = {
                                        Text(
                                            text = if (speed % 1f == 0f) "${speed.toInt()}x" else String.format("%.2fx", speed),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFFF4D4D),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF22222B),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }
                        }

                        // Fine-tuning Controls (-0.25x / +0.25x)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val newSpeed = maxOf(0.25f, currentPlaybackSpeed - 0.25f)
                                    currentPlaybackSpeed = newSpeed
                                    prefs.edit().putFloat("youtube_playback_speed", newSpeed).apply()
                                    webViewInstance?.evaluateJavascript("window.__targetPlaybackSpeed = ${newSpeed}f; window.__applyPlaybackRate();", null)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                            ) {
                                Text("-0.25x", color = Color.White, fontSize = 11.sp)
                            }

                            Text(
                                text = if (currentPlaybackSpeed % 1f == 0f) "${currentPlaybackSpeed.toInt()}x" else String.format("%.2fx", currentPlaybackSpeed),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )

                            OutlinedButton(
                                onClick = {
                                    val newSpeed = minOf(5.0f, currentPlaybackSpeed + 0.25f)
                                    currentPlaybackSpeed = newSpeed
                                    prefs.edit().putFloat("youtube_playback_speed", newSpeed).apply()
                                    webViewInstance?.evaluateJavascript("window.__targetPlaybackSpeed = ${newSpeed}f; window.__applyPlaybackRate();", null)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                            ) {
                                Text("+0.25x", color = Color.White, fontSize = 11.sp)
                            }
                        }

                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.4f))

                        // AdBlock Toggle Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("YouTube Ad Blocker 🛡️", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Blocks video ads, skip buttons, and banner ads automatically", color = Color.Gray, fontSize = 10.sp)
                            }
                            Switch(
                                checked = isAdBlockEnabled,
                                onCheckedChange = {
                                    isAdBlockEnabled = it
                                    prefs.edit().putBoolean("youtube_adblock_enabled", it).apply()
                                    webViewInstance?.evaluateJavascript("window.__adBlockEnabled = $it; window.__handleAdsAndSpeed();", null)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFF00E676)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSpeedSelectorModal = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4D)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Done", color = Color.White)
                    }
                }
            )
        }

        // Modal 1: Session Timer Setup Modal
        if (showTimerSetupModal) {
            AlertDialog(
                onDismissRequest = { showTimerSetupModal = false },
                containerColor = Color(0xFF141418),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Timer",
                            tint = Color(0xFFFF0000)
                        )
                        Text("YouTube Focus Session Timer", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Set a intentional time limit for your YouTube session. Once the timer ends, access will be locked to keep you focused.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        Text("Quick Session Presets:", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(15, 30, 45, 60).forEach { mins ->
                                FilterChip(
                                    selected = sessionTimerMinutes == mins,
                                    onClick = { sessionTimerMinutes = mins },
                                    label = { Text("${mins}m", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFFF0000),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF22222B),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }
                        }

                        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.4f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Unlimited / No Timer", color = Color.White, fontSize = 12.sp)
                            Switch(
                                checked = sessionTimerMinutes == 0,
                                onCheckedChange = { if (it) sessionTimerMinutes = 0 else sessionTimerMinutes = 15 },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFF0000)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (sessionTimerMinutes > 0) {
                                val targetMs = System.currentTimeMillis() + (sessionTimerMinutes * 60 * 1000L)
                                prefs.edit().putLong("antitube_timer_end_ms", targetMs).apply()
                                remainingSeconds = sessionTimerMinutes * 60
                                isTimerActive = true
                            } else {
                                prefs.edit().putLong("antitube_timer_end_ms", -1L).apply()
                                isTimerActive = false
                            }
                            showTimerSetupModal = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (sessionTimerMinutes > 0) "Start Session (${sessionTimerMinutes}m)" else "Continue Without Timer", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (prefs.getLong("antitube_timer_end_ms", 0L) == 0L) {
                            prefs.edit().putLong("antitube_timer_end_ms", -1L).apply()
                        }
                        showTimerSetupModal = false
                    }) {
                        Text("Skip", color = Color.Gray)
                    }
                }
            )
        }

        // Modal 2: Session Timer Expired Lock Screen
        if (showTimerExpiredModal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.96f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181820)),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF0000).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = Color(0xFFFF0000),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Text(
                            text = "Session Time Expired! ⏱️",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = "Your YouTube focus time limit has concluded. Time to take a mindful break and return to your focus goals!",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    remainingSeconds = 15 * 60
                                    isTimerActive = true
                                    showTimerExpiredModal = false
                                },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, Color.Gray)
                            ) {
                                Text("+15m Extra", color = Color.White, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    showTimerExpiredModal = false
                                    onBack()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
                            ) {
                                Text("Close YouTube", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // Modal 3: AntiTube Info Dialog
        if (showAntiTubeInfoDialog) {
            AlertDialog(
                onDismissRequest = { showAntiTubeInfoDialog = false },
                containerColor = Color(0xFF141418),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = "AntiTube Info", tint = Color(0xFFFF0000))
                        Text("AntiTube Active Safeguards", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Active YouTube Rules:",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Ad Blocking: " + (if (isAdBlockEnabled) "Enabled 🛡️ (Video ads & banners blocked)" else "Disabled ⚠️") + "\n" +
                                    "• Playback Speed: " + (if (currentPlaybackSpeed % 1f == 0f) "${currentPlaybackSpeed.toInt()}x ⚡" else String.format("%.2fx ⚡", currentPlaybackSpeed)) + "\n" +
                                    "• Shorts: " + (if (shortsBlocked) (if (allowSubscribedShorts) "Blocked (Subscribed Channels Allowed) 🚫" else "Blocked 🚫") else "Allowed ✅") + "\n" +
                                    "• Home Feed: " + (if (blockHomeFeed) "Blocked (Subscriptions Feed Only) 📺" else "Allowed ✅") + "\n" +
                                    "• Search Feed: " + (if (searchBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                    "• Comments: " + (if (commentsBlocked) "Blocked 🚫" else "Allowed ✅") + "\n" +
                                    "• Mode: " + (if (blockHomeFeed) "Subscriptions Only 📺" else "Clean Feed 📱"),
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAntiTubeInfoDialog = false }) {
                        Text("Got it", color = Color(0xFFFF0000))
                    }
                }
            )
        }

        // Modal 4: Download YT Video / MP3 Dialog
        if (showDownloadModal) {
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
                onDismissRequest = { if (!isDownloadingYt) showDownloadModal = false },
                containerColor = Color(0xFF14141E),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = Color(0xFFB388FF)
                        )
                        Text("Download YouTube Audio / Video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Download as MP3/M4A audio or MP4 video with real-time file size estimation based on video length.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        OutlinedTextField(
                            value = downloadUrlInput,
                            onValueChange = { 
                                downloadUrlInput = it 
                                val vid = YouTubeMediaDownloader.extractVideoId(it)
                                if (vid != null) {
                                    scope.launch {
                                        isFetchingDuration = true
                                        val fetched = YouTubeMediaDownloader.fetchVideoDurationSeconds(vid)
                                        if (fetched > 0) detectedVideoDuration = fetched
                                        isFetchingDuration = false
                                    }
                                }
                            },
                            label = { Text("YouTube Link or Video ID", color = Color.Gray, fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFB388FF),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        // Video Length & Estimation Info Header
                        Surface(
                            color = Color(0xFF1A1A2E),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Video Duration",
                                        tint = Color(0xFF69F0AE),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Video Length:",
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isFetchingDuration) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF69F0AE)
                                        )
                                        Text("Detecting...", color = Color(0xFF69F0AE), fontSize = 11.sp)
                                    } else if (detectedVideoDuration > 0) {
                                        Text(
                                            text = "${YouTubeMediaDownloader.formatDurationString(detectedVideoDuration)} (${detectedVideoDuration}s)",
                                            color = Color(0xFF69F0AE),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text(
                                            text = "Standard (Live Stream / Unknown)",
                                            color = Color.Yellow,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = {
                                        val vid = YouTubeMediaDownloader.extractVideoId(downloadUrlInput)
                                        scope.launch {
                                            isFetchingDuration = true
                                            PersistentWebMediaManager.queryActiveVideoDuration { dur ->
                                                if (dur > 0) detectedVideoDuration = dur
                                            }
                                            if (vid != null) {
                                                val fetched = YouTubeMediaDownloader.fetchVideoDurationSeconds(vid)
                                                if (fetched > 0) detectedVideoDuration = fetched
                                            }
                                            isFetchingDuration = false
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("Re-Check", color = Color(0xFFB388FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Select Quality & Estimated Size:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = {
                                    if (downloadUrlInput.isNotBlank()) {
                                        YouTubeMediaDownloader.shareYouTubeLink(context, downloadUrlInput)
                                    }
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = "Share Link", tint = Color(0xFF69F0AE), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share Link", color = Color(0xFF69F0AE), fontSize = 11.sp)
                            }
                        }

                        // Quality Options with Estimated Size Badges
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            qualityOptions.forEach { (fmtKey, fmtLabel) ->
                                val isSelected = selectedDownloadFormat == fmtKey
                                val isAudioFormat = fmtKey.contains("MP3") || fmtKey.contains("M4A")
                                val estimatedSizeStr = YouTubeMediaDownloader.calculateEstimatedFileSize(detectedVideoDuration, fmtKey)

                                Surface(
                                    color = if (isSelected) Color(0xFF281C48) else Color(0xFF191924),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFFB388FF) else Color.Gray.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectedDownloadFormat = fmtKey }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = fmtLabel,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Estimated File Size Badge
                                        Surface(
                                            color = if (isAudioFormat) Color(0xFF00331A) else Color(0xFF0D253A),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isAudioFormat) Color(0xFF00E676).copy(alpha = 0.5f) else Color(0xFF29B6F6).copy(alpha = 0.5f)
                                            )
                                        ) {
                                            Text(
                                                text = estimatedSizeStr,
                                                color = if (isAudioFormat) Color(0xFF69F0AE) else Color(0xFF81D4FA),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { selectedDownloadFormat = fmtKey },
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFB388FF))
                                        )
                                    }
                                }
                            }
                        }

                        // Selected Summary Banner
                        Surface(
                            color = Color(0xFF1E1632),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Format Info",
                                    tint = Color(0xFFB388FF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Est. Size: ${YouTubeMediaDownloader.calculateEstimatedFileSize(detectedVideoDuration, selectedDownloadFormat)} (${if (detectedVideoDuration > 0) YouTubeMediaDownloader.formatDurationString(detectedVideoDuration) else "Default"})",
                                    color = Color(0xFFE1BEE7),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (isDownloadingYt) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LinearProgressIndicator(
                                    progress = { downloadProgressPct / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFB388FF),
                                    trackColor = Color.DarkGray
                                )
                                Text("Downloading... $downloadProgressPct%", color = Color(0xFFB388FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (downloadUrlInput.isBlank()) {
                                Toast.makeText(context, "Please enter a valid YouTube URL or Video ID", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isDownloadingYt = true
                            downloadProgressPct = 0
                            scope.launch {
                                val (success, msg) = YouTubeMediaDownloader.downloadMedia(
                                    context = context,
                                    rawUrlOrId = downloadUrlInput,
                                    qualityFormat = selectedDownloadFormat,
                                    onProgress = { pct -> downloadProgressPct = pct }
                                )
                                isDownloadingYt = false
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                if (success) {
                                    offlineLibraryItems = YouTubeMediaDownloader.getDownloadedLibrary(context)
                                    showDownloadModal = false
                                    showOfflineModal = true
                                }
                            }
                        },
                        enabled = !isDownloadingYt,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isDownloadingYt) "Downloading..." else "Download Now ⬇️", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDownloadModal = false },
                        enabled = !isDownloadingYt
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            )
        }

        // Modal 5: Offline Downloads Library Modal
        if (showOfflineModal) {
            AlertDialog(
                onDismissRequest = { showOfflineModal = false },
                containerColor = Color(0xFF14141E),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = "Offline Library",
                            tint = Color(0xFF80D8FF)
                        )
                        Text("Offline YouTube Downloads", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        Text(
                            text = "Play offline, export to device storage, or share downloaded MP3 audio / video files with any app on your device.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )

                        if (offlineLibraryItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No offline downloads yet.\nUse Download button to save MP3 or videos!", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        } else {
                            androidx.compose.foundation.lazy.LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                items(offlineLibraryItems) { item ->
                                    Surface(
                                        color = Color(0xFF1E1E2A),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(10.dp)
                                                .fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    imageVector = if (item.isAudioOnly) Icons.Default.MusicNote else Icons.Default.Movie,
                                                    contentDescription = "Media Type",
                                                    tint = if (item.isAudioOnly) Color(0xFFFF4081) else Color(0xFF00E676),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.title,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${item.format} • ${String.format("%.1f MB", item.fileSize / (1024f * 1024f))}",
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            // Action Row for each download item
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Play
                                                IconButton(
                                                    onClick = { playingOfflineItem = item },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Play Offline",
                                                        tint = Color(0xFF00E676),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }

                                                // Share File to App
                                                IconButton(
                                                    onClick = {
                                                        com.example.util.YouTubeMediaDownloader.shareDownloadedFile(context, item)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = "Share File",
                                                        tint = Color(0xFFFFB74D),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // Save to Public Storage / Downloads
                                                IconButton(
                                                    onClick = {
                                                        val (success, msg) = com.example.util.YouTubeMediaDownloader.exportToPublicDownloads(context, item)
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.SaveAlt,
                                                        contentDescription = "Export to Downloads Folder",
                                                        tint = Color(0xFF80D8FF),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // Share YT Link
                                                IconButton(
                                                    onClick = {
                                                        com.example.util.YouTubeMediaDownloader.shareYouTubeLink(context, item.videoId, item.title)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Link,
                                                        contentDescription = "Share Link",
                                                        tint = Color(0xFFB388FF),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                // Delete
                                                IconButton(
                                                    onClick = {
                                                        com.example.util.YouTubeMediaDownloader.deleteDownloadedItem(context, item)
                                                        offlineLibraryItems = com.example.util.YouTubeMediaDownloader.getDownloadedLibrary(context)
                                                        Toast.makeText(context, "Deleted from offline downloads", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showOfflineModal = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Close", color = Color.White)
                    }
                }
            )
        }

        // Offline Player Inspector Dialog
        playingOfflineItem?.let { item ->
            com.example.ui.components.MediaInspectorDialog(
                mediaPath = item.filePath,
                onDismiss = { playingOfflineItem = null }
            )
        }
    }
}
