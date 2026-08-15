package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.util.PdfCompressorHelper
import kotlinx.coroutines.delay

@Composable
fun FloatingPdfCompressionOverlay(
    modifier: Modifier = Modifier,
    onOpenResult: ((java.io.File) -> Unit)? = null
) {
    val taskState by PdfCompressorHelper.currentCompressionTask.collectAsStateWithLifecycle()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(taskState) {
        val task = taskState
        if (task != null) {
            isVisible = true
            if (!task.isRunning) {
                // Keep completed or error state visible for 4.5 seconds then auto dismiss
                delay(4500)
                isVisible = false
                PdfCompressorHelper.dismissCurrentTask()
            }
        } else {
            isVisible = false
        }
    }

    AnimatedVisibility(
        visible = isVisible && taskState != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        val task = taskState ?: return@AnimatedVisibility
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF13141F).copy(alpha = 0.95f))
                .border(
                    width = 1.dp,
                    color = when {
                        task.isRunning -> Color(0xFF4ADE80).copy(alpha = 0.6f)
                        task.resultFile != null -> Color(0xFF4ADE80)
                        else -> Color(0xFFFF5252).copy(alpha = 0.7f)
                    },
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable {
                    if (task.resultFile != null) {
                        onOpenResult?.invoke(task.resultFile)
                    }
                },
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = when {
                                        task.isRunning -> Color(0xFF4ADE80).copy(alpha = 0.15f)
                                        task.resultFile != null -> Color(0xFF4ADE80).copy(alpha = 0.2f)
                                        else -> Color(0xFFFF5252).copy(alpha = 0.15f)
                                    },
                                    shape = CircleShape
                                )
                        ) {
                            when {
                                task.isRunning -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = Color(0xFF4ADE80),
                                        trackColor = Color(0xFF2A2B3D)
                                    )
                                }
                                task.resultFile != null -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4ADE80),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (task.isRunning) "Background Compressing" else if (task.resultFile != null) "Compression Complete" else "Compression Failed",
                                    color = if (task.isRunning || task.resultFile != null) Color(0xFF4ADE80) else Color(0xFFFF5252),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = task.fileName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when {
                                task.isRunning -> {
                                    if (task.totalPages > 0) {
                                        "Page ${task.currentPage}/${task.totalPages} • Target < 5 MB"
                                    } else {
                                        task.statusText.ifEmpty { "Optimizing stream..." }
                                    }
                                }
                                task.resultFile != null -> {
                                    val origMb = String.format(java.util.Locale.US, "%.2f MB", task.originalSizeBytes / (1024.0 * 1024.0))
                                    val compMb = String.format(java.util.Locale.US, "%.2f MB", task.compressedSizeBytes / (1024.0 * 1024.0))
                                    "$origMb ➔ $compMb (${task.reductionPercentage}% smaller) • Auto-Opened"
                                }
                                else -> task.error ?: "Failed to compress PDF"
                            }
                            Text(
                                text = subtitle,
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            isVisible = false
                            PdfCompressorHelper.dismissCurrentTask()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (task.isRunning && task.progressFraction > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { task.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF4ADE80),
                        trackColor = Color(0xFF2A2B3D)
                    )
                }
            }
        }
    }
}
