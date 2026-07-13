package io.github.darkryh.yukitoki.sample

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.darkryh.yukitoki.manager.DownloadState
import io.github.darkryh.yukitoki.sample.ui.DownloadUiIntent
import io.github.darkryh.yukitoki.sample.ui.components.ConcurrencyController
import io.github.darkryh.yukitoki.sample.ui.components.DownloadRow
import io.github.darkryh.yukitoki.sample.ui.components.Speedometer
import io.github.darkryh.yukitoki.sample.ui.components.TrafficChart
import io.github.darkryh.yukitoki.sample.ui.components.UrlInputBar
import io.github.darkryh.yukitoki.sample.ui.components.formatSpeed
import io.github.darkryh.yukitoki.sample.ui.theme.YukitokiTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

enum class DownloadCategory(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Done"),
    PAUSED("Paused"),
    FAILED("Failed")
}

@Composable
fun App() {
    val viewModel = remember { DownloadViewModel() }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }

    val state by viewModel.state.collectAsState()

    // Slide window for speed chart
    val speedSamples = remember {
        mutableStateListOf<Long>().apply {
            addAll(List(20) { 0L })
        }
    }

    val totalSpeed = remember(state.snapshots) {
        state.snapshots
            .filter { it.state is DownloadState.Running }
            .sumOf { (it.state as DownloadState.Running).progress.speedBytesPerSecond ?: 0L }
    }

    LaunchedEffect(totalSpeed) {
        // Collect samples at roughly 1s interval
        while (true) {
            speedSamples.add(totalSpeed)
            if (speedSamples.size > 25) {
                speedSamples.removeAt(0)
            }
            delay(1000.milliseconds)
        }
    }

    var selectedCategory by remember { mutableStateOf(DownloadCategory.ALL) }

    val snapshots = state.snapshots
    val allCount = snapshots.size
    val activeCount = snapshots.count {
        it.state is DownloadState.Running || it.state is DownloadState.Queued || it.state is DownloadState.Starting || it.state is DownloadState.Pausing || it.state is DownloadState.Cancelling
    }
    val completedCount = snapshots.count { it.state is DownloadState.Succeeded }
    val pausedCount = snapshots.count { it.state is DownloadState.Paused }
    val failedCount = snapshots.count { it.state is DownloadState.Failed || it.state is DownloadState.Cancelled }

    val filteredSnapshots = remember(snapshots, selectedCategory) {
        when (selectedCategory) {
            DownloadCategory.ALL -> snapshots
            DownloadCategory.ACTIVE -> snapshots.filter {
                it.state is DownloadState.Running || it.state is DownloadState.Queued || it.state is DownloadState.Starting || it.state is DownloadState.Pausing || it.state is DownloadState.Cancelling
            }
            DownloadCategory.COMPLETED -> snapshots.filter { it.state is DownloadState.Succeeded }
            DownloadCategory.PAUSED -> snapshots.filter { it.state is DownloadState.Paused }
            DownloadCategory.FAILED -> snapshots.filter { it.state is DownloadState.Failed || it.state is DownloadState.Cancelled }
        }
    }

    YukitokiTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWide = maxWidth > 768.dp

                if (isWide) {
                    // Wide Desktop Dashboard
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Sidebar Pane
                        Column(
                            modifier = Modifier
                                .width(300.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF070B19))
                                .border(1.dp, Color(0xFF1E293B))
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Scrollable Top Content
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Logo / Brand
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            "YUKITOKI",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            "Stream Client",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

                                // Speedometer Widget
                                Speedometer(speedBytesPerSecond = totalSpeed)

                                // Live Traffic Chart Widget
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "Network Traffic (live)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF64748B)
                                    )
                                    TrafficChart(
                                        samples = speedSamples,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(70.dp)
                                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                                            .padding(4.dp)
                                    )
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

                                // Categories
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DownloadCategory.entries.forEach { category ->
                                        val count = when (category) {
                                            DownloadCategory.ALL -> allCount
                                            DownloadCategory.ACTIVE -> activeCount
                                            DownloadCategory.COMPLETED -> completedCount
                                            DownloadCategory.PAUSED -> pausedCount
                                            DownloadCategory.FAILED -> failedCount
                                        }
                                        val isSelected = selectedCategory == category
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                                                .clickable { selectedCategory = category }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = category.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8)
                                            )
                                            Surface(
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E293B),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = count.toString(),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = if (isSelected) Color.Black else Color.White,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))

                            // ConcurrencyController inside Sidebar at the bottom
                            ConcurrencyController(
                                concurrency = state.maxConcurrentDownloads,
                                onIntent = viewModel::onIntent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Content Area Container (Centers layout content on wide monitors)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 960.dp)
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .align(Alignment.TopCenter)
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                UrlInputBar(
                                    url = state.url,
                                    onIntent = viewModel::onIntent
                                )

                                if (filteredSnapshots.isEmpty()) {
                                    Box(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = Color(0xFF1E293B),
                                                modifier = Modifier.size(64.dp)
                                            )
                                            Text(
                                                "No transfers found",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color(0xFF64748B)
                                            )
                                            Text(
                                                "Select another tab or add a new link to start streaming.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(filteredSnapshots, key = { it.id.value }) { snapshot ->
                                            DownloadRow(
                                                snapshot = snapshot,
                                                onIntent = viewModel::onIntent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Mobile Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Mobile Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "YUKITOKI",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            // Compact Speed Badge
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            ) {
                                Text(
                                    text = formatSpeed(totalSpeed),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        UrlInputBar(
                            url = state.url,
                            onIntent = viewModel::onIntent
                        )

                        // Mobile Concurrency Controller
                        ConcurrencyController(
                            concurrency = state.maxConcurrentDownloads,
                            onIntent = viewModel::onIntent,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Category Tabs for Mobile (Horizontal Scrolling Chips)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DownloadCategory.entries.forEach { category ->
                                val count = when (category) {
                                    DownloadCategory.ALL -> allCount
                                    DownloadCategory.ACTIVE -> activeCount
                                    DownloadCategory.COMPLETED -> completedCount
                                    DownloadCategory.PAUSED -> pausedCount
                                    DownloadCategory.FAILED -> failedCount
                                }
                                val isSelected = selectedCategory == category
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF1E293B),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedCategory = category }
                                ) {
                                    Text(
                                        text = "${category.label} ($count)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        if (filteredSnapshots.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFF1E293B),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        "No transfers found",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(filteredSnapshots, key = { it.id.value }) { snapshot ->
                                    DownloadRow(
                                        snapshot = snapshot,
                                        onIntent = viewModel::onIntent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
