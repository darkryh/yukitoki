package com.ead.yukitoki.sample.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun ConcurrencyController(
    concurrency: Int,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Concurrency: $concurrency")
        OutlinedButton(onClick = { onIntent(DownloadUiIntent.DecreaseConcurrency) }) {
            Text("-")
        }
        OutlinedButton(onClick = { onIntent(DownloadUiIntent.IncreaseConcurrency) }) {
            Text("+")
        }
    }
}
