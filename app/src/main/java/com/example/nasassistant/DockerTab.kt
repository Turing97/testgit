package com.example.nasassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private fun imgLabel(i: String) = if (i.isBlank() || i.startsWith("sha256:")) "本地镜像" else i

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

    fun portOf(s: Svc): Int? = parseCfg(s, custom[s.name]).first
    fun urlOf(s: Svc): String? {
        val (port, https) = parseCfg(s, custom[s.name])
        return port?.let { "${if (https) "https" else "http"}://${dsm.hostOnly}:$it" }
    }

    val list = svcs
    val runCount = list?.count { it.running } ?: 0
    Column(Modifier.fillMaxSize()) {
        TabHeader("Docker", sub = { SubText(if (list == null) "加载中…" else "${list.size} 个容器 · $runCount 个运行中") }) {
            PillButton("刷新", { n++ })
            PillButton(if (edit) "完成" else "编辑", { edit = !edit })
        }
        if (list != null) {
            val shown = if (edit) list else list.filter { it.name !in hidden }
            val running = shown.filter { it.running }
            val stopped = shown.filter { !it.running }
            LazyColumn(
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (err.isNotEmpty()) item { Text(err, color = Rose, fontSize = 13.sp) }
                if (shown.isEmpty() && err.isEmpty()) {
                    item { Text(if (list.isEmpty()) "没有找到容器" else "全部已隐藏，点右上角“编辑”可重新显示", color = Sub) }
                }
                if (edit) item { Text("开关＝是否在列表中显示；点卡片可修改端口", color = Sub, fontSize = 12.sp) }
                if (running.isNotEmpty()) item { Text("运行中", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
                items(running) { s -> SvcRow(s, list.indexOfFirst { it.name == s.name }, edit, hidden, ::portOf, ::urlOf, ctx, { hidden = it; prefs.edit().putStringSet("hidden", it).apply() }) { info = ""; dialog = s } }
                if (stopped.isNotEmpty()) item { Text("已停止", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
                items(stopped) { s -> SvcRow(s, list.indexOfFirst { it.name == s.name }, edit, hidden, ::portOf, ::urlOf, ctx, { hidden = it; prefs.edit().putStringSet("hidden", it).apply() }) { info = ""; dialog = s } }
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
                    if (info.isNotEmpty()) Text(info, color = Rose)
                    if (s.ports.isNotEmpty()) {
                        Text("检测到的端口（点一下选用）：", fontSize = 12.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            s.ports.forEach { p -> TextButton(onClick = { portText = p.toString() }) { Text("$p") } }
                        }
                    }
                    OutlinedTextField(
                        portText, { portText = it.filter { c -> c.isDigit() } },
                        label = { Text("端口") }, singleLine = true,
                        shape = RoundedCornerShape(14.dp), colors = fieldColors()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(https, { https = it }, colors = glassSwitch())
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
            dismissButton = { TextButton(onClick = { info = ""; dialog = null }) { Text("取消", color = Sub) } }
        )
    }
}

@Composable
private fun SvcRow(
    s: Svc, idx: Int, edit: Boolean, hidden: Set<String>,
    portOf: (Svc) -> Int?, urlOf: (Svc) -> String?, ctx: Context,
    onHidden: (Set<String>) -> Unit, onEdit: () -> Unit
) {
    val port = portOf(s)
    val url = urlOf(s)
    Glass(
        Modifier.fillMaxWidth().alpha(if (s.running) 1f else 0.62f).clickable {
            if (edit) onEdit()
            else if (url != null) openUrl(ctx, url)
            else onEdit()
        },
        radius = 22.dp
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tileBrush(idx)),
                contentAlignment = Alignment.Center
            ) { Text(s.name.take(1).uppercase(), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f)) {
                Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        if (!s.running) append("已停止 · ")
                        else if (port != null) { withStyle(SpanStyle(color = Color.White)) { append(port.toString()) }; append(" · ") }
                        append(imgLabel(s.image))
                    },
                    color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (edit) {
                Switch(
                    checked = s.name !in hidden,
                    onCheckedChange = { v -> onHidden(if (v) hidden - s.name else hidden + s.name) },
                    colors = glassSwitch()
                )
            } else if (s.running && url != null) {
                CircleBtn(Ic.arrow, { openUrl(ctx, url) }, size = 40.dp)
            }
        }
    }
}
