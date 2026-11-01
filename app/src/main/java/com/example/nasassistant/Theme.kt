package com.example.nasassistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF9AB8E8)
val Lav = Color(0xFFB9A9E0)
val Amber = Color(0xFFE6C28A)
val Green = Color(0xFF8FD1A8)
val Rose = Color(0xFFE4A3AB)
val Sub = Color(0xA3FFFFFF)

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color(0xFF0F1830),
            background = Color(0xFF151B2B), surface = Color(0xFF1A2236),
            onSurface = Color.White, onSurfaceVariant = Color(0xFFB8C0D4),
            surfaceContainerHigh = Color(0xFF263050),
            error = Rose
        ),
        content = content
    )
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF151B2B), Color(0xFF222C43), Color(0xFF1A2B3A))))
            .drawBehind {
                fun orb(c: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r),
                        radius = r, center = Offset(cx, cy)
                    )
                }
                orb(Color(0x807F9CCF), size.width * 0.95f, 0f, size.width * 0.85f)
                orb(Color(0x6BA99BC9), 0f, size.height * 0.95f, size.width * 0.85f)
                orb(Color(0x4D8FC0B3), size.width, size.height * 0.5f, size.width * 0.55f)
            },
        content = content
    )
}

@Composable
fun Glass(
    modifier: Modifier = Modifier, radius: Dp = 24.dp, fill: Float = 0.075f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(fill + 0.04f), Color.White.copy(fill))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.2f), Color.White.copy(0.08f))), shape),
        content = content
    )
}

@Composable
fun TabHeader(
    title: String,
    sub: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            sub()
        }
        actions()
    }
}

@Composable
fun SubText(text: String) = Text(text, color = Sub, fontSize = 13.sp, maxLines = 1)

@Composable
fun PillButton(text: String, onClick: () -> Unit, color: Color = Color.White, h: Dp = 44.dp) {
    Box(
        Modifier.height(h).clip(CircleShape).background(Color.White.copy(0.08f))
            .border(1.dp, Color.White.copy(0.15f), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontSize = 14.sp, maxLines = 1) }
}

@Composable
fun CircleBtn(icon: ImageVector, onClick: () -> Unit, size: Dp = 44.dp, tint: Color = Color.White) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(0.08f))
            .border(1.dp, Color.White.copy(0.15f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(size * 0.45f), tint = tint) }
}

@Composable
fun Ring(
    progress: Float, dia: Dp, stroke: Dp, color: Color,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(Modifier.size(dia), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val s = Size(this.size.width - w, this.size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(color = Color.White.copy(0.13f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = tl, size = s, style = Stroke(w))
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = tl, size = s, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun GlassBar(p: Float, color: Color, h: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color.White.copy(0.13f))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

@Composable
fun glassSwitch() = SwitchDefaults.colors(
    checkedTrackColor = Accent, checkedThumbColor = Color.White, checkedBorderColor = Accent,
    uncheckedTrackColor = Color.White.copy(0.12f), uncheckedThumbColor = Color.White.copy(0.7f),
    uncheckedBorderColor = Color.White.copy(0.2f)
)

fun tileColor(name: String): Color {
    val cs = listOf(Color(0xFF8FD1A8), Color(0xFFE6C28A), Color(0xFFB9A9E0), Color(0xFF9AB8E8), Color(0xFF8FC0B3))
    return cs[(name.hashCode() and 0x7fffffff) % cs.size].copy(alpha = 0.38f)
}

object Ic {
    private fun v(vararg d: String): ImageVector {
        val b = ImageVector.Builder("i", 24.dp, 24.dp, 24f, 24f)
        d.forEach {
            b.addPath(
                PathParser().parsePathString(it).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }
    val gauge = v("M4,15 A8,8 0 1 1 20,15", "M12,15 L16,10")
    val disk = v("M4,6 a8,3 0 1,0 16,0 a8,3 0 1,0 -16,0", "M4,6 v6 c0,1.7 3.6,3 8,3 s8,-1.3 8,-3 V6", "M4,12 v6 c0,1.7 3.6,3 8,3 s8,-1.3 8,-3 v-6")
    val box = v("M3,8 l9,-5 9,5 v8 l-9,5 -9,-5 z", "M3,8 l9,5 9,-5 M12,13 v8")
    val folder = v("M3,7 a2,2 0 0 1 2,-2 h4 l2,2 h8 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 H5 a2,2 0 0 1 -2,-2 z")
    val sliders = v("M5,7 h9 M18,7 h1 M5,17 h1 M10,17 h9", "M14,7 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0", "M6,17 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0")
    val download = v("M12,4 v11 M7,11 l5,5 5,-5 M5,20 h14")
    val arrow = v("M7,17 L17,7 M9,7 h8 v8")
    val pause = v("M9,6 v12 M15,6 v12")
    val close = v("M6,6 l12,12 M18,6 L6,18")
    val play = v("M8,5 l11,7 -11,7 z")
    val up = v("M6,15 l6,-6 6,6")
    val power = v("M12,3 v9", "M6.4,6.6 a8,8 0 1 0 11.2,0")
    val restart = v("M20,12 a8,8 0 1 1 -2.6,-5.9", "M20,4 v5 h-5")
    val terminal = v("M4,6 h16 v12 H4 z", "M8,10 l3,2 -3,2 M13,15 h4")
    val music = v("M9,18 V6 l10,-2 v12", "M3,18 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0", "M13,16 a3,3 0 1,0 6,0 a3,3 0 1,0 -6,0")
    val image = v("M4,5 h16 v14 H4 z", "M4,16 l5,-5 4,4 3,-3 4,4")
    val doc = v("M6,3 h8 l4,4 v14 H6 z", "M14,3 v4 h4", "M9,13 h6 M9,17 h6")
}
