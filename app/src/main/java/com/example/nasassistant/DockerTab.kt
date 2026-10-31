package com.example.nasassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

// 保存格式： "端口,是否HTTPS(1/0)"
private fun parseCfg(s: Svc, stored: String?): Pair<Int?, Boolean> {
    val p = stored?.split(",")
    val port = p?.getOrNull(0)?.toIntOrNull() ?: s.ports.firstOrNull()
    return port to (p?.getOrNull(1) == "1")
}

@Composable
fun DockerTab(dsm: Dsm) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("nas", Context.MODE_PRIVATE) }
    var svcs by remember { mutableStateOf<List<Svc>?>(null) }
    var err by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet()) }
    val custom = remember {
        mutableStateMapOf<String, String>().apply {
            prefs.all.forEach { (k, v) -> if (k.startsWith("p:") && v is String) put(k.removePrefix("p:"), v) }
        }
    }
    var dialog by remember { mutableStateOf<Svc?>(null) }
    var info by remember { mutableStateOf("") }

    LaunchedEffect(n) {
        svcs = null; err = ""
        try {
            svcs = withContext(Dispatchers.IO) { dsm.dockerList() }
        } catch (e: Exception) {
            err = e.message ?: "错误"; svcs = emptyList()
        }
    }

    fun urlOf(s: Svc): String? {
        val (port, https) = parseCfg(s, custom[s.name])
        return port?.let { "${if (https) "https" else "http"}://${dsm.hostOnly}:$it" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Docker 服务", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { n++ }) { Text("刷新") }
            TextButton(onClick = { edit = !edit }) { Text(if (edit) "完成" else "编辑") }
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        val list = svcs
        if (list == null) {
            Text("加载中…", modifier = Modifier.padding(16.dp))
        } else {
            val shown = if (edit) list else list.filter { it.name !in hidden }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (shown.isEmpty() && err.isEmpty()) {
                    item { Text(if (list.isEmpty()) "没有找到容器" else "全部已隐藏，点右上角“编辑”可重新显示") }
                }
                if (edit) item { Text("打开开关＝在列表中显示；点卡片可设置端口", style = MaterialTheme.typography.bodySmall) }
                items(shown) { s ->
                    val shape = RoundedCornerShape(16.dp)
                    val url = urlOf(s)
                    Card(
                        Modifier.fillMaxWidth().clip(shape).clickable {
                            if (edit) { info = ""; dialog = s }
                            else if (url != null) openUrl(ctx, url)
                            else { info = "没检测到端口，请手动设置"; dialog = s }
                        },
                        shape = shape
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).clip(CircleShape)
                                    .background(if (s.running) Color(0xFF2E7D32) else Color(0xFF9E9E9E))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium)
                                Text(s.image, style = MaterialTheme.typography.bodySmall)
                            }
                            if (edit) {
                                Text(url?.substringAfterLast(":") ?: "无端口", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = s.name !in hidden,
                                    onCheckedChange = { v ->
                                        hidden = if (v) hidden - s.name else hidden + s.name
                                        prefs.edit().putStringSet("hidden", hidden).apply()
                                    }
                                )
                            } else {
                                Text(
                                    if (url != null) ":${url.substringAfterLast(":")} ↗" else "—",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dialog?.let { s ->
        val cfg = parseCfg(s, custom[s.name])
        var portText by remember(s.name) { mutableStateOf(cfg.first?.toString() ?: "") }
        var https by remember(s.name) { mutableStateOf(cfg.second) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(s.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.error)
                    if (s.ports.isNotEmpty()) {
                        Text("检测到的端口（点一下选用）：", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            s.ports.forEach { p -> TextButton(onClick = { portText = p.toString() }) { Text("$p") } }
                        }
                    }
                    OutlinedTextField(
                        portText, { portText = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") }, singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(https, { https = it })
                        Spacer(Modifier.width(8.dp))
                        Text("使用 HTTPS")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (portText.isBlank()) {
                        custom.remove(s.name)
                        prefs.edit().remove("p:${s.name}").apply()
                    } else {
                        val v = "$portText,${if (https) "1" else "0"}"
                        custom[s.name] = v
                        prefs.edit().putString("p:${s.name}", v).apply()
                    }
                    info = ""; dialog = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { info = ""; dialog = null }) { Text("取消") } }
        )
    }
}
