package com.example.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
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
import com.example.ui.AppViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun MultiWindowDesktopOverlay(
    viewModel: AppViewModel,
    onExit: () -> Unit
) {
    val layoutMode by viewModel.multiWindowLayoutMode.collectAsState()

    val isSpotifyMinimized by viewModel.isSpotifyWindowMinimized.collectAsState()
    val isYouTubeMinimized by viewModel.isYouTubeWindowMinimized.collectAsState()
    val isInstagramMinimized by viewModel.isInstagramWindowMinimized.collectAsState()
    val isLifeOsMinimized by viewModel.isLifeOsWindowMinimized.collectAsState()

    val isSpotifyMaximized by viewModel.isSpotifyWindowMaximized.collectAsState()
    val isYouTubeMaximized by viewModel.isYouTubeWindowMaximized.collectAsState()
    val isInstagramMaximized by viewModel.isInstagramWindowMaximized.collectAsState()
    val isLifeOsMaximized by viewModel.isLifeOsWindowMaximized.collectAsState()

    val focusedWindowId by viewModel.focusedWindowId.collectAsState()

    // Floating window position offsets (for FLOATING freeform mode)
    var spotifyOffset by remember { mutableStateOf(IntOffset(20, 20)) }
    var youtubeOffset by remember { mutableStateOf(IntOffset(220, 40)) }
    var instagramOffset by remember { mutableStateOf(IntOffset(40, 260)) }
    var lifeOsOffset by remember { mutableStateOf(IntOffset(240, 280)) }

    // Life OS active tab inside window
    var lifeOsTab by remember { mutableStateOf("TIMER") } // "TIMER", "TASKS", "NOTES", "HABITS", "ANALYTICS"

    val hasAnyMaximized = isSpotifyMaximized || isYouTubeMaximized || isInstagramMaximized || isLifeOsMaximized

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090D))
            .testTag("multi_window_desktop_overlay")
    ) {
        // Main Desktop Screen Canvas
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 60.dp) // Leave space for bottom Desktop Dock
        ) {
            val totalWidth = maxWidth
            val totalHeight = maxHeight

            if (hasAnyMaximized) {
                // Single Maximized Window Layout
                val maxWindowId = when {
                    isSpotifyMaximized -> "SPOTIFY"
                    isYouTubeMaximized -> "YOUTUBE"
                    isInstagramMaximized -> "INSTAGRAM"
                    else -> "LIFE_OS"
                }

                DesktopWindowFrame(
                    title = getWindowTitle(maxWindowId),
                    iconColor = getWindowColor(maxWindowId),
                    windowId = maxWindowId,
                    isMinimized = false,
                    isMaximized = true,
                    isFocused = true,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                ) {
                    RenderWindowContent(maxWindowId, viewModel, lifeOsTab) { lifeOsTab = it }
                }
            } else if (layoutMode == "QUAD_GRID") {
                // 2x2 QUAD SPLIT GRID LAYOUT (All 4 Apps simultaneously)
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Top-Left: Spotify
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                        ) {
                            DesktopWindowFrame(
                                title = "Spotify Web",
                                iconColor = Color(0xFF1DB954),
                                windowId = "SPOTIFY",
                                isMinimized = isSpotifyMinimized,
                                isMaximized = false,
                                isFocused = focusedWindowId == "SPOTIFY",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("SPOTIFY", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }

                        // Top-Right: YouTube
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                        ) {
                            DesktopWindowFrame(
                                title = "YouTube Web",
                                iconColor = Color(0xFFFF0000),
                                windowId = "YOUTUBE",
                                isMinimized = isYouTubeMinimized,
                                isMaximized = false,
                                isFocused = focusedWindowId == "YOUTUBE",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("YOUTUBE", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Bottom-Left: Instagram
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                        ) {
                            DesktopWindowFrame(
                                title = "Instagram Web",
                                iconColor = Color(0xFFE1306C),
                                windowId = "INSTAGRAM",
                                isMinimized = isInstagramMinimized,
                                isMaximized = false,
                                isFocused = focusedWindowId == "INSTAGRAM",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("INSTAGRAM", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }

                        // Bottom-Right: Life OS
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                        ) {
                            DesktopWindowFrame(
                                title = "Life OS Hub",
                                iconColor = Color(0xFF3B82F6),
                                windowId = "LIFE_OS",
                                isMinimized = isLifeOsMinimized,
                                isMaximized = false,
                                isFocused = focusedWindowId == "LIFE_OS",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("LIFE_OS", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }
                }
            } else if (layoutMode == "SPLIT_DUAL") {
                // 2-APP SIDE BY SIDE SPLIT
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(2.dp)
                    ) {
                        DesktopWindowFrame(
                            title = "YouTube Web",
                            iconColor = Color(0xFFFF0000),
                            windowId = "YOUTUBE",
                            isMinimized = isYouTubeMinimized,
                            isMaximized = false,
                            isFocused = focusedWindowId == "YOUTUBE",
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            RenderWindowContent("YOUTUBE", viewModel, lifeOsTab) { lifeOsTab = it }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(2.dp)
                    ) {
                        DesktopWindowFrame(
                            title = "Spotify Web",
                            iconColor = Color(0xFF1DB954),
                            windowId = "SPOTIFY",
                            isMinimized = isSpotifyMinimized,
                            isMaximized = false,
                            isFocused = focusedWindowId == "SPOTIFY",
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            RenderWindowContent("SPOTIFY", viewModel, lifeOsTab) { lifeOsTab = it }
                        }
                    }
                }
            } else {
                // FREEFORM FLOATING WINDOWS MODE
                Box(modifier = Modifier.fillMaxSize()) {
                    // Spotify Floating Window
                    if (!isSpotifyMinimized) {
                        Box(
                            modifier = Modifier
                                .offset { spotifyOffset }
                                .size(width = (totalWidth * 0.48f), height = (totalHeight * 0.48f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        spotifyOffset = IntOffset(
                                            (spotifyOffset.x + dragAmount.x).roundToInt(),
                                            (spotifyOffset.y + dragAmount.y).roundToInt()
                                        )
                                        viewModel.setFocusedWindow("SPOTIFY")
                                    }
                                }
                        ) {
                            DesktopWindowFrame(
                                title = "Spotify Web",
                                iconColor = Color(0xFF1DB954),
                                windowId = "SPOTIFY",
                                isMinimized = false,
                                isMaximized = false,
                                isFocused = focusedWindowId == "SPOTIFY",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("SPOTIFY", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }

                    // YouTube Floating Window
                    if (!isYouTubeMinimized) {
                        Box(
                            modifier = Modifier
                                .offset { youtubeOffset }
                                .size(width = (totalWidth * 0.48f), height = (totalHeight * 0.48f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        youtubeOffset = IntOffset(
                                            (youtubeOffset.x + dragAmount.x).roundToInt(),
                                            (youtubeOffset.y + dragAmount.y).roundToInt()
                                        )
                                        viewModel.setFocusedWindow("YOUTUBE")
                                    }
                                }
                        ) {
                            DesktopWindowFrame(
                                title = "YouTube Web",
                                iconColor = Color(0xFFFF0000),
                                windowId = "YOUTUBE",
                                isMinimized = false,
                                isMaximized = false,
                                isFocused = focusedWindowId == "YOUTUBE",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("YOUTUBE", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }

                    // Instagram Floating Window
                    if (!isInstagramMinimized) {
                        Box(
                            modifier = Modifier
                                .offset { instagramOffset }
                                .size(width = (totalWidth * 0.48f), height = (totalHeight * 0.48f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        instagramOffset = IntOffset(
                                            (instagramOffset.x + dragAmount.x).roundToInt(),
                                            (instagramOffset.y + dragAmount.y).roundToInt()
                                        )
                                        viewModel.setFocusedWindow("INSTAGRAM")
                                    }
                                }
                        ) {
                            DesktopWindowFrame(
                                title = "Instagram Web",
                                iconColor = Color(0xFFE1306C),
                                windowId = "INSTAGRAM",
                                isMinimized = false,
                                isMaximized = false,
                                isFocused = focusedWindowId == "INSTAGRAM",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("INSTAGRAM", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }

                    // Life OS Floating Window
                    if (!isLifeOsMinimized) {
                        Box(
                            modifier = Modifier
                                .offset { lifeOsOffset }
                                .size(width = (totalWidth * 0.48f), height = (totalHeight * 0.48f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        lifeOsOffset = IntOffset(
                                            (lifeOsOffset.x + dragAmount.x).roundToInt(),
                                            (lifeOsOffset.y + dragAmount.y).roundToInt()
                                        )
                                        viewModel.setFocusedWindow("LIFE_OS")
                                    }
                                }
                        ) {
                            DesktopWindowFrame(
                                title = "Life OS Hub",
                                iconColor = Color(0xFF3B82F6),
                                windowId = "LIFE_OS",
                                isMinimized = false,
                                isMaximized = false,
                                isFocused = focusedWindowId == "LIFE_OS",
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                RenderWindowContent("LIFE_OS", viewModel, lifeOsTab) { lifeOsTab = it }
                            }
                        }
                    }
                }
            }

            // Off-screen / Minimized Background Playback Engine
            // Ensures WebViews remain initialized and continuously playing audio/video even when minimized!
            if (layoutMode != "QUAD_GRID") {
                if (isSpotifyMinimized) {
                    Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                        com.example.ui.components.SpotifyWebBrowserScreen(viewModel = viewModel, onBack = {})
                    }
                }
                if (isYouTubeMinimized) {
                    Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                        com.example.ui.components.YouTubeWebBrowserScreen(viewModel = viewModel, onBack = {})
                    }
                }
                if (isInstagramMinimized) {
                    Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                        com.example.ui.components.InstagramWebBrowserScreen(viewModel = viewModel, onBack = {})
                    }
                }
            }
        }

        // FLOATING DESKTOP TASKBAR / DOCK AT BOTTOM
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(58.dp),
            color = Color(0xF2121216),
            tonalElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // App Quick-Launch Dock Icons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spotify Dock Button
                    DockAppButton(
                        label = "Spotify",
                        color = Color(0xFF1DB954),
                        isMinimized = isSpotifyMinimized,
                        isActive = true,
                        onClick = {
                            viewModel.toggleWindowMinimized("SPOTIFY")
                            viewModel.setFocusedWindow("SPOTIFY")
                        }
                    )

                    // YouTube Dock Button
                    DockAppButton(
                        label = "YouTube",
                        color = Color(0xFFFF0000),
                        isMinimized = isYouTubeMinimized,
                        isActive = true,
                        onClick = {
                            viewModel.toggleWindowMinimized("YOUTUBE")
                            viewModel.setFocusedWindow("YOUTUBE")
                        }
                    )

                    // Instagram Dock Button
                    DockAppButton(
                        label = "Instagram",
                        color = Color(0xFFE1306C),
                        isMinimized = isInstagramMinimized,
                        isActive = true,
                        onClick = {
                            viewModel.toggleWindowMinimized("INSTAGRAM")
                            viewModel.setFocusedWindow("INSTAGRAM")
                        }
                    )

                    // Life OS Dock Button
                    DockAppButton(
                        label = "Life OS",
                        color = Color(0xFF3B82F6),
                        isMinimized = isLifeOsMinimized,
                        isActive = true,
                        onClick = {
                            viewModel.toggleWindowMinimized("LIFE_OS")
                            viewModel.setFocusedWindow("LIFE_OS")
                        }
                    )
                }

                // Layout Mode Switchers & Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quad 2x2 Grid Layout
                    IconButton(
                        onClick = { viewModel.setMultiWindowLayoutMode("QUAD_GRID") },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (layoutMode == "QUAD_GRID") Color(0xFF2563EB) else Color(0xFF1F1F28),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "2x2 Quad Grid",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Freeform Floating Windows Layout
                    IconButton(
                        onClick = { viewModel.setMultiWindowLayoutMode("FLOATING") },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (layoutMode == "FLOATING") Color(0xFF2563EB) else Color(0xFF1F1F28),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = "Floating Windows",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Dual Split Layout
                    IconButton(
                        onClick = { viewModel.setMultiWindowLayoutMode("SPLIT_DUAL") },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (layoutMode == "SPLIT_DUAL") Color(0xFF2563EB) else Color(0xFF1F1F28),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ViewColumn,
                            contentDescription = "Dual Split",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Tile / Restore All
                    IconButton(
                        onClick = { viewModel.resetAllWindows() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF1F1F28), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Restore Windows",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Exit Desktop Mode
                    IconButton(
                        onClick = {
                            viewModel.setMultiWindowDesktopMode(false)
                            onExit()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFDC2626), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit Desktop Mode",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopWindowFrame(
    title: String,
    iconColor: Color,
    windowId: String,
    isMinimized: Boolean,
    isMaximized: Boolean,
    isFocused: Boolean,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) iconColor else Color(0xFF272732),
                shape = RoundedCornerShape(8.dp)
            ),
        color = Color(0xFF121217),
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Window Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF1C1C24),
                                Color(0xFF181820)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(iconColor, CircleShape)
                    )
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Window Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Minimize
                    IconButton(
                        onClick = { viewModel.toggleWindowMinimized(windowId) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Minimize",
                            tint = Color.LightGray,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Maximize / Restore
                    IconButton(
                        onClick = { viewModel.toggleWindowMaximized(windowId) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                            contentDescription = "Maximize",
                            tint = Color.LightGray,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Window Content Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                if (isMinimized) {
                    // Minimized state placeholder while maintaining background audio/video
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F0F14)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Playing in Background",
                                tint = iconColor,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Running in Background",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    content()
                }
            }
        }
    }
}

@Composable
private fun RenderWindowContent(
    windowId: String,
    viewModel: AppViewModel,
    lifeOsTab: String,
    onSelectLifeOsTab: (String) -> Unit
) {
    when (windowId) {
        "SPOTIFY" -> {
            com.example.ui.components.SpotifyWebBrowserScreen(
                viewModel = viewModel,
                onBack = { viewModel.toggleWindowMinimized("SPOTIFY") }
            )
        }
        "YOUTUBE" -> {
            com.example.ui.components.YouTubeWebBrowserScreen(
                viewModel = viewModel,
                onBack = { viewModel.toggleWindowMinimized("YOUTUBE") }
            )
        }
        "INSTAGRAM" -> {
            com.example.ui.components.InstagramWebBrowserScreen(
                viewModel = viewModel,
                onBack = { viewModel.toggleWindowMinimized("INSTAGRAM") }
            )
        }
        "LIFE_OS" -> {
            Column(modifier = Modifier.fillMaxSize()) {
                // Life OS Quick Tool Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(Color(0xFF16161D))
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LifeOsTabPill("Timer", lifeOsTab == "TIMER") { onSelectLifeOsTab("TIMER") }
                    LifeOsTabPill("Tasks", lifeOsTab == "TASKS") { onSelectLifeOsTab("TASKS") }
                    LifeOsTabPill("Notes", lifeOsTab == "NOTES") { onSelectLifeOsTab("NOTES") }
                    LifeOsTabPill("Habits", lifeOsTab == "HABITS") { onSelectLifeOsTab("HABITS") }
                    LifeOsTabPill("AI Chat", lifeOsTab == "AI_CHAT") { onSelectLifeOsTab("AI_CHAT") }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (lifeOsTab) {
                        "TIMER" -> TimerView(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                        "TASKS" -> TaskEngineView(viewModel = viewModel)
                        "NOTES" -> KeepNotesView(viewModel = viewModel)
                        "HABITS" -> HabitsView(viewModel = viewModel)
                        "AI_CHAT" -> SmartChatView(viewModel = viewModel)
                        else -> TimerView(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun LifeOsTabPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF2563EB) else Color(0xFF20202A))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color.Gray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DockAppButton(
    label: String,
    color: Color,
    isMinimized: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (!isMinimized) color.copy(alpha = 0.25f) else Color(0xFF1E1E28),
        border = BorderStroke(1.dp, if (!isMinimized) color else Color(0xFF333342))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun getWindowTitle(id: String): String = when (id) {
    "SPOTIFY" -> "Spotify Web"
    "YOUTUBE" -> "YouTube Web"
    "INSTAGRAM" -> "Instagram Web"
    else -> "Life OS Hub"
}

private fun getWindowColor(id: String): Color = when (id) {
    "SPOTIFY" -> Color(0xFF1DB954)
    "YOUTUBE" -> Color(0xFFFF0000)
    "INSTAGRAM" -> Color(0xFFE1306C)
    else -> Color(0xFF3B82F6)
}
