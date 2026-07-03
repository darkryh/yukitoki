package io.github.darkryh.yukitoki.sample.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.darkryh.yukitoki.manager.DownloadPriority
import io.github.darkryh.yukitoki.manager.DownloadSnapshot
import io.github.darkryh.yukitoki.manager.DownloadState
import io.github.darkryh.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun DownloadRow(
    snapshot: DownloadSnapshot,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val name = snapshot.request.destinationPath?.name ?: snapshot.request.url.substringAfterLast("/")
    val (fileTypeLabel, fileTypeColor) = getFileTypeDetails(name)
    val state = snapshot.state

    val isRunning = state is DownloadState.Running
    val isQueued = state is DownloadState.Queued || state is DownloadState.Starting
    val isPaused = state is DownloadState.Paused || state is DownloadState.Pausing
    val isFinished = state is DownloadState.Succeeded
    val isFailed = state is DownloadState.Failed || state is DownloadState.Cancelled

    val stateColor = when (state) {
        is DownloadState.Queued -> Color(0xFF64748B)
        is DownloadState.Starting -> Color(0xFF00E5FF)
        is DownloadState.Running -> Color(0xFF00E5FF)
        is DownloadState.Pausing -> Color(0xFFF59E0B)
        is DownloadState.Paused -> Color(0xFFF59E0B)
        is DownloadState.Cancelling -> Color(0xFFEC4899)
        is DownloadState.Cancelled -> Color(0xFFEC4899)
        is DownloadState.Succeeded -> Color(0xFF10B981)
        is DownloadState.Failed -> Color(0xFFF43F5E)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A).copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1: Badges + File Name + Priority
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Badge
                Surface(
                    color = fileTypeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.border(1.dp, fileTypeColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                ) {
                    Text(
                        text = fileTypeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = fileTypeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // File Name
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Priority Badge
                if (snapshot.request.priority == DownloadPriority.HIGH) {
                    Surface(
                        color = Color(0xFFD946EF).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.border(1.dp, Color(0xFFD946EF).copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = "HIGH",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFD946EF),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Row 2: Status Indicator / Progress Bar
            val fraction = (state as? DownloadState.Running)?.progress?.fraction
                ?: (state as? DownloadState.Succeeded)?.let { 1.0 }
                ?: 0.0

            val animatedProgress by animateFloatAsState(
                targetValue = fraction.toFloat(),
                animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    if (isRunning) {
                        // Gradient for active progress
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(Color(0xFF00E5FF), Color(0xFFD946EF))
                                    )
                                )
                        )
                    } else {
                        // Solid state color
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress)
                                .clip(RoundedCornerShape(3.dp))
                                .background(stateColor)
                        )
                    }
                }

                // Progress percentage & status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusLabel(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = stateColor
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isRunning) Color(0xFF00E5FF) else stateColor
                    )
                }
            }

            // Row 3: Stats Details & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Size transferred & speed & ETA
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val progress = (state as? DownloadState.Running)?.progress
                    val bytesDownloaded = progress?.bytesDownloaded
                        ?: (state as? DownloadState.Succeeded)?.result?.bytesDownloaded
                        ?: 0L
                    val totalBytes = progress?.totalBytes
                        ?: (state as? DownloadState.Succeeded)?.result?.totalBytes

                    val sizeText = if (totalBytes != null) {
                        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
                    } else {
                        formatBytes(bytesDownloaded)
                    }

                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFE2E8F0)
                    )

                    if (isRunning && progress != null) {
                        val etaText = formatEta(progress.etaMillis)
                        val speedText = progress.speedBytesPerSecond?.let { formatSpeed(it) } ?: "0 B/s"
                        Text(
                            text = "$speedText • ETA: $etaText",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = Color(0xFF64748B)
                        )
                    }
                }

                // Control Action Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        isRunning || isQueued -> {
                            IconButton(
                                onClick = { onIntent(DownloadUiIntent.PauseDownload(snapshot.id)) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color(0xFFF59E0B),
                                    containerColor = Color(0xFFF59E0B).copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onIntent(DownloadUiIntent.CancelDownload(snapshot.id)) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color(0xFFF43F5E),
                                    containerColor = Color(0xFFF43F5E).copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, Color(0xFFF43F5E).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                            }
                        }
                        isPaused -> {
                            IconButton(
                                onClick = { onIntent(DownloadUiIntent.ResumeDownload(snapshot.id)) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color(0xFF00E5FF),
                                    containerColor = Color(0xFF00E5FF).copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onIntent(DownloadUiIntent.CancelDownload(snapshot.id)) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color(0xFFF43F5E),
                                    containerColor = Color(0xFFF43F5E).copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, Color(0xFFF43F5E).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                            }
                        }
                        isFailed -> {
                            IconButton(
                                onClick = { onIntent(DownloadUiIntent.RetryDownload(snapshot.id)) },
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color(0xFF00E5FF),
                                    containerColor = Color(0xFF00E5FF).copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getFileTypeDetails(fileName: String): Pair<String, Color> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "mp3", "mp4", "mkv", "avi", "flac", "mov", "wav" -> "MEDIA" to Color(0xFFF97316) // Orange
        "pdf", "docx", "txt", "epub", "xlsx", "pptx" -> "DOC" to Color(0xFF10B981) // Emerald
        "zip", "tar", "gz", "rar", "7z", "dmg", "iso" -> "ARCHIVE" to Color(0xFFF59E0B) // Amber
        "exe", "msi", "apk", "deb", "pkg" -> "EXEC" to Color(0xFFEC4899) // Hot Pink
        else -> if (ext.isNotEmpty()) ext.uppercase() to Color(0xFF00E5FF) else "FILE" to Color(0xFF3B82F6)
    }
}
