package com.cosmic802.cosmo

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CosmoColors {
    val Black = Color(0xFF05080D)
    val Cyan = Color(0xFF00E5FF)
    val Amber = Color(0xFFFFB300)
    val Crimson = Color(0xFFFF1744)
    val Violet = Color(0xFF7C4DFF)
    val Panel = Color(0xFF0B121A)
    val Dim = Color(0xFF1B2A36)
}

private val CosmoScheme = darkColorScheme(
    primary = CosmoColors.Cyan,
    onPrimary = CosmoColors.Black,
    secondary = CosmoColors.Amber,
    onSecondary = CosmoColors.Black,
    background = CosmoColors.Black,
    onBackground = CosmoColors.Cyan,
    surface = CosmoColors.Panel,
    onSurface = CosmoColors.Cyan
)

@Composable
fun CosmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CosmoScheme, content = content)
}

@Composable
fun CosmoBackground(
    accent: Color = CosmoColors.Cyan,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(CosmoColors.Black)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.05f),
                        radius = size.width * 0.95f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(CosmoColors.Violet.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.95f),
                        radius = size.width * 0.95f
                    )
                )
            }
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color = CosmoColors.Cyan,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.03f))
                ),
                shape
            )
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content
    )
}

@Composable
fun CoreVisualizer(
    modifier: Modifier = Modifier,
    accent: Color = CosmoColors.Cyan
) {
    val transition = rememberInfiniteTransition(label = "core")
    val rotA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "rotA"
    )
    val rotB by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "rotB"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = modifier) {
        val c = center
        val r = size.minDimension / 2f

        drawCircle(color = accent.copy(alpha = 0.10f), radius = r * pulse, center = c)
        drawCircle(
            color = accent.copy(alpha = 0.6f),
            radius = r * 0.96f,
            center = c,
            style = Stroke(width = 2.dp.toPx())
        )

        rotate(degrees = rotA, pivot = c) {
            for (i in 0 until 4) {
                drawArc(
                    color = accent,
                    startAngle = i * 90f + 10f,
                    sweepAngle = 60f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.82f, c.y - r * 0.82f),
                    size = Size(r * 1.64f, r * 1.64f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Butt)
                )
            }
        }

        rotate(degrees = rotB, pivot = c) {
            for (i in 0 until 6) {
                drawArc(
                    color = CosmoColors.Amber,
                    startAngle = i * 60f + 5f,
                    sweepAngle = 35f,
                    useCenter = false,
                    topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                    size = Size(r * 1.24f, r * 1.24f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        drawCircle(
            color = accent.copy(alpha = 0.25f),
            radius = r * 0.42f * pulse,
            center = c
        )
        drawCircle(color = accent, radius = r * 0.30f * pulse, center = c)
        drawCircle(color = CosmoColors.Black, radius = r * 0.14f, center = c)
    }
}

@Composable
fun StatusRing(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 72.dp,
    color: Color = CosmoColors.Cyan
) {
    val p = progress.coerceIn(0f, 1f)
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = CosmoColors.Dim,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * p,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
    }
}
