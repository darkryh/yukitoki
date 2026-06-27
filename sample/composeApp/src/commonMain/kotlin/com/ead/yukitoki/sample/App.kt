package com.ead.yukitoki.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.manager.DownloadFailure
import com.ead.yukitoki.manager.DownloadPriority
import com.ead.yukitoki.manager.DownloadSnapshot
import com.ead.yukitoki.manager.DownloadState

@Composable
fun App(environment: DownloadEnvironment) {
    MaterialTheme {
        val viewModel = remember(environment) { DownloadViewModel(environment) }
        DisposableEffect(viewModel) { onDispose { viewModel.close() } }

        val state by viewModel.state.collectAsState()
        var url by remember { mutableStateOf("https://fsn1-speed.hetzner.com/10GB.bin") }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Yukitoki Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { viewModel.enqueue(url) }) { Text("Add") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Concurrency: ${state.maxConcurrentDownloads}")
                    OutlinedButton(onClick = { viewModel.setConcurrency(state.maxConcurrentDownloads - 1) }) { Text("-") }
                    OutlinedButton(onClick = { viewModel.setConcurrency(state.maxConcurrentDownloads + 1) }) { Text("+") }
                    Button(onClick = { viewModel.enqueue(url, DownloadPriority.HIGH) }) { Text("Add (High)") }
                }

                val items = state.snapshots.values.toList()
                if (items.isEmpty()) {
                    Text("No downloads yet. Paste a URL and tap Add.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items, key = { it.id.value }) { snapshot ->
                            DownloadRow(
                                snapshot = snapshot,
                                onPause = { viewModel.pause(snapshot.id) },
                                onResume = { viewModel.resume(snapshot.id) },
                                onCancel = { viewModel.cancel(snapshot.id) },
                                onRetry = { viewModel.retry(snapshot.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    snapshot: DownloadSnapshot,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = snapshot.request.destinationPath?.name ?: snapshot.request.url
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(statusLabel(snapshot.state), style = MaterialTheme.typography.bodySmall)

            val fraction = (snapshot.state as? DownloadState.Running)?.progress?.fraction
            if (snapshot.state is DownloadState.Running) {
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (snapshot.state) {
                    is DownloadState.Queued, is DownloadState.Starting, is DownloadState.Running -> {
                        OutlinedButton(onClick = onPause) { Text("Pause") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is DownloadState.Paused, is DownloadState.Pausing -> {
                        OutlinedButton(onClick = onResume) { Text("Resume") }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is DownloadState.Failed, is DownloadState.Cancelled -> {
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun statusLabel(state: DownloadState): String = when (state) {
    is DownloadState.Queued -> "Queued"
    is DownloadState.Starting -> "Starting"
    is DownloadState.Running -> {
        val p = state.progress
        val pct = p.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
        val speed = p.speedBytesPerSecond?.let { " - ${it / 1024} KB/s" } ?: ""
        "Downloading$pct$speed"
    }
    is DownloadState.Pausing -> "Pausing"
    is DownloadState.Paused -> "Paused"
    is DownloadState.Cancelling -> "Cancelling"
    is DownloadState.Cancelled -> "Cancelled"
    is DownloadState.Succeeded -> "Done - ${state.result.bytesDownloaded} bytes"
    is DownloadState.Failed -> "Failed: ${failureLabel(state.failure)}"
}

private fun failureLabel(failure: DownloadFailure): String = when (failure) {
    is DownloadFailure.HttpStatus -> "HTTP ${failure.code}"
    is DownloadFailure.Network -> "network error"
    is DownloadFailure.Timeout -> "timeout"
    is DownloadFailure.PermissionDenied -> "permission denied"
    is DownloadFailure.NoSpace -> "no space"
    is DownloadFailure.AlreadyExists -> "already exists"
    is DownloadFailure.FileSystem -> "file system error"
    is DownloadFailure.IntegrityMismatch -> "size mismatch"
    is DownloadFailure.ChecksumMismatch -> "checksum mismatch"
    is DownloadFailure.ServerChanged -> "server file changed"
    is DownloadFailure.CancelledByUser -> "cancelled"
    is DownloadFailure.PausedByUser -> "paused"
    is DownloadFailure.Unknown -> failure.message
}
