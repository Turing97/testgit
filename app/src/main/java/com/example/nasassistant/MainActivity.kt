package com.example.nasassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        File(cacheDir, "open").deleteRecursively()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { NasTheme { App() } }
    }
}

fun fmt(b: Long): String {
    var v = b.toDouble()
    val u = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

@Composable
fun App() {
    var dsm by remember { mutableStateOf<Dsm?>(null) }
    GlassBackground {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
            val d = dsm
            if (d == null) Login { dsm = it } else Main(d) { dsm = null }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Color.White.copy(0.22f),
    focusedLabelColor = Accent, unfocusedLabelColor = Sub, cursorColor = Accent,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White
)

data class LoginRecord(val host: String, val user: String, val https: Boolean)

fun readLoginHistory(prefs: android.content.SharedPreferences): List<LoginRecord> {
    val records = mutableListOf<LoginRecord>()
    try {
        val items = JSONArray(prefs.getString("login_history", "[]"))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val host = item.optString("host")
            val user = item.optString("user")
            if (host.isNotBlank() && user.isNotBlank()) records.add(LoginRecord(host, user, item.optBoolean("https")))
        }
    } catch (_: Exception) { }
    val host = prefs.getString("host", "").orEmpty()
    val user = prefs.getString("user", "").orEmpty()
    if (!prefs.contains("login_history") && host.isNotBlank() && user.isNotBlank()) {
        records.add(LoginRecord(host, user, prefs.getBoolean("https", false)))
    }
    return records
}

fun writeLoginHistory(prefs: android.content.SharedPreferences, records: List<LoginRecord>) {
    val items = JSONArray()
    records.forEach { record ->
        items.put(JSONObject().put("host", record.host).put("user", record.user).put("https", record.https))
    }
    prefs.edit().putString("login_history", items.toString()).apply()
}

@Composable
fun Login(onOk: (Dsm) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("nas", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var https by remember { mutableStateOf(prefs.getBoolean("https", false)) }
    var fingerprint by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var pass by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(readLoginHistory(prefs)) }
    var showHistory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding()
            .navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        val logoShape = RoundedCornerShape(26.dp)
        Box(
            Modifier.size(92.dp).clip(logoShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3B474F), Color(0xFF202A30))))
                .border(1.dp, Color.White.copy(0.2f), logoShape)
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("群晖助手", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("连接你的 NAS", color = Sub, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Glass(Modifier.fillMaxWidth(), radius = 28.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (history.isNotEmpty()) {
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Text(if (showHistory) "收起历史账号" else "选择历史账号 (${history.size})")
                    }
                    if (showHistory) history.forEach { record ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                host = record.host; user = record.user; https = record.https
                                pass = ""; fingerprint = ""; err = ""; showHistory = false
                            }.padding(vertical = 8.dp)) {
                                Text(record.user, fontSize = 14.sp, maxLines = 1)
                                Text(record.host + if (record.https) " · HTTPS" else " · HTTP", color = Sub, fontSize = 12.sp, maxLines = 1)
                            }
                            TextButton(onClick = {
                                history = history.filterNot { it == record }
                                writeLoginHistory(prefs, history)
                            }) { Text("删除") }
                        }
                    }
                }
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                    label = { Text("地址，如 192.168.1.10:5000") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(https, { https = it }, colors = glassSwitch())
                    Spacer(Modifier.width(10.dp))
                    Text("使用 HTTPS（通常是 5001 端口）", fontSize = 14.sp)
                }
                if (https) {
                    OutlinedTextField(
                        fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(),
                        label = { Text("证书 SHA-256 指纹（自签证书选填）") },
                        supportingText = { Text("请填写已独立核对的指纹；留空使用系统证书验证。") }
                    )
                } else Text("HTTP 会明文传输密码和文件，建议仅在可信局域网使用。", color = Rose)
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(), label = { Text("账号") }
                )
                OutlinedTextField(
                    pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(), label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0F1830)),
                    enabled = !busy && host.isNotBlank() && user.isNotBlank(),
                    onClick = {
                        busy = true; err = ""
                        scope.launch {
                            try {
                                val h = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                                val d = Dsm(h, https, fingerprint.trim())
                                withContext(Dispatchers.IO) { d.login(user.trim(), pass) }
                                prefs.edit().putString("host", h).putBoolean("https", https)
                                    .putString("user", user.trim()).apply()
                                val record = LoginRecord(h, user.trim(), https)
                                history = (listOf(record) + history.filterNot { it == record }).take(20)
                                writeLoginHistory(prefs, history)
                                onOk(d)
                            } catch (e: Exception) {
                                err = e.message ?: "登录失败"
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "登录中…" else "登录", fontSize = 16.sp) }
                if (err.isNotEmpty()) Text(err, color = Rose, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun MiniPill(onClick: () -> Unit) {
    val act = Downloads.tasks.filter { it.state == Task.RUN || it.state == Task.WAIT }
    if (act.isEmpty()) return
    val first = act.firstOrNull { it.state == Task.RUN } ?: act.first()
    val p = if (first.total > 0) first.done.toFloat() / first.total else 0f
    val spd = act.sumOf { it.speed }
    Glass(Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onClick), radius = 29.dp, light = true) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Ic.download, null, Modifier.size(20.dp), tint = Accent)
            Column(Modifier.weight(1f)) {
                Text("后台下载中 · ${act.size} 项", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                GlassBar(p, Accent, 4.dp)
            }
            Text("${fmt(spd)}/s", color = Sub, fontSize = 12.sp)
            Icon(Ic.up, null, Modifier.size(18.dp), tint = Sub)
        }
    }
}

@Composable
fun Main(dsm: Dsm, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    remember(dsm) { Downloads.bind(ctx, dsm); 0 }
    var tab by remember { mutableIntStateOf(0) }
    var filePath by remember { mutableStateOf("") }
    var showTasks by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val tabs: List<Pair<String, ImageVector>> = listOf(
        "概览" to Ic.grid, "Docker" to Ic.box, "文件" to Ic.folder, "管理" to Ic.sliders
    )
    val busy = Downloads.tasks.any { it.state == Task.RUN || it.state == Task.WAIT }
    BackHandler(showTasks) { showTasks = false }
    Box(Modifier.fillMaxSize()) {
        key(refresh) {
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> OverviewTab(dsm) { Downloads.clearAll(); onLogout() }
                    1 -> DockerTab(dsm)
                    2 -> FilesTab(dsm, filePath, { filePath = it }) { showTasks = true }
                    else -> AdminTab(dsm)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiniPill { showTasks = true }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavCircle(Ic.download, { showTasks = true }, busy)
                Glass(Modifier.weight(1f).height(64.dp), radius = 32.dp, light = true) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { i, (label, icon) ->
                            val sel = tab == i
                            Column(
                                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp))
                                    .background(if (sel) Color.White.copy(0.26f) else Color.Transparent)
                                    .clickable { tab = i },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(icon, null, Modifier.size(21.dp), tint = if (sel) Color.White else Color.White.copy(0.8f))
                                Text(label, fontSize = 10.sp, color = if (sel) Color.White else Color.White.copy(0.8f),
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                            }
                        }
                    }
                }
                NavCircle(Ic.restart, { refresh++ })
            }
        }
        if (showTasks) TasksPanel { showTasks = false }
    }
}
