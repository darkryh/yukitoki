package io.github.darkryh.yukitoki.sample.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ln

@Composable
fun Speedometer(
    speedBytesPerSecond: Long,
    modifier: Modifier = Modifier
) {
    val speedText = formatSpeed(speedBytesPerSecond)
    val speedValue = speedText.substringBefore(" ")
    val speedUnit = speedText.substringAfter(" ")

    // Logarithmic scale so low speeds (e.g. 100 KB/s) still have a visible arc sweep,
    // rather than looking like 0% relative to a 100 MB/s cap.
    val maxSpeed = 100.0 * 1024.0 * 1024.0 // 100 MB/s
    val fraction = if (speedBytesPerSecond <= 0) 0f else {
        val logSpeed = ln(speedBytesPerSecond.toDouble()).coerceAtLeast(0.0)
        val logMax = ln(maxSpeed).coerceAtLeast(1.0)
        (logSpeed / logMax).coerceIn(0.0, 1.0).toFloat()
    }

    Box(
        modifier = modifier.size(130.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()
            val center = this.center
            val radius = (size.minDimension - strokeWidth) / 2
            
            // Draw dial ticks
            val tickCount = 20
            val tickLength = 5.dp.toPx()
            val tickStartRadius = radius - strokeWidth - 4.dp.toPx()
            val tickEndRadius = tickStartRadius - tickLength
            
            for (i in 0..tickCount) {
                val angle = 135f + (270f / tickCount) * i
                val angleRad = angle * (kotlin.math.PI / 180.0)
                
                val startX = (center.x + tickStartRadius * kotlin.math.cos(angleRad)).toFloat()
                val startY = (center.y + tickStartRadius * kotlin.math.sin(angleRad)).toFloat()
                val endX = (center.x + tickEndRadius * kotlin.math.cos(angleRad)).toFloat()
                val endY = (center.y + tickEndRadius * kotlin.math.sin(angleRad)).toFloat()
                
                val isActive = (i.toFloat() / tickCount) <= fraction
                val tickColor = if (isActive) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF1E293B)
                
                drawLine(
                    color = tickColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            
            // Draw background track
            drawArc(
                color = Color(0xFF1E293B),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Draw active neon track
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color(0xFF00E5FF), Color(0xFFD946EF), Color(0xFF00E5FF))
                ),
                startAngle = 135f,
                sweepAngle = 270f * fraction,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Subtle neon glow underlay
            drawArc(
                color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                startAngle = 135f,
                sweepAngle = 270f * fraction,
                useCenter = false,
                style = Stroke(width = strokeWidth + 6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = speedValue,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White
            )
            Text(
                text = speedUnit,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B)
            )
        }
    }
}
