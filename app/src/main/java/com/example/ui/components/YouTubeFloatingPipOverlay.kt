package com.example.ui.components

import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.AppViewModel
import com.example.ui.Screen
import com.example.util.PersistentWebMediaManager
import kotlin.math.roundToInt

/**
 * Floating Picture-in-Picture YouTube Video Player Overlay.
 * Allows users to watch YouTube videos in a draggable, floating box anywhere
 * while multitasking across other apps, tools, timers, and tasks.
 */
@Composable
fun YouTubeFloatingPipOverlay(
    viewModel: AppViewModel,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val screenWidthPx = config.screenWidthDp * config.densityDpi / 160f
    val screenHeightPx = config.screenHeightDp * config.densityDpi / 160f

    val isPipActive by PersistentWebMediaManager.isYoutubePipActive.collectAsState()
    val isPlaying by PersistentWebMediaManager.isYoutubePlaying.collectAsState()
    val videoTitle by PersistentWebMediaManager.youtubeTitle.collectAsState()
    val pipSize by PersistentWebMediaManager.youtubePipSize.collectAsState()
    val isMuted by PersistentWebMediaManager.isYoutubeMuted.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()

    // Don't display floating overlay if user is already on the full YouTube screen or if PiP is disabled
    if (!isPipActive || currentScreen == Screen.YOUTUBE_WEB_APP) {
        return
    }

    var offsetX by remember { mutableFloatStateOf(screenWidthPx - 650f) }
    var offsetY by remember { mutableFloatStateOf(180f) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(4000L)
            showControls = false
        }
    }

    val (cardWidthDp, cardHeightDp) = when (pipSize) {
        "small" -> Pair(200.dp, 120.dp)
        "large" -> Pair(330.dp, 195.dp)
        else -> Pair(265.dp, 155.dp) // medium
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt().coerceAtLeast(16), offsetY.roundToInt().coerceAtLeast(32)) }
                .width(cardWidthDp)
                .wrapContentHeight()
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF0000).copy(alpha = 0.85f),
                            Color(0xFF880000).copy(alpha = 0.5f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(Color(0xFF0F0F12))
                .testTag("youtube_floating_pip_box")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F12))
            ) {
                // Drag Handle & Top Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E24))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "YouTube PiP",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        Text(
                            text = videoTitle.ifBlank { "YouTube Mini Player" },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Window Action Buttons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Size cycle toggle
                        IconButton(
                            onClick = {
                                val next = when (pipSize) {
                                    "small" -> "medium"
                                    "medium" -> "large"
                                    else -> "small"
                                }
                                PersistentWebMediaManager.setYoutubePipSize(next)
                            },
                            modifier = Modifier.size(24.dp).testTag("youtube_pip_size_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Toggle Size",
                                tint = Color.LightGray,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        // Maximize / Return to full YouTube Screen
                        IconButton(
                            onClick = {
                                onExpand()
                            },
                            modifier = Modifier.size(24.dp).testTag("youtube_pip_expand_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = "Expand Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Close PiP
                        IconButton(
                            onClick = {
                                PersistentWebMediaManager.closeYoutube()
                            },
                            modifier = Modifier.size(24.dp).testTag("youtube_pip_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Player",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Video Display Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeightDp)
                        .background(Color.Black)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showControls = !showControls
                        }
                ) {
                    // Embed Persistent YouTube WebView
                    AndroidView<android.webkit.WebView>(
                        factory = { ctx ->
                            PersistentWebMediaManager.getOrCreateYouTubeWebView(ctx)
                        },
                        update = { view ->
                            PersistentWebMediaManager.detachFromParent(view)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Touch / Control Overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showControls,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            // Centered Playback Controls
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // -10s Rewind
                                IconButton(
                                    onClick = { PersistentWebMediaManager.seekYoutube(-10) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "Rewind 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Play / Pause Toggle
                                IconButton(
                                    onClick = { PersistentWebMediaManager.toggleYoutubePlayPause() },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF0000))
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // +10s Fast Forward
                                IconButton(
                                    onClick = { PersistentWebMediaManager.seekYoutube(10) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "Forward 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Bottom Mute & Tap Hint Bar
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                IconButton(
                                    onClick = { PersistentWebMediaManager.toggleYoutubeMute() },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        contentDescription = "Mute Toggle",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Text(
                                    "Tap to hide",
                                    color = Color.LightGray.copy(alpha = 0.7f),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
