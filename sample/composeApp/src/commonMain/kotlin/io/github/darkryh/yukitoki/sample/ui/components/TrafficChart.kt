package io.github.darkryh.yukitoki.sample.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun TrafficChart(
    samples: List<Long>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Draw horizontal grid lines
        val gridLinesCount = 3
        for (i in 1..gridLinesCount) {
            val yGrid = height * (i.toFloat() / (gridLinesCount + 1))
            drawLine(
                color = Color(0xFF1E293B).copy(alpha = 0.4f),
                start = Offset(0f, yGrid),
                end = Offset(width, yGrid),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (samples.isEmpty()) return@Canvas
        
        val maxSample = (samples.maxOrNull() ?: 0L).coerceAtLeast(1024L).toDouble()
        
        val stepX = width / (samples.size.coerceAtLeast(2) - 1)
        val path = Path()
        val fillPath = Path()
        
        samples.forEachIndexed { index, sample ->
            val x = index * stepX
            val y = height - (sample.toDouble() / maxSample * (height - 8.dp.toPx())).toFloat() - 4.dp.toPx()
            
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (index == samples.lastIndex) {
                fillPath.lineTo(x, height)
                fillPath.close()
            }
        }
        
        // Draw gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.15f), Color.Transparent)
            )
        )
        
        // Draw neon glow stroke (thick semi-transparent underlay)
        drawPath(
            path = path,
            color = Color(0xFF00E5FF).copy(alpha = 0.25f),
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw sharp active path (thin solid overlay)
        drawPath(
            path = path,
            color = Color(0xFF00E5FF),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
