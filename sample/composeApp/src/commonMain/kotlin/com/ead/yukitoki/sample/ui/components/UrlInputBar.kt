package com.ead.yukitoki.sample.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.manager.DownloadPriority
import com.ead.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun UrlInputBar(
    url: String,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { onIntent(DownloadUiIntent.UpdateUrl(it)) },
            label = { Text("URL") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onIntent(DownloadUiIntent.AddDownload(DownloadPriority.NORMAL)) }) {
            Text("Add")
        }
        Button(onClick = { onIntent(DownloadUiIntent.AddDownload(DownloadPriority.HIGH)) }) {
            Text("Add (High)")
        }
    }
}
