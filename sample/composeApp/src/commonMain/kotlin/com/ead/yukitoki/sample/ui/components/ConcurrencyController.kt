package com.ead.yukitoki.sample.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ead.yukitoki.sample.ui.DownloadUiIntent

@Composable
fun ConcurrencyController(
    concurrency: Int,
    onIntent: (DownloadUiIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0F172A).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Max Concurrent Transfers",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$concurrency active streams",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF00E5FF)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onIntent(DownloadUiIntent.DecreaseConcurrency) },
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp)),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                
                Text(
                    text = concurrency.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                
                IconButton(
                    onClick = { onIntent(DownloadUiIntent.IncreaseConcurrency) },
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color(0xFF00E5FF)
                    )
                ) {
                    Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
