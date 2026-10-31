package com.example.nasassistant

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FItem(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)
data class Saved(val uri: Uri, val mime: String, val bytes: Long)
data class Dl(
    val name: String, val done: Long = 0, val total: Long = 0,
    val uri: Uri? = null, val mime: String = "", val error: String = "", val finished: Boolean = false
)

fun fileIcon(f: FItem): String {
    if (f.dir) return "📁"
    return when (f.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "🖼️"
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts" -> "🎬"
        "mp3", "flac", "wav", "aac", "m4a", "ogg" -> "🎵"
        "zip", "rar", "7z", "tar", "gz" -> "🗜️"
        "apk" -> "🤖"
        else -> "📄"
    }
}

fun saveFile(
    ctx: Context, dsm: Dsm, f: FItem,
    onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean
): Saved {
    val outName = if (f.dir) f.name + ".zip" else f.name
    val mime = if (f.dir) "application/zip"
    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(outName.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, outName)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NAS")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        ?: throw Exception("无法创建文件")
    try {
        val bytes = ctx.contentResolver.openOutputStream(uri)!!.use {
            dsm.download(f.path, it, if (f.dir) 0L else f.size, onProgress, cancelled)
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, done, null, null)
        return Saved(uri, mime, bytes)
    } catch (e: Throwable) {
        ctx.contentResolver.delete(uri, null, null)
        throw e
    }
}

fun openFile(ctx: Context, uri: Uri, mime: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) { }
}

@Composable
fun FilesTab(dsm: Dsm, path: String, onPath: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(listOf<FItem>()) }
    var msg by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(0) }
    var dl by remember { mutableStateOf<Dl?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val fmtDate = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    BackHandler(path.isNotEmpty()) { onPath(path.substringBeforeLast("/")) }

    LaunchedEffect(path, n) {
        try {
            val root = path.isEmpty()
            val d = withContext(Dispatchers.IO) {
                if (root) dsm.call("SYNO.FileStation.List", 2, "list_share")
                else dsm.call(
                    "SYNO.FileStation.List", 2, "list",
                    mapOf("folder_path" to path, "additional" to "[\"size\",\"time\"]")
                )
            }
            val a = d.optJSONArray(if (root) "shares" else "files") ?: JSONArray()
            list = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val add = o.optJSONObject("additional")
                FItem(
                    o.optString("name"), o.optString("path"), root || o.optBoolean("isdir"),
                    add?.optLong("size") ?: 0L,
                    add?.optJSONObject("time")?.optLong("mtime") ?: 0L
                )
            }.sortedWith(compareByDescending<FItem> { it.dir }.thenBy { it.name.lowercase() })
            msg = ""
        } catch (e: Exception) {
            msg = e.message ?: "错误"
        }
    }

    fun start(f: FItem) {
        dl = Dl(if (f.dir) f.name + ".zip" else f.name, total = if (f.dir) 0L else f.size)
        job = scope.launch {
            try {
                var last = 0L
                val s = withContext(Dispatchers.IO) {
                    saveFile(ctx, dsm, f, { d, t ->
                        val now = System.currentTimeMillis()
                        if (now - last > 200) { last = now; dl = dl?.copy(done = d, total = t) }
                    }, { !isActive })
                }
                dl = dl?.copy(uri = s.uri, mime = s.mime, done = s.bytes, finished = true)
            } catch (e: CancellationException) {
                dl = null; msg = "已取消下载"
            } catch (e: Exception) {
                dl = dl?.copy(error = e.message ?: "下载失败", finished = true)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { onPath(path.substringBeforeLast("/")) }) { Text("⬆ 上一级") }
            }
            Text(
                if (path.isEmpty()) "共享文件夹" else path,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            TextButton(onClick = { n++ }) { Text("刷新") }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list) { f ->
                val shape = RoundedCornerShape(14.dp)
                Card(
                    Modifier.fillMaxWidth().clip(shape).clickable {
                        if (f.dir) onPath(f.path) else start(f)
                    },
                    shape = shape
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(fileIcon(f), fontSize = 26.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            val meta = buildString {
                                if (!f.dir) append(fmt(f.size))
                                if (f.mtime > 0) {
                                    if (isNotEmpty()) append("  ·  ")
                                    append(fmtDate.format(Date(f.mtime * 1000)))
                                }
                            }
                            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { start(f) }) { Text(if (f.dir) "打包下载" else "下载") }
                    }
                }
            }
        }
    }

    dl?.let { d ->
        val u = d.uri
        AlertDialog(
            onDismissRequest = { if (d.finished) dl = null },
            title = { Text(if (d.error.isNotEmpty()) "下载失败" else if (d.finished) "下载完成" else "正在下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(d.name)
                    if (d.error.isNotEmpty()) {
                        Text(d.error, color = MaterialTheme.colorScheme.error)
                    } else if (d.finished) {
                        Text("已保存到“下载/NAS”文件夹，大小 ${fmt(d.done)}")
                    } else if (d.total > 0) {
                        LinearProgressIndicator(
                            progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${fmt(d.done)} / ${fmt(d.total)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("已下载 ${fmt(d.done)}")
                    }
                }
            },
            confirmButton = {
                if (d.finished && u != null) {
                    TextButton(onClick = { openFile(ctx, u, d.mime) }) { Text("打开") }
                } else if (!d.finished) {
                    TextButton(onClick = { job?.cancel() }) { Text("取消") }
                } else {
                    TextButton(onClick = { dl = null }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (d.finished && u != null) TextButton(onClick = { dl = null }) { Text("关闭") }
            }
        )
    }
}
