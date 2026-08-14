package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Singleton manager for persistent WebViews (YouTube, Spotify, Instagram).
 * Keeps background audio and video playing uninterrupted across tabs, screens,
 * desktop multi-window mode, and floating Picture-in-Picture overlays.
 */
object PersistentWebMediaManager {

    private val scope = CoroutineScope(Dispatchers.Main)

    // Persistent WebView references
    private var _youtubeWebView: WebView? = null
    val youtubeWebView: WebView? get() = _youtubeWebView

    private var _spotifyWebView: WebView? = null
    val spotifyWebView: WebView? get() = _spotifyWebView

    private var _instagramWebView: WebView? = null
    val instagramWebView: WebView? get() = _instagramWebView

    // YouTube State
    private val _isYoutubePlaying = MutableStateFlow(false)
    val isYoutubePlaying: StateFlow<Boolean> = _isYoutubePlaying.asStateFlow()

    private val _isYoutubeVideoActive = MutableStateFlow(false)
    val isYoutubeVideoActive: StateFlow<Boolean> = _isYoutubeVideoActive.asStateFlow()

    private val _youtubeTitle = MutableStateFlow("YouTube Video")
    val youtubeTitle: StateFlow<String> = _youtubeTitle.asStateFlow()

    private val _youtubeDurationSeconds = MutableStateFlow(0L)
    val youtubeDurationSeconds: StateFlow<Long> = _youtubeDurationSeconds.asStateFlow()

    private val _youtubeCurrentTimeSeconds = MutableStateFlow(0L)
    val youtubeCurrentTimeSeconds: StateFlow<Long> = _youtubeCurrentTimeSeconds.asStateFlow()

    private val _youtubeCurrentVideoUrl = MutableStateFlow("")
    val youtubeCurrentVideoUrl: StateFlow<String> = _youtubeCurrentVideoUrl.asStateFlow()

    private val _isYoutubePipActive = MutableStateFlow(false)
    val isYoutubePipActive: StateFlow<Boolean> = _isYoutubePipActive.asStateFlow()

    private val _youtubePipSize = MutableStateFlow("medium") // "small", "medium", "large"
    val youtubePipSize: StateFlow<String> = _youtubePipSize.asStateFlow()

    private val _isYoutubeMuted = MutableStateFlow(false)
    val isYoutubeMuted: StateFlow<Boolean> = _isYoutubeMuted.asStateFlow()

    // Spotify State
    private val _isSpotifyPlaying = MutableStateFlow(false)
    val isSpotifyPlaying: StateFlow<Boolean> = _isSpotifyPlaying.asStateFlow()

    private val _spotifyTrackTitle = MutableStateFlow("No track playing")
    val spotifyTrackTitle: StateFlow<String> = _spotifyTrackTitle.asStateFlow()

    private val _spotifyArtist = MutableStateFlow("Spotify Web")
    val spotifyArtist: StateFlow<String> = _spotifyArtist.asStateFlow()

    private val _spotifyCoverUrl = MutableStateFlow("")
    val spotifyCoverUrl: StateFlow<String> = _spotifyCoverUrl.asStateFlow()

    private val _isSpotifyFloatingBarVisible = MutableStateFlow(false)
    val isSpotifyFloatingBarVisible: StateFlow<Boolean> = _isSpotifyFloatingBarVisible.asStateFlow()

    /**
     * Safely detaches a view from its parent ViewGroup to prevent "child already has a parent" errors.
     */
    fun detachFromParent(view: View?) {
        try {
            (view?.parent as? ViewGroup)?.removeView(view)
        } catch (_: Exception) {}
    }

    /**
     * Retrieves or creates the single shared YouTube WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateYouTubeWebView(
        context: Context,
        shortsBlocked: Boolean = false,
        allowSubscribedShorts: Boolean = false,
        blockHomeFeed: Boolean = false,
        searchBlocked: Boolean = false,
        commentsBlocked: Boolean = false,
        isAdBlockEnabled: Boolean = true,
        currentPlaybackSpeed: Float = 1.0f,
        onPageStartedCallback: ((String?) -> Unit)? = null,
        onPageFinishedCallback: ((String?) -> Unit)? = null
    ): WebView {
        val existing = _youtubeWebView
        if (existing != null) {
            detachFromParent(existing)
            return existing
        }

        val startUrl = if (blockHomeFeed) "https://www.youtube.com/feed/subscriptions" else "https://www.youtube.com/"

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // JavaScript bridge to receive live playback status, duration, and video metadata from YouTube DOM
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun updatePlaybackState(playing: Boolean, isVideoActive: Boolean, title: String, durationSec: Double, currentTimeSec: Double, videoUrl: String) {
                        scope.launch {
                            _isYoutubePlaying.value = playing
                            val isWatchUrl = videoUrl.contains("/watch") || videoUrl.contains("/shorts/") || videoUrl.contains("youtu.be/")
                            val isFeedOrHome = (videoUrl.endsWith("youtube.com/") || videoUrl.endsWith("youtube.com") || videoUrl.contains("/feed/") || videoUrl.contains("/results")) && !isWatchUrl
                            _isYoutubeVideoActive.value = (isVideoActive || playing || isWatchUrl) && !isFeedOrHome
                            if (title.isNotBlank()) {
                                _youtubeTitle.value = title.replace("- YouTube", "").trim()
                            }
                            if (durationSec > 0) {
                                _youtubeDurationSeconds.value = durationSec.toLong()
                            }
                            if (currentTimeSec >= 0) {
                                _youtubeCurrentTimeSeconds.value = currentTimeSec.toLong()
                            }
                            if (videoUrl.isNotBlank()) {
                                _youtubeCurrentVideoUrl.value = videoUrl
                            }
                        }
                    }

                    @JavascriptInterface
                    fun updateVideoDuration(sec: Double) {
                        if (sec > 0) {
                            scope.launch {
                                _youtubeDurationSeconds.value = sec.toLong()
                            }
                        }
                    }
                },
                "LifeOsYouTubeBridge"
            )

            val antiTubeJs = buildAntiTubeScript(
                shortsBlocked = shortsBlocked,
                allowSubscribedShorts = allowSubscribedShorts,
                blockHomeFeed = blockHomeFeed,
                searchBlocked = searchBlocked,
                commentsBlocked = commentsBlocked,
                isAdBlockEnabled = isAdBlockEnabled,
                currentPlaybackSpeed = currentPlaybackSpeed
            )

            webViewClient = object : WebViewClient() {
                private fun isAuthUrl(url: String?): Boolean {
                    if (url == null) return false
                    val lower = url.lowercase()
                    return lower.contains("accounts.google") || lower.contains("accounts.youtube") || lower.contains("servicelogin") || lower.contains("signin") || lower.contains("myaccount.google")
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val currentUrl = url ?: ""
                    val isWatchUrl = currentUrl.contains("/watch") || currentUrl.contains("/shorts/") || currentUrl.contains("youtu.be/")
                    val isFeedOrHome = (currentUrl.endsWith("youtube.com/") || currentUrl.endsWith("youtube.com") || currentUrl.contains("/feed/") || currentUrl.contains("/results")) && !isWatchUrl
                    if (isWatchUrl) {
                        _youtubeCurrentVideoUrl.value = currentUrl
                        _isYoutubeVideoActive.value = true
                    } else if (isFeedOrHome) {
                        _isYoutubeVideoActive.value = false
                    }
                    onPageStartedCallback?.invoke(url)
                    if (!isAuthUrl(url)) {
                        view?.evaluateJavascript(antiTubeJs, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val currentUrl = url ?: ""
                    val isWatchUrl = currentUrl.contains("/watch") || currentUrl.contains("/shorts/") || currentUrl.contains("youtu.be/")
                    val isFeedOrHome = (currentUrl.endsWith("youtube.com/") || currentUrl.endsWith("youtube.com") || currentUrl.contains("/feed/") || currentUrl.contains("/results")) && !isWatchUrl
                    if (isWatchUrl) {
                        _youtubeCurrentVideoUrl.value = currentUrl
                        _isYoutubeVideoActive.value = true
                    } else if (isFeedOrHome) {
                        _isYoutubeVideoActive.value = false
                    }
                    onPageFinishedCallback?.invoke(url)
                    if (!isAuthUrl(url)) {
                        view?.evaluateJavascript(antiTubeJs, null)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val currentUrl = url ?: ""
                    val isWatchUrl = currentUrl.contains("/watch") || currentUrl.contains("/shorts/") || currentUrl.contains("youtu.be/")
                    val isFeedOrHome = (currentUrl.endsWith("youtube.com/") || currentUrl.endsWith("youtube.com") || currentUrl.contains("/feed/") || currentUrl.contains("/results")) && !isWatchUrl
                    if (isWatchUrl) {
                        _youtubeCurrentVideoUrl.value = currentUrl
                        _isYoutubeVideoActive.value = true
                    } else if (isFeedOrHome) {
                        _isYoutubeVideoActive.value = false
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (isAuthUrl(reqUrl) || reqUrl.contains("gstatic.com") || reqUrl.contains("google.com/recaptcha")) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (isAdBlockEnabled) {
                        val url = reqUrl.lowercase()
                        if (url.contains("googleads") ||
                            url.contains("doubleclick") ||
                            url.contains("googlesyndication") ||
                            url.contains("googleadservices") ||
                            url.contains("youtube.com/pagead/") ||
                            url.contains("youtube.com/api/stats/ads") ||
                            url.contains("youtube.com/ptracking") ||
                            url.contains("youtube.com/get_midroll_info") ||
                            url.contains("adservice.google") ||
                            url.contains("/adunit") ||
                            url.contains("/ad_status")
                        ) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val targetUrl = request?.url?.toString() ?: ""
                    if (isAuthUrl(targetUrl)) {
                        return false
                    }
                    if (blockHomeFeed && (targetUrl == "https://m.youtube.com/" || targetUrl == "https://www.youtube.com/")) {
                        view?.loadUrl(startUrl)
                        return true
                    }
                    if (shortsBlocked && !allowSubscribedShorts && targetUrl.contains("/shorts")) {
                        view?.loadUrl(startUrl)
                        return true
                    }
                    return false
                }
            }

            loadUrl(startUrl)
        }

        _youtubeWebView = webView
        return webView
    }

    /**
     * Builds custom JavaScript injected into YouTube for background audio/video playback,
     * visibility spoofing, duration & playback tracking, and ad-skipping.
     */
    fun buildAntiTubeScript(
        shortsBlocked: Boolean,
        allowSubscribedShorts: Boolean,
        blockHomeFeed: Boolean,
        searchBlocked: Boolean,
        commentsBlocked: Boolean,
        isAdBlockEnabled: Boolean,
        currentPlaybackSpeed: Float
    ): String {
        return """
        (function() {
            try {
                var h = (window.location && window.location.hostname) ? window.location.hostname : '';
                var u = (window.location && window.location.href) ? window.location.href : '';
                if (h.indexOf('accounts.google') !== -1 || h.indexOf('accounts.youtube') !== -1 || h.indexOf('myaccount.google') !== -1 || h.indexOf('ssl.gstatic') !== -1 || u.indexOf('ServiceLogin') !== -1 || u.indexOf('signin') !== -1) {
                    return;
                }
            } catch(e) {}

            window.__targetPlaybackSpeed = ${currentPlaybackSpeed};
            window.__adBlockEnabled = ${isAdBlockEnabled};
            window.__allowBgPlay = true;

            try {
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            } catch(e) {}

            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function(evt) {
                window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });

            // Monitor active video playback state and duration, report to Android bridge
            if (!window.__lifeOsStateMonitor) {
                window.__lifeOsStateMonitor = setInterval(function() {
                    var v = document.querySelector('video');
                    var title = document.title || '';
                    var href = window.location.href || '';
                    var isWatchOrShorts = href.indexOf('/watch') !== -1 || href.indexOf('/shorts/') !== -1 || href.indexOf('youtu.be/') !== -1;
                    var isFeedOrHome = (href === 'https://www.youtube.com/' || href === 'https://m.youtube.com/' || href.indexOf('/feed/') !== -1 || href.indexOf('/results') !== -1) && !isWatchOrShorts;
                    var duration = 0;
                    var currentTime = 0;
                    var isPlaying = false;
                    var isVideoActive = false;

                    if (v) {
                        isPlaying = !v.paused && !v.ended && v.readyState > 2;
                        var isPaused = v.paused && !v.ended;
                        if (!isNaN(v.duration) && v.duration > 0 && isFinite(v.duration)) {
                            duration = v.duration;
                        }
                        if (!isNaN(v.currentTime) && v.currentTime >= 0) {
                            currentTime = v.currentTime;
                        }
                        if (isWatchOrShorts) {
                            isVideoActive = true;
                        } else if (!isFeedOrHome && (isPlaying || (isPaused && currentTime > 0))) {
                            isVideoActive = true;
                        } else {
                            isVideoActive = false;
                        }
                    } else {
                        isVideoActive = isWatchOrShorts;
                    }

                    if (duration === 0) {
                        var durElem = document.querySelector('.ytp-time-duration, span.ytp-time-duration, .ytp-clip-duration');
                        if (durElem && durElem.innerText) {
                            var parts = durElem.innerText.trim().split(':').map(Number);
                            if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
                                duration = parts[0] * 60 + parts[1];
                            } else if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
                                duration = parts[0] * 3600 + parts[1] * 60 + parts[2];
                            }
                        }
                    }
                    if (window.LifeOsYouTubeBridge && window.LifeOsYouTubeBridge.updatePlaybackState) {
                        window.LifeOsYouTubeBridge.updatePlaybackState(isPlaying, isVideoActive, title, duration, currentTime, href);
                    }
                }, 1000);
            }

            // Ad blocking styling & injection
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

            // Auto click skip ad button
            setInterval(function() {
                var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot button');
                if (skipBtn) {
                    try { skipBtn.click(); } catch(e) {}
                }
            }, 600);
        })();
        """.trimIndent()
    }

    /**
     * Actively queries the WebView DOM for duration in seconds.
     */
    fun queryActiveVideoDuration(callback: (Long) -> Unit) {
        val wv = _youtubeWebView
        if (wv == null) {
            callback(_youtubeDurationSeconds.value)
            return
        }
        wv.evaluateJavascript(
            """
            (function() {
                var v = document.querySelector('video');
                if (v && !isNaN(v.duration) && v.duration > 0 && isFinite(v.duration)) {
                    return Math.round(v.duration).toString();
                }
                var durElem = document.querySelector('.ytp-time-duration, span.ytp-time-duration');
                if (durElem && durElem.innerText) {
                    var parts = durElem.innerText.trim().split(':').map(Number);
                    if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
                        return (parts[0] * 60 + parts[1]).toString();
                    } else if (parts.length === 3 && !isNaN(parts[0]) && !isNaN(parts[1]) && !isNaN(parts[2])) {
                        return (parts[0] * 3600 + parts[1] * 60 + parts[2]).toString();
                    }
                }
                return "0";
            })()
            """.trimIndent()
        ) { result ->
            val clean = result?.replace("\"", "")?.trim() ?: "0"
            val sec = clean.toLongOrNull() ?: 0L
            if (sec > 0) {
                _youtubeDurationSeconds.value = sec
            }
            callback(if (sec > 0) sec else _youtubeDurationSeconds.value)
        }
    }

    /**
     * Retrieves or creates the single shared Spotify WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateSpotifyWebView(
        context: Context,
        onPageStartedCallback: ((String?) -> Unit)? = null,
        onPageFinishedCallback: ((String?) -> Unit)? = null
    ): WebView {
        val existing = _spotifyWebView
        if (existing != null) {
            detachFromParent(existing)
            return existing
        }

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Bridge to extract live Spotify track info
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun updateTrackInfo(title: String, artist: String, coverUrl: String, playing: Boolean, currentTime: Int, duration: Int) {
                        scope.launch {
                            _spotifyTrackTitle.value = title
                            _spotifyArtist.value = artist
                            _spotifyCoverUrl.value = coverUrl
                            _isSpotifyPlaying.value = playing
                            if (playing && title != "No track playing") {
                                _isSpotifyFloatingBarVisible.value = true
                            }
                        }
                    }
                },
                "SpotifyTrackBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onPageStartedCallback?.invoke(url)
                    injectSpotifyHelperJs(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onPageFinishedCallback?.invoke(url)
                    injectSpotifyHelperJs(view)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString()?.lowercase() ?: ""
                    if (reqUrl.contains("audio-fa.scdn.co") || reqUrl.contains("audio-ak.spotify.com") || reqUrl.contains("audio4-ak.spotify.com")) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (reqUrl.contains("googleads") || reqUrl.contains("doubleclick") || reqUrl.contains("googlesyndication") || reqUrl.contains("adservice.google")) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            loadUrl("https://open.spotify.com")
        }

        _spotifyWebView = webView
        return webView
    }

    private fun injectSpotifyHelperJs(view: WebView?) {
        val js = """
        (function() {
            try {
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            } catch(e) {}

            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'].forEach(function(evt) {
                window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });

            if (!window.__spotifyObserverAttached) {
                window.__spotifyObserverAttached = true;
                setInterval(function() {
                    try {
                        var titleElem = document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-title"] a') ||
                                        document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-title"]') ||
                                        document.querySelector('a[data-testid="now-playing-track-link"]');
                        var artistElem = document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-artist"] a') ||
                                         document.querySelector('[data-testid="now-playing-widget"] [data-testid="context-item-info-artist"]');
                        var imgElem = document.querySelector('[data-testid="now-playing-widget"] img') ||
                                      document.querySelector('[data-testid="cover-art-image"]');
                        var playBtn = document.querySelector('[data-testid="control-button-playpause"]');

                        var title = titleElem ? titleElem.innerText.trim() : "No track playing";
                        var artist = artistElem ? artistElem.innerText.trim() : "Spotify Web";
                        var coverUrl = imgElem ? imgElem.src : "";
                        var isPlaying = playBtn ? (playBtn.getAttribute('aria-label') === 'Pause' || playBtn.getAttribute('data-testid') === 'control-button-pause') : false;

                        if (window.SpotifyTrackBridge && window.SpotifyTrackBridge.updateTrackInfo) {
                            window.SpotifyTrackBridge.updateTrackInfo(title, artist, coverUrl, isPlaying, 0, 0);
                        }
                    } catch(err) {}
                }, 1000);
            }
        })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // --- Control Actions ---

    fun toggleYoutubePlayPause() {
        _youtubeWebView?.evaluateJavascript(
            "var v = document.querySelector('video'); if (v) { if (v.paused) v.play(); else v.pause(); }",
            null
        )
    }

    fun seekYoutube(secondsOffset: Int) {
        _youtubeWebView?.evaluateJavascript(
            "var v = document.querySelector('video'); if (v) v.currentTime = Math.max(0, Math.min(v.duration || 999999, v.currentTime + ($secondsOffset)));",
            null
        )
    }

    fun toggleYoutubeMute() {
        val nextMuted = !_isYoutubeMuted.value
        _isYoutubeMuted.value = nextMuted
        _youtubeWebView?.evaluateJavascript(
            "var v = document.querySelector('video'); if (v) v.muted = $nextMuted;",
            null
        )
    }

    fun setYoutubePlaybackSpeed(speed: Float) {
        _youtubeWebView?.evaluateJavascript(
            "var v = document.querySelector('video'); if (v) v.playbackRate = $speed;",
            null
        )
    }

    fun toggleSpotifyPlayPause() {
        _spotifyWebView?.evaluateJavascript(
            "var btn = document.querySelector('[data-testid=\"control-button-playpause\"]'); if (btn) btn.click();",
            null
        )
    }

    fun setYoutubePipActive(active: Boolean) {
        _isYoutubePipActive.value = active
    }

    fun setYoutubePipSize(size: String) {
        _youtubePipSize.value = size
    }

    fun setSpotifyFloatingBarVisible(visible: Boolean) {
        _isSpotifyFloatingBarVisible.value = visible
    }

    fun closeYoutube() {
        _isYoutubePipActive.value = false
        _youtubeWebView?.evaluateJavascript("var v = document.querySelector('video'); if (v) v.pause();", null)
    }

    fun closeSpotify() {
        _isSpotifyFloatingBarVisible.value = false
        _spotifyWebView?.evaluateJavascript("var btn = document.querySelector('[data-testid=\"control-button-playpause\"][aria-label=\"Pause\"]'); if (btn) btn.click();", null)
    }
}
