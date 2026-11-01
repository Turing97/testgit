package com.example.nasassistant

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun shortTime(t: String): String = if (t.length >= 16) t.substring(5, 16).replace('/', '-') else t

fun levelColor(l: String): Color = when {
    l.startsWith("err") || l.startsWith("crit") -> Rose
    l.startsWith("warn") -> Amber
    else -> Green
}

@Composable
private fun PowerBtn(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(CircleShape).background(Color.White.copy(0.12f))
            .border(1.dp, Color.White.copy(0.2f), CircleShape).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AdminTab(dsm: Dsm) {
    val scope = rememberCoroutineScope()
    var n by remember { mutableIntStateOf(0) }
    var ssh by remember { mutableStateOf<Boolean?>(null) }
    var sshPort by remember { mutableIntStateOf(22) }
    var sshErr by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<Conn>?>(null) }
    var usersErr by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf<List<LogItem>?>(null) }
    var logsErr by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true=关机 false=重启
    var allLogs by remember { mutableStateOf(false) }

    LaunchedEffect(n) {
        try {
            val r = withContext(Dispatchers.IO) { dsm.sshGet() }
            ssh = r.first; sshPort = r.second; sshErr = ""
        } catch (e: Exception) { sshErr = e.message ?: "错误" }
        try {
            users = withContext(Dispatchers.IO) { dsm.users() }; usersErr = ""
        } catch (e: Exception) { usersErr = e.message ?: "错误"; users = emptyList() }
        try {
            logs = withContext(Dispatchers.IO) { dsm.logs(30) }; logsErr = ""
        } catch (e: Exception) { logsErr = e.message ?: "错误"; logs = emptyList() }
    }

    if (allLogs) {
        LogsScreen(dsm) { allLogs = false }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("管理", sub = { SubText("电源 · 服务 · 用户与日志") }) { PillButton("刷新", { n++ }) }
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Glass(Modifier.fillMaxWidth(), radius = 26.dp) {
                Column(Modifier.padding(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PowerBtn(Ic.restart, "重启", Accent, Modifier.weight(1f)) { confirm = false }
                        PowerBtn(Ic.power, "关机", Rose, Modifier.weight(1f)) { confirm = true }
                    }
                    Text("操作前会再次确认", color = Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(0.1f)))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(0.1f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Ic.terminal, null, Modifier.size(20.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text("SSH", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    sshErr.isNotEmpty() -> sshErr
                                    ssh == null -> "读取中…"
                                    ssh == true -> "已开启 · 端口 $sshPort"
                                    else -> "已关闭"
                                },
                                color = if (sshErr.isNotEmpty()) Rose else Sub, fontSize = 12.sp, maxLines = 2
                            )
                        }
                        Switch(
                            checked = ssh == true, enabled = ssh != null, colors = glassSwitch(),
                            onCheckedChange = { v ->
                                val old = ssh
                                ssh = v
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { dsm.sshSet(v, sshPort) }
                                        sshErr = ""
                                    } catch (e: Exception) {
                                        ssh = old; sshErr = e.message ?: "失败"
                                    }
                                }
                            }
                        )
                    }
                }
            }
            if (info.isNotEmpty()) Text(info, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))

            Glass(Modifier.fillMaxWidth(), radius = 26.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row {
                        Text("在线用户", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        SubText(if (users == null) "" else "${users!!.size} 人")
                    }
                    when {
                        usersErr.isNotEmpty() -> Text(usersErr, color = Rose, fontSize = 12.sp)
                        users == null -> Text("加载中…", color = Sub, fontSize = 13.sp)
                        users!!.isEmpty() -> Text("没有在线用户", color = Sub, fontSize = 13.sp)
                        else -> users!!.take(5).forEach { u ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(tileBrushOf(u.who)),
                                    contentAlignment = Alignment.Center
                                ) { Text(u.who.take(1).uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                                Column(Modifier.weight(1f)) {
                                    Text(u.who, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        listOf(u.type, u.from, shortTime(u.time)).filter { it.isNotBlank() }.joinToString(" · "),
                                        color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Glass(Modifier.fillMaxWidth(), radius = 26.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近日志", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("查看全部 ›", color = Sub, fontSize = 13.sp, modifier = Modifier.clickable { allLogs = true })
                    }
                    when {
                        logsErr.isNotEmpty() -> Text(logsErr, color = Rose, fontSize = 12.sp)
                        logs == null -> Text("加载中…", color = Sub, fontSize = 13.sp)
                        logs!!.isEmpty() -> Text("暂无日志", color = Sub, fontSize = 13.sp)
                        else -> logs!!.take(3).forEach { l -> LogRow(l, 1) }
                    }
                }
            }
            Spacer(Modifier.height(150.dp))
        }
    }

    confirm?.let { shutdown ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (shutdown) "确认关机？" else "确认重启？") },
            text = {
                Text(
                    if (shutdown) "关机后无法从手机远程开机，需要到 NAS 旁按电源键（或使用网络唤醒）。"
                    else "NAS 将重启，约几分钟后恢复，期间无法访问。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        info = try {
                            withContext(Dispatchers.IO) { dsm.power(shutdown) }
                            if (shutdown) "已发送关机指令" else "已发送重启指令"
                        } catch (e: Exception) { e.message ?: "操作失败" }
                    }
                }) { Text(if (shutdown) "关机" else "重启", color = Rose) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消", color = Sub) } }
        )
    }
}

@Composable
fun LogRow(l: LogItem, lines: Int) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(levelColor(l.level)))
        Text(l.msg, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(shortTime(l.time), color = Sub, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun LogsScreen(dsm: Dsm, onBack: () -> Unit) {
    var logs by remember { mutableStateOf<List<LogItem>?>(null) }
    var err by remember { mutableStateOf("") }
    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        try { logs = withContext(Dispatchers.IO) { dsm.logs(100) } }
        catch (e: Exception) { err = e.message ?: "错误"; logs = emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        TabHeader("日志", sub = { SubText("最近 100 条") }) { PillButton("返回", onBack) }
        LazyColumn(
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (err.isNotEmpty()) item { Text(err, color = Rose, fontSize = 13.sp) }
            if (logs == null) item { Text("加载中…", color = Sub) }
            items(logs ?: emptyList()) { l ->
                Glass(Modifier.fillMaxWidth(), radius = 18.dp) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { LogRow(l, 4) }
                }
            }
        }
    }
}
