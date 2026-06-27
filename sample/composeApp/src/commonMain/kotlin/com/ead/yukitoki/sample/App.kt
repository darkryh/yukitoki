package com.ead.yukitoki.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.sample.ui.components.ConcurrencyController
import com.ead.yukitoki.sample.ui.components.DownloadRow
import com.ead.yukitoki.sample.ui.components.UrlInputBar

@Composable
fun App(environment: DownloadEnvironment) {
    MaterialTheme {
        val viewModel = remember(environment) { DownloadViewModel(environment) }
        DisposableEffect(viewModel) { onDispose { viewModel.close() } }

        val state by viewModel.state.collectAsState()

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Yukitoki Downloads",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                UrlInputBar(
                    url = state.url,
                    onIntent = viewModel::onIntent
                )

                ConcurrencyController(
                    concurrency = state.maxConcurrentDownloads,
                    onIntent = viewModel::onIntent
                )

                if (state.snapshots.isEmpty()) {
                    Text(
                        text = "No downloads yet. Paste a URL and tap Add.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.snapshots, key = { it.id.value }) { snapshot ->
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
