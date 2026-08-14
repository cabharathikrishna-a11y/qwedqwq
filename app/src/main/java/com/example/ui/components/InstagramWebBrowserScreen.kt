package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
        """
        (function() {
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

            var oldStyle = document.getElementById('antigram-styles');
            if (oldStyle) oldStyle.remove();

            var style = document.createElement('style');
            style.id = 'antigram-styles';
            var css = `
                video {
                    max-height: 100vh !important;
                    object-fit: contain !important;
                }
                div[data-testid="app-install-banner"],
                a[href*="app.adjust.com"],
                a[href*="threads.net"],
                a[href*="threads.com"],
                a[href*="threads"],
                a[aria-label*="Threads"],
                svg[aria-label*="Threads"],
                a[href*="meta_ai"],
                a[href*="/ai/"],
                a[aria-label*="Meta AI"],
                svg[aria-label*="Meta AI"],
                a[aria-label*="Imagine"] {
                    display: none !important;
                }
            `;

            ${if (reelsBlocked && storiesBlocked) """
                css += `
                    section > main[role="main"] > div:not([aria-label*="Direct"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (reelsBlocked) """
                css += `
                    a[href*="/reels"],
                    a[href*="/reel"],
                    a[aria-label*="Reels"],
                    a[aria-label*="reels"],
                    svg[aria-label*="Reels"],
                    svg[aria-label*="reels"],
                    div[aria-label*="Reels"],
                    div[role="tab"]:has(a[href*="/reels"]),
                    div[role="tab"]:has(a[href*="/reel"]),
                    div[role="listitem"]:has(a[href*="/reels"]),
                    div[role="listitem"]:has(a[href*="/reel"]) {
                        display: none !important;
                    }

                    body:not([data-antigram-chat="true"]) article:has(a[href*="/reel/"]),
                    body:not([data-antigram-chat="true"]) article:has(a[href*="/reels/"]),
                    body:not([data-antigram-chat="true"]) article:has(video),
                    body:not([data-antigram-chat="true"]) article:has(svg[aria-label*="Reel"]),
                    body:not([data-antigram-chat="true"]) article:has(svg[aria-label*="Clip"]),
                    body:not([data-antigram-chat="true"]) section:has(a[href*="/reel/"]),
                    body:not([data-antigram-chat="true"]) section:has(a[href*="/reels/"]),
                    body:not([data-antigram-chat="true"]) div[role="presentation"]:has(a[href*="/reel/"]),
                    body:not([data-antigram-chat="true"]) div[role="listitem"]:has(a[href*="/reel/"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (storiesBlocked) """
                css += `
                    a[href*="/stories"],
                    div[aria-label*="Stories"],
                    div[role="menu"]:has(a[href*="/stories"]),
                    ul:has(a[href*="/stories"]),
                    div:has(> a[href*="/stories"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (messagesBlocked) """
                css += `
                    a[href*="/direct"],
                    a[aria-label*="Direct"],
                    a[aria-label*="Messenger"],
                    svg[aria-label*="Direct"],
                    svg[aria-label*="Messenger"],
                    div[role="tab"]:has(a[href*="/direct"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (exploreBlocked) """
                css += `
                    a[href*="/explore"],
                    a[aria-label*="Explore"],
                    a[aria-label*="Search"],
                    svg[aria-label*="Explore"],
                    svg[aria-label*="Search"],
                    div[role="tab"]:has(a[href*="/explore"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            ${if (notificationsBlocked) """
                css += `
                    a[href*="/accounts/activity"],
                    a[href*="/activity"],
                    a[aria-label*="Notifications"],
                    a[aria-label*="Activity"],
                    svg[aria-label*="Notifications"],
                    svg[aria-label*="Activity Feed"],
                    svg[aria-label*="Like"],
                    div[role="tab"]:has(a[href*="/activity"]) {
                        display: none !important;
                    }
                `;
            """ else ""}

            style.textContent = css;
            (document.head || document.documentElement).appendChild(style);

            if (!window.__antigramClickIntercepted) {
                window.__antigramClickIntercepted = true;
                document.addEventListener('click', function(e) {
                    if (!${reelsBlocked}) return;
                    var p = window.location.pathname;
                    if (!p.startsWith('/direct')) {
                        var target = e.target;
                        var anchor = target ? target.closest('a') : null;
                        if (anchor) {
                            var href = anchor.getAttribute('href') || '';
                            if (href.indexOf('/reel/') !== -1 || href.indexOf('/reels') !== -1) {
                                e.preventDefault();
                                e.stopPropagation();
                                return false;
                            }
                        }
                    }
                }, true);
            }

            function blockFilteredAndClean() {
                var path = window.location.pathname;

                /* Track if user navigation is originating from Direct Chat */
                if (path.startsWith('/direct')) {
                    if (document.body) document.body.setAttribute('data-antigram-chat', 'true');
                    window.__antigramLastNavWasChat = true;
                    try { sessionStorage.setItem('antigram_last_nav_was_chat', 'true'); } catch(e){}
                } else if (!path.startsWith('/reel/') && !path.startsWith('/reels')) {
                    if (document.body) document.body.setAttribute('data-antigram-chat', 'false');
                    window.__antigramLastNavWasChat = false;
                    try { sessionStorage.setItem('antigram_last_nav_was_chat', 'false'); } catch(e){}
                }

                /* Redirect Home to Direct Inbox if both Reels & Stories are blocked */
                if (${reelsBlocked && storiesBlocked} && (path === '/' || path === '' || path === '/index.html')) {
                    window.location.href = 'https://www.instagram.com/direct/inbox/';
                    return;
                }

                /* Block Reels strictly when reelsBlocked is true, EXCEPT if opened from inside Direct Chat */
                if (${reelsBlocked} && (path.startsWith('/reels') || path.startsWith('/reel'))) {
                    var wasFromChat = false;
                    try {
                        if (window.__antigramLastNavWasChat === true) wasFromChat = true;
                        if (sessionStorage.getItem('antigram_last_nav_was_chat') === 'true') wasFromChat = true;
                        if (document.referrer && document.referrer.indexOf('/direct') !== -1) wasFromChat = true;
                    } catch(e) {}

                    var isSingleReelFromChat = ${allowSharedReels} && path.startsWith('/reel/') && path !== '/reels' && path !== '/reels/' && wasFromChat;
                    if (isSingleReelFromChat) {
                        if (!window.__allowedSingleReelPath) {
                            window.__allowedSingleReelPath = path;
                        } else if (window.__allowedSingleReelPath !== path) {
                            // Swiped or scrolled away to another reel!
                            window.location.href = 'https://www.instagram.com/direct/inbox/';
                            return;
                        }

                        // Lock scrolling on single shared reel page
                        document.body.style.overflow = 'hidden';
                        if (!window.__noScrollReelListener) {
                            window.__noScrollReelListener = true;
                            window.addEventListener('wheel', function(e) {
                                if (Math.abs(e.deltaY) > 8) {
                                    window.location.href = 'https://www.instagram.com/direct/inbox/';
                                }
                            }, { passive: true });
                            var touchStartY = 0;
                            window.addEventListener('touchstart', function(e) {
                                if (e.touches && e.touches.length > 0) {
                                    touchStartY = e.touches[0].clientY;
                                }
                            }, { passive: true });
                            window.addEventListener('touchmove', function(e) {
                                if (e.touches && e.touches.length > 0) {
                                    var diffY = touchStartY - e.touches[0].clientY;
                                    if (Math.abs(diffY) > 25) {
                                        window.location.href = 'https://www.instagram.com/direct/inbox/';
                                    }
                                }
                            }, { passive: true });
                        }
                    } else {
                        // Tried to open Reel from Home Feed or non-chat section -> Redirect away!
                        window.location.href = '${homeOrDmUrl}';
                        return;
                    }
                } else {
                    window.__allowedSingleReelPath = null;
                }

                if (${storiesBlocked} && path.startsWith('/stories')) {
                    window.location.href = '${homeOrDmUrl}';
                    return;
                }
                if (${messagesBlocked} && path.startsWith('/direct')) {
                    window.location.href = 'https://www.instagram.com/';
                    return;
                }
                if (${exploreBlocked} && path.startsWith('/explore')) {
                    window.location.href = '${homeOrDmUrl}';
                    return;
                }
                if (${notificationsBlocked} && (path.startsWith('/accounts/activity') || path.startsWith('/activity'))) {
                    window.location.href = '${homeOrDmUrl}';
                    return;
                }

                // Helper to hide matching elements and their navigation parent container
                function hideElementAndParent(selector) {
                    var elements = document.querySelectorAll(selector);
                    for (var k = 0; k < elements.length; k++) {
                        var item = elements[k];
                        var container = item.closest('div[role="tab"]') || item.closest('div[role="listitem"]') || item.closest('li') || item.closest('div[role="button"]') || item.closest('span[role="link"]') || item.parentElement;
                        if (container) {
                            container.style.display = 'none';
                        }
                        item.style.display = 'none';
                    }
                }

                // Always hide Threads and Meta AI
                hideElementAndParent('a[href*="threads"], a[href*="meta_ai"], a[aria-label*="Threads"], a[aria-label*="Meta AI"], svg[aria-label*="Threads"], svg[aria-label*="Meta AI"]');

                if (${reelsBlocked} && !path.startsWith('/direct')) {
                    hideElementAndParent('a[href*="/reels"], a[href*="/reel"], a[aria-label*="Reels"], a[aria-label*="reels"], svg[aria-label*="Reels"], svg[aria-label*="reels"], div[aria-label*="Reels"]');

                    // 1. Hide articles containing video reels or reel links on home feed
                    var articles = document.querySelectorAll('article');
                    for (var a = 0; a < articles.length; a++) {
                        var art = articles[a];
                        var hasVid = art.querySelector('video') !== null;
                        var hasReelLink = art.querySelector('a[href*="/reel/"], a[href*="/reels/"]') !== null;
                        var hasReelSvg = art.querySelector('svg[aria-label*="Reel"], svg[aria-label*="reels"], svg[aria-label*="Clip"]') !== null;
                        if (hasVid || hasReelLink || hasReelSvg) {
                            art.style.display = 'none';
                            var vids = art.querySelectorAll('video');
                            for (var v = 0; v < vids.length; v++) {
                                try { vids[v].pause(); } catch(e){}
                            }
                        }
                    }

                    // 2. Hide "Suggested Reels" / "Reels and short videos" trays and sections
                    var headings = document.querySelectorAll('span, h2, h3, div');
                    for (var h = 0; h < headings.length; h++) {
                        var txt = headings[h].innerText ? headings[h].innerText.trim().toLowerCase() : '';
                        if (txt === 'reels' || txt === 'suggested reels' || txt === 'reels and short videos' || txt === 'suggested reels for you') {
                            var reelSection = headings[h].closest('section') || 
                                              headings[h].closest('div[role="region"]') || 
                                              headings[h].closest('article') || 
                                              headings[h].parentElement?.parentElement?.parentElement;
                            if (reelSection && reelSection !== document.body && reelSection.tagName !== 'MAIN') {
                                reelSection.style.display = 'none';
                            }
                        }
                    }
                }

                if (${storiesBlocked}) {
                    hideElementAndParent('a[href*="/stories"]');
                    // Hide story tray horizontal bar
                    var storyTrays = document.querySelectorAll('div[role="menu"], ul:has(a[href*="/stories"]), div:has(> a[href*="/stories"])');
                    for (var st = 0; st < storyTrays.length; st++) {
                        storyTrays[st].style.display = 'none';
                    }
                }

                if (${messagesBlocked}) {
                    hideElementAndParent('a[href*="/direct"], a[aria-label*="Direct"], a[aria-label*="Messenger"], svg[aria-label*="Direct"], svg[aria-label*="Messenger"]');
                }

                if (${exploreBlocked}) {
                    hideElementAndParent('a[href*="/explore"], a[aria-label*="Explore"], a[aria-label*="Search"], svg[aria-label*="Explore"], svg[aria-label*="Search"]');
                }

                if (${notificationsBlocked}) {
                    hideElementAndParent('a[href*="/accounts/activity"], a[href*="/activity"], a[aria-label*="Notifications"], a[aria-label*="Activity"], svg[aria-label*="Notifications"], svg[aria-label*="Activity"]');
                }
            }

            blockFilteredAndClean();

            if (!window.__antigramInterval) {
                window.__antigramInterval = setInterval(function() {
                    blockFilteredAndClean();
                }, 150);
            }

            if (!window.__antigramPatched) {
                window.__antigramPatched = true;
                var origPushState = history.pushState;
                history.pushState = function() {
                    origPushState.apply(this, arguments);
                    blockFilteredAndClean();
                };
                var origReplaceState = history.replaceState;
                history.replaceState = function() {
                    origReplaceState.apply(this, arguments);
                    blockFilteredAndClean();
                };
                window.addEventListener('popstate', blockFilteredAndClean);
            }

            if (!window.__antigramObserver) {
                window.__antigramObserver = new MutationObserver(function() {
                    blockFilteredAndClean();
                });
                window.__antigramObserver.observe(document.body || document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true
                });
            }
        })();
        """.trimIndent()
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
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        setRenderPriority(WebSettings.RenderPriority.HIGH)
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                            if (url != null) {
                                if (reelsBlocked && storiesBlocked && (url == "https://www.instagram.com/" || url == "https://www.instagram.com")) {
                                    view?.loadUrl("https://www.instagram.com/direct/inbox/")
                                    return
                                }
                                if (reelsBlocked) {
                                    if (url.contains("/reels")) {
                                        view?.loadUrl(homeOrDmUrl)
                                        return
                                    }
                                    if (!allowSharedReels && url.contains("/reel/")) {
                                        view?.loadUrl(homeOrDmUrl)
                                        return
                                    }
                                }
                                if (storiesBlocked && url.contains("/stories")) {
                                    view?.loadUrl(homeOrDmUrl)
                                    return
                                }
                                if (messagesBlocked && url.contains("/direct")) {
                                    view?.loadUrl(homeOrDmUrl)
                                    return
                                }
                                if (exploreBlocked && url.contains("/explore")) {
                                    view?.loadUrl(homeOrDmUrl)
                                    return
                                }
                                if (notificationsBlocked && (url.contains("/accounts/activity") || url.contains("/activity"))) {
                                    view?.loadUrl(homeOrDmUrl)
                                    return
                                }
                            }
                            view?.evaluateJavascript(antiGramJs, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            view?.evaluateJavascript(antiGramJs, null)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            view?.evaluateJavascript(antiGramJs, null)
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            view?.evaluateJavascript(antiGramJs, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val targetUrl = request?.url?.toString() ?: ""
                            if (reelsBlocked && storiesBlocked && (targetUrl == "https://www.instagram.com/" || targetUrl == "https://www.instagram.com")) {
                                view?.loadUrl("https://www.instagram.com/direct/inbox/")
                                return true
                            }
                            if (reelsBlocked) {
                                if (targetUrl.contains("/reels")) {
                                    Toast.makeText(ctx, "AntiGram: Reels tab is blocked 🛡️", Toast.LENGTH_SHORT).show()
                                    view?.loadUrl(homeOrDmUrl)
                                    return true
                                }
                                if (!allowSharedReels && targetUrl.contains("/reel/")) {
                                    Toast.makeText(ctx, "AntiGram: Reels are blocked 🛡️", Toast.LENGTH_SHORT).show()
                                    view?.loadUrl(homeOrDmUrl)
                                    return true
                                }
                            }
                            if (storiesBlocked && targetUrl.contains("/stories")) {
                                Toast.makeText(ctx, "AntiGram: Stories are blocked 🛡️", Toast.LENGTH_SHORT).show()
                                view?.loadUrl(homeOrDmUrl)
                                return true
                            }
                            if (messagesBlocked && targetUrl.contains("/direct")) {
                                Toast.makeText(ctx, "AntiGram: Direct Messages are blocked 🛡️", Toast.LENGTH_SHORT).show()
                                view?.loadUrl(homeOrDmUrl)
                                return true
                            }
                            if (exploreBlocked && targetUrl.contains("/explore")) {
                                Toast.makeText(ctx, "AntiGram: Explore is blocked 🛡️", Toast.LENGTH_SHORT).show()
                                view?.loadUrl(homeOrDmUrl)
                                return true
                            }
                            if (notificationsBlocked && (targetUrl.contains("/accounts/activity") || targetUrl.contains("/activity"))) {
                                Toast.makeText(ctx, "AntiGram: Notifications are blocked 🛡️", Toast.LENGTH_SHORT).show()
                                view?.loadUrl(homeOrDmUrl)
                                return true
                            }
                            return false
                        }
                    }
                    webChromeClient = WebChromeClient()
                    loadUrl(homeOrDmUrl)
                    webViewInstance = this
                }
            },
            update = { webView ->
                webViewInstance = webView
                webView.evaluateJavascript(antiGramJs, null)
            },
            onRelease = { wv ->
                try {
                    wv.stopLoading()
                    wv.onPause()
                    wv.pauseTimers()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (_: Exception) {}
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

