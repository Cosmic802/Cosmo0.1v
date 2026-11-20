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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.font.FontWeight
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
    /** Main accent; follows the colour chosen in Settings. */
    val Cyan: Color get() = Prefs.accentColor()
    val Amber = Color(0xFFFFB300)
    val Crimson = Color(0xFFFF1744)
    val Violet = Color(0xFF7C4DFF)
    val Panel = Color(0xFF0B121A)
    val Dim = Color(0xFF1B2A36)
}

@Composable
fun CosmoTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = CosmoColors.Cyan,
        onPrimary = CosmoColors.Black,
        secondary = CosmoColors.Amber,
        onSecondary = CosmoColors.Black,
        background = CosmoColors.Black,
        onBackground = CosmoColors.Cyan,
        surface = CosmoColors.Panel,
        onSurface = CosmoColors.Cyan
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun CosmoBackground(
    accent: Color = CosmoColors.Cyan,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF060C1A), Color(0xFF0A0B26), Color(0xFF150B31))
                )
            )
            .drawBehind {
                // accent glow, top right
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.02f),
                        radius = size.width * 1.1f
                    )
                )
                // violet glow, bottom left
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(CosmoColors.Violet.copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(size.width * 0.0f, size.height * 0.92f),
                        radius = size.width * 1.2f
                    )
                )
                // soft blue haze, middle
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF3D5AFE).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.45f),
                        radius = size.width * 0.9f
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
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFFA9B8FF).copy(alpha = (0.16f * Prefs.glass).coerceIn(0.02f, 0.6f)),
                        Color(0xFF6C5CE7).copy(alpha = (0.07f * Prefs.glass).coerceIn(0.01f, 0.4f))
                    )
                ),
                shape
            )
            .drawBehind {
                // light catching the top-left corner of the glass
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.9f
                    )
                )
            }
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.45f), accent.copy(alpha = 0.12f), accent.copy(alpha = 0.35f))
                ),
                shape
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
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

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                center = c,
                radius = r * 1.45f
            ),
            radius = r * 1.45f,
            center = c
        )
        drawCircle(color = accent.copy(alpha = 0.12f), radius = r * pulse, center = c)
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

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = CosmoColors.Cyan,
                checkedThumbColor = CosmoColors.Black,
                uncheckedTrackColor = CosmoColors.Dim,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** Row of selectable pills, e.g. language or personality. */
@Composable
fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (id, label) ->
            val sel = id == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (sel) CosmoColors.Cyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                        shape
                    )
                    .border(1.dp, if (sel) CosmoColors.Cyan else CosmoColors.Dim, shape)
                    .clickable { onSelect(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (sel) CosmoColors.Cyan else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
