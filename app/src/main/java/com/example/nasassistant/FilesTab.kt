package com.example.nasassistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FItem(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)
data class OpenState(val name: String, val done: Long = 0, val total: Long = 0)

// 0 文件夹 1 视频 2 音频 3 图片 4 其他（文档等）
fun kindOf(f: FItem): Int {
    if (f.dir) return 0
    return when (f.name.substringAfterLast('.', "").lowercase()) {
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m4v", "webm", "3gp", "rmvb", "rm", "mpg", "mpeg", "m2ts" -> 1
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape", "opus" -> 2
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> 3
        else -> 4
    }
}

fun kindIcon(k: Int): ImageVector = when (k) {
    0 -> Ic.folder; 1 -> Ic.play; 2 -> Ic.music; 3 -> Ic.image; else -> Ic.doc
}

fun kindTint(k: Int): Color = when (k) {
    0 -> Amber.copy(0.3f); 1 -> Accent.copy(0.28f); 2 -> Lav.copy(0.3f); 3 -> Green.copy(0.28f); else -> Color.White.copy(0.14f)
}

fun viewFile(ctx: Context, file: File): String? {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { ctx.startActivity(i); null } catch (e: ActivityNotFoundException) {
        "没有可以打开 .${file.extension} 文件的应用"
    }
}

fun viewUri(ctx: Context, uri: Uri, mime: String): String? {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { ctx.startActivity(i); null } catch (e: ActivityNotFoundException) { "没有可以打开该文件的应用" }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(Color.White.copy(if (active) 0.2f else 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Color.White else Color.White.copy(0.78f), maxLines = 1)
    }
}

@Composable
fun FilesTab(dsm: Dsm, path: String, onPath: (String) -> Unit, onTasks: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(listOf<FItem>()) }
    var msg by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf<OpenState?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    val fmtDate = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    BackHandler(path.isNotEmpty()) { onPath(path.substringBeforeLast("/")) }

    LaunchedEffect(path, n) {
        try {
            val root = path.isEmpty()
            val d = withContext(Dispatchers.IO) {
                if (root) dsm.call("SYNO.FileStation.List", 2, "list_share")
                else dsm.call("SYNO.FileStation.List", 2, "list",
                    mapOf("folder_path" to path, "additional" to "[\"size\",\"time\"]"))
            }
            val a = d.optJSONArray(if (root) "shares" else "files") ?: JSONArray()
            list = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val add = o.optJSONObject("additional")
                FItem(
                    o.optString("name"), o.optString("path"), root || o.optBoolean("isdir"),
                    add?.optLong("size") ?: 0L, add?.optJSONObject("time")?.optLong("mtime") ?: 0L
                )
            }.sortedWith(compareByDescending<FItem> { it.dir }.thenBy { it.name.lowercase() })
            msg = ""
        } catch (e: Exception) {
            msg = e.message ?: "错误"
        }
    }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    // 视频/音频：直接串流给外部播放器；其他：下载到缓存后用对应软件打开
    fun openRemote(f: FItem) {
        val k = kindOf(f)
        if (k == 1 || k == 2) {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(dsm.streamUrl(f.path)), if (k == 1) "video/*" else "audio/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) {
                toast("没有找到播放器，可以点右侧按钮下载后再打开")
            }
            return
        }
        val dir = File(ctx.cacheDir, "open").apply { mkdirs() }
        val file = File(dir, f.name)
        opening = OpenState(f.name, 0, f.size)
        openJob = scope.launch {
            try {
                if (!(file.exists() && f.size > 0 && file.length() == f.size)) {
                    var last = 0L
                    withContext(Dispatchers.IO) {
                        try {
                            file.outputStream().use { out ->
                                dsm.download(f.path, out, f.size, 0L, { d, t ->
                                    val now = System.currentTimeMillis()
                                    if (now - last > 200) { last = now; opening = opening?.copy(done = d, total = t) }
                                }, { !isActive })
                            }
                        } catch (e: Throwable) { file.delete(); throw e }
                    }
                }
                opening = null
                viewFile(ctx, file)?.let { toast(it) }
            } catch (e: CancellationException) {
                opening = null
            } catch (e: Exception) {
                opening = null; msg = e.message ?: "打开失败"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabHeader("文件", sub = { SubText(if (path.isEmpty()) "共享文件夹" else path) }) {
            PillButton("刷新", { n++ })
        }
        val segs = path.split("/").filter { it.isNotEmpty() }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip("全部", segs.isEmpty()) { onPath("") }
            segs.forEachIndexed { i, s ->
                Chip(s, i == segs.lastIndex) { onPath("/" + segs.take(i + 1).joinToString("/")) }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Rose, fontSize = 13.sp, modifier = Modifier.padding(start = 26.dp, top = 8.dp))
        LazyColumn(
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 170.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list) { f ->
                val k = kindOf(f)
                val task = Downloads.tasks.firstOrNull {
                    it.path == f.path && (it.state == Task.WAIT || it.state == Task.RUN || it.state == Task.PAUSE)
                }
                Glass(
                    Modifier.fillMaxWidth().clickable { if (f.dir) onPath(f.path) else openRemote(f) },
                    radius = 22.dp
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(kindTint(k)),
                            contentAlignment = Alignment.Center
                        ) { Icon(kindIcon(k), null, Modifier.size(22.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val meta = if (task != null) {
                                when (task.state) {
                                    Task.RUN -> "下载中 ${if (task.total > 0) task.done * 100 / task.total else 0}% · ${fmt(task.speed)}/s"
                                    Task.PAUSE -> "已暂停"
                                    else -> "等待下载"
                                }
                            } else buildString {
                                if (!f.dir) append(fmt(f.size))
                                if (f.mtime > 0) {
                                    if (isNotEmpty()) append("  ·  ")
                                    append(fmtDate.format(Date(f.mtime * 1000)))
                                }
                            }
                            if (meta.isNotEmpty()) Text(meta, color = if (task != null) Accent else Sub, fontSize = 12.sp, maxLines = 1)
                        }
                        if (task != null) {
                            val p = if (task.total > 0) task.done.toFloat() / task.total else 0f
                            Box(Modifier.size(44.dp).clip(CircleShape).clickable { onTasks() }) {
                                Ring(p, 44.dp, 3.dp, Accent) { Icon(if (task.state == Task.PAUSE) Ic.play else Ic.pause, null, Modifier.size(18.dp)) }
                            }
                        } else {
                            CircleBtn(Ic.download, {
                                Downloads.enqueue(f)
                                toast(if (f.dir) "文件夹将打包为 zip 后台下载" else "已加入后台下载")
                            })
                        }
                    }
                }
            }
        }
    }

    opening?.let { o ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在打开") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(o.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (o.total > 0) {
                        GlassBar((o.done.toFloat() / o.total), Accent, 8.dp)
                        Text("${fmt(o.done)} / ${fmt(o.total)}", fontSize = 12.sp, color = Sub)
                    } else Text("已下载 ${fmt(o.done)}", fontSize = 12.sp, color = Sub)
                }
            },
            confirmButton = { TextButton(onClick = { openJob?.cancel() }) { Text("取消") } }
        )
    }
}
