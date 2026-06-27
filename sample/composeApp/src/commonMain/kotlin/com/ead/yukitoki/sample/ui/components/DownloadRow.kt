package com.ead.yukitoki.sample.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.manager.DownloadSnapshot
import com.ead.yukitoki.manager.DownloadState
import com.ead.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun DownloadRow(
    snapshot: DownloadSnapshot,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
                        OutlinedButton(onClick = { onIntent(DownloadUiIntent.PauseDownload(snapshot.id)) }) {
                            Text("Pause")
                        }
                        OutlinedButton(onClick = { onIntent(DownloadUiIntent.CancelDownload(snapshot.id)) }) {
                            Text("Cancel")
                        }
                    }
                    is DownloadState.Paused, is DownloadState.Pausing -> {
                        OutlinedButton(onClick = { onIntent(DownloadUiIntent.ResumeDownload(snapshot.id)) }) {
                            Text("Resume")
                        }
                        OutlinedButton(onClick = { onIntent(DownloadUiIntent.CancelDownload(snapshot.id)) }) {
                            Text("Cancel")
                        }
                    }
                    is DownloadState.Failed, is DownloadState.Cancelled -> {
                        OutlinedButton(onClick = { onIntent(DownloadUiIntent.RetryDownload(snapshot.id)) }) {
                            Text("Retry")
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
