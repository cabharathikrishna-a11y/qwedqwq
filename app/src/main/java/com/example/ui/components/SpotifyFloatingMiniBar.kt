package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppViewModel
import com.example.ui.Screen
import com.example.util.PersistentWebMediaManager
import kotlin.math.roundToInt

/**
 * Floating Spotify Background Audio Mini Player Bar.
 * Keeps user informed of track info and lets them control Spotify audio while multitasking.
 */
@Composable
fun SpotifyFloatingMiniBar(
    viewModel: AppViewModel,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val screenWidthPx = config.screenWidthDp * config.densityDpi / 160f
    val screenHeightPx = config.screenHeightDp * config.densityDpi / 160f

    val isVisible by PersistentWebMediaManager.isSpotifyFloatingBarVisible.collectAsState()
    val isPlaying by PersistentWebMediaManager.isSpotifyPlaying.collectAsState()
    val trackTitle by PersistentWebMediaManager.spotifyTrackTitle.collectAsState()
    val artistName by PersistentWebMediaManager.spotifyArtist.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()

    // Don't show floating bar if user is currently inside Spotify Web screen
    if (!isVisible || currentScreen == Screen.SPOTIFY_WEB_APP || trackTitle == "No track playing") {
        return
    }

    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx - 260f) }

    // Pulsing equalizer animation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt().coerceAtLeast(16), offsetY.roundToInt().coerceAtLeast(32)) }
                .widthIn(min = 240.dp, max = 340.dp)
                .shadow(12.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF1DB954).copy(alpha = 0.8f), Color(0xFF191414))
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
                .testTag("spotify_floating_mini_bar"),
            color = Color(0xFF181818).copy(alpha = 0.95f),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Spotify Logo + Equalizer + Track Info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onExpand() }
                ) {
                    // Spotify Icon with pulsating glow
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1DB954)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Spotify",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Equalizer Bars
                    if (isPlaying) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.height(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(bar1Height.dp)
                                    .background(Color(0xFF1DB954), RoundedCornerShape(1.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(bar2Height.dp)
                                    .background(Color(0xFF1DB954), RoundedCornerShape(1.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(bar3Height.dp)
                                    .background(Color(0xFF1DB954), RoundedCornerShape(1.dp))
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trackTitle,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artistName,
                            color = Color(0xFF1DB954),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Controls: Play/Pause, Expand, Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { PersistentWebMediaManager.toggleSpotifyPlayPause() },
                        modifier = Modifier.size(28.dp).testTag("spotify_mini_playpause_btn")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { onExpand() },
                        modifier = Modifier.size(26.dp).testTag("spotify_mini_expand_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand",
                            tint = Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    IconButton(
                        onClick = { PersistentWebMediaManager.closeSpotify() },
                        modifier = Modifier.size(24.dp).testTag("spotify_mini_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.Gray,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
