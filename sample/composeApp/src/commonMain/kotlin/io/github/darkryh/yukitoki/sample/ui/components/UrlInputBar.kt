package io.github.darkryh.yukitoki.sample.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.darkryh.yukitoki.manager.DownloadPriority
import io.github.darkryh.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun UrlInputBar(
    url: String,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { onIntent(DownloadUiIntent.UpdateUrl(it)) },
            label = { Text("Download Link", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)) },
            placeholder = { Text("https://example.com/file.zip", color = Color(0xFF64748B)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedContainerColor = Color(0xFF0F172A),
                unfocusedContainerColor = Color(0xFF0B0F19),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color(0xFFE2E8F0)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        )
        
        Button(
            onClick = { onIntent(DownloadUiIntent.AddDownload(DownloadPriority.NORMAL)) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF00E5FF)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(56.dp).border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(10.dp))
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Normal", style = MaterialTheme.typography.labelLarge)
            }
        }
        
        Button(
            onClick = { onIntent(DownloadUiIntent.AddDownload(DownloadPriority.HIGH)) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD946EF),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(56.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("High", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
