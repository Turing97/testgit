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

val Accent = Color(0xFF7FE3FF)
val Blue = Color(0xFF2AA0FF)
val Lav = Color(0xFFD6C4FF)
val Amber = Color(0xFFF6D56B)
val Green = Color(0xFF7FE0B0)
val Rose = Color(0xFFFF9FA8)
val Gold = Color(0xFFF2D36B)
val Sub = Color(0xC2FFFFFF)

val CoolBrush = Brush.horizontalGradient(listOf(Color(0xFF7FE3FF), Color(0xFF2AA0FF)))
val LavBrush = Brush.horizontalGradient(listOf(Color(0xFFD6C4FF), Color(0xFF8F7BFF)))
val WarmBrush = Brush.horizontalGradient(listOf(Color(0xFFF6E05A), Color(0xFFFF9A5A), Color(0xFFFF7A95)))

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color(0xFF0B2540),
            background = Color(0xFF445A8C), surface = Color(0xFF445A8C),
            onSurface = Color.White, onSurfaceVariant = Color(0xFFDCE3F5),
            surfaceContainerHigh = Color(0xFF3A4E84),
            error = Rose
        ),
        content = content
    )
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF6F88C0), 0.36f to Color(0xFF5068A0),
                    0.68f to Color(0xFF445A8C), 1f to Color(0xFF5B709D)
                )
            )
            .drawBehind {
                fun orb(c: Color, cx: Float, cy: Float, r: Float) {
                    drawCircle(
                        Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r),
                        radius = r, center = Offset(cx, cy)
                    )
                }
                orb(Color(0x3DFFFFFF), size.width * 0.2f, 0f, size.width * 0.95f)
                orb(Color(0x6BD2DEFF), size.width * 0.95f, size.height * 0.02f, size.width * 0.75f)
                orb(Color(0x807896D7), 0f, size.height * 0.97f, size.width * 0.85f)
            },
        content = content
    )
}

@Composable
fun Glass(
    modifier: Modifier = Modifier, radius: Dp = 28.dp, light: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val fill = if (light) listOf(Color(0x8C82A0E1), Color(0x805A78C3)) else listOf(Color(0x94425892), Color(0x8534487E))
    val b1 = Color.White.copy(if (light) 0.32f else 0.2f)
    val b2 = Color.White.copy(if (light) 0.16f else 0.08f)
    Box(
        modifier.clip(shape).background(Brush.verticalGradient(fill))
            .border(1.dp, Brush.verticalGradient(listOf(b1, b2)), shape),
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
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            sub()
        }
        actions()
    }
}

@Composable
fun SubText(text: String) = Text(text, color = Sub, fontSize = 14.sp, maxLines = 1)

@Composable
fun PillButton(text: String, onClick: () -> Unit, color: Color = Color.White, h: Dp = 42.dp) {
    Box(
        Modifier.height(h).clip(CircleShape).background(Color.White.copy(0.12f))
            .border(1.dp, Color.White.copy(0.2f), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontSize = 14.sp, maxLines = 1) }
}

@Composable
fun CircleBtn(icon: ImageVector, onClick: () -> Unit, size: Dp = 42.dp, tint: Color = Color.White) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(0.12f))
            .border(1.dp, Color.White.copy(0.22f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(size * 0.45f), tint = tint) }
}

@Composable
fun NavCircle(icon: ImageVector, onClick: () -> Unit, badge: Boolean = false) {
    Box(Modifier.size(58.dp)) {
        Glass(Modifier.fillMaxSize().clickable(onClick = onClick), radius = 29.dp, light = true) {
            Icon(icon, null, Modifier.align(Alignment.Center).size(22.dp))
        }
        if (badge) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 13.dp).size(9.dp).clip(CircleShape).background(Amber)
        )
    }
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
            drawArc(color = Color.White.copy(0.2f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = tl, size = s, style = Stroke(w))
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = tl, size = s, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 带圆点的渐变胶囊进度条（参考天气 App 的紫外线条） */
@Composable
fun Track(p: Float, brush: Brush, h: Dp = 16.dp) {
    val big = h >= 12.dp
    Box(
        Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color(0x4A0F193C))
            .border(1.dp, Color.White.copy(0.2f), CircleShape).padding(if (big) 2.dp else 1.dp)
    ) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0.05f, 1f)).fillMaxHeight().clip(CircleShape).background(brush)) {
            if (big) Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp).size(8.dp).clip(CircleShape).background(Color.White)
            )
        }
    }
}

@Composable
fun GlassBar(p: Float, color: Color, h: Dp = 8.dp) =
    Track(p, Brush.horizontalGradient(listOf(color, color)), h)

@Composable
fun glassSwitch() = SwitchDefaults.colors(
    checkedTrackColor = Color(0xFF3DB4FF), checkedThumbColor = Color.White, checkedBorderColor = Color(0xFF3DB4FF),
    uncheckedTrackColor = Color.White.copy(0.16f), uncheckedThumbColor = Color.White.copy(0.8f),
    uncheckedBorderColor = Color.White.copy(0.25f)
)

// 饱和度较高的渐变色块，用于 Docker 图标、头像、文件类型图标
private val tiles = listOf(
    listOf(Color(0xFF3DDC97), Color(0xFF1FA36A)),
    listOf(Color(0xFFFFC857), Color(0xFFFF9F1C)),
    listOf(Color(0xFFB79BFF), Color(0xFF7C5CFF)),
    listOf(Color(0xFF5CC8FF), Color(0xFF2B8CFF)),
    listOf(Color(0xFFFF8FA3), Color(0xFFFF5C7A)),
    listOf(Color(0xFF4FE0D0), Color(0xFF1AA6B7))
)

fun tileBrush(i: Int): Brush = Brush.linearGradient(tiles[((i % tiles.size) + tiles.size) % tiles.size])
fun tileBrushOf(name: String): Brush = tileBrush(name.hashCode() and 0x7fffffff)

// 0 文件夹 1 视频 2 音频 3 图片 4 其他
fun kindBrush(k: Int): Brush = tileBrush(when (k) { 0 -> 1; 1 -> 3; 2 -> 2; 3 -> 0; else -> 5 })

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
    val grid = v("M4,4 h7 v7 H4 z", "M13,4 h7 v7 h-7 z", "M4,13 h7 v7 H4 z", "M13,13 h7 v7 h-7 z")
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
