package com.example.nasassistant

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun eta(sec: Long): String = when {
    sec < 60 -> "$sec 秒"
    sec < 3600 -> "${sec / 60} 分钟"
    else -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分钟"
}

@Composable
fun TasksPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var seg by remember { mutableIntStateOf(0) }
    val all = Downloads.tasks.toList()
    val doing = all.filter { it.state != Task.DONE }
    val done = all.filter { it.state == Task.DONE }
    val shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.84f).clip(shape)
                .background(Color(0xF2242D48)).border(1.dp, Color.White.copy(0.13f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 20.dp)
        ) {
            Box(
                Modifier.padding(top = 12.dp, bottom = 16.dp).align(Alignment.CenterHorizontally)
                    .size(44.dp, 5.dp).clip(CircleShape).background(Color.White.copy(0.3f))
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下载任务", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (doing.isNotEmpty()) {
                    val anyRun = doing.any { it.state == Task.RUN || it.state == Task.WAIT }
                    PillButton(if (anyRun) "全部暂停" else "全部继续", { if (anyRun) Downloads.pauseAll() else Downloads.resumeAll() }, h = 38.dp)
                }
            }
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(CircleShape).background(Color.White.copy(0.08f)).padding(4.dp)
            ) {
                listOf("进行中 ${doing.size}", "已完成 ${done.size}").forEachIndexed { i, t ->
                    Box(
                        Modifier.weight(1f).clip(CircleShape)
                            .background(if (seg == i) Color.White.copy(0.18f) else Color.Transparent)
                            .clickable { seg = i }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(t, fontSize = 14.sp, fontWeight = if (seg == i) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (seg == i) Color.White else Sub)
                    }
                }
            }
            val shown = if (seg == 0) doing else done
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (shown.isEmpty()) item {
                    Text(if (seg == 0) "没有进行中的任务" else "还没有完成的下载", color = Sub, modifier = Modifier.padding(16.dp))
                }
                items(shown, key = { it.id }) { t -> TaskRow(t, ctx) }
                if (seg == 0 && shown.isNotEmpty()) item {
                    Text("切换应用或锁屏后继续下载，通知栏同步显示进度", color = Sub, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: Task, ctx: android.content.Context) {
    val p = if (t.total > 0) t.done.toFloat() / t.total else 0f
    val meta = when (t.state) {
        Task.RUN -> buildString {
            append(if (t.total > 0) "${fmt(t.done)} / ${fmt(t.total)}" else "已下载 ${fmt(t.done)}")
            if (t.speed > 0) append(" · ${fmt(t.speed)}/s")
            if (t.speed > 0 && t.total > t.done) append(" · 剩余 ${eta((t.total - t.done) / t.speed)}")
        }
        Task.PAUSE -> "已暂停 · ${fmt(t.done)}"
        Task.WAIT -> "等待中"
        Task.FAIL -> t.error
        Task.DONE -> "已保存到 下载/NAS · ${fmt(t.done)}"
        else -> ""
    }
    Glass(Modifier.fillMaxWidth(), radius = 22.dp, fill = 0.06f) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.state == Task.RUN || t.state == Task.PAUSE) GlassBar(p, if (t.state == Task.PAUSE) Sub else Accent)
                Text(meta, color = if (t.state == Task.FAIL) Rose else Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when (t.state) {
                Task.RUN -> { CircleBtn(Ic.pause, { Downloads.pause(t.id) }, 40.dp); CircleBtn(Ic.close, { Downloads.cancel(t.id) }, 40.dp) }
                Task.PAUSE -> { CircleBtn(Ic.play, { Downloads.resume(t.id) }, 40.dp); CircleBtn(Ic.close, { Downloads.cancel(t.id) }, 40.dp) }
                Task.WAIT -> CircleBtn(Ic.close, { Downloads.cancel(t.id) }, 40.dp)
                Task.FAIL -> { CircleBtn(Ic.restart, { Downloads.retry(t.id) }, 40.dp); CircleBtn(Ic.close, { Downloads.cancel(t.id) }, 40.dp) }
                Task.DONE -> {
                    PillButton("打开", {
                        val u = t.uri
                        if (u != null) viewUri(ctx, u, t.mime)?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    }, h = 38.dp)
                    CircleBtn(Ic.close, { Downloads.cancel(t.id) }, 40.dp)
                }
            }
        }
    }
}
