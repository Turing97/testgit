package com.example.nasassistant

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    Surface(Modifier.fillMaxSize()) {
        val d = dsm
        if (d == null) Login { dsm = it } else Main(d) { dsm = null }
    }
}

@Composable
fun Header(subtitle: String, action: String?, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("🗄️ 群晖助手", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
            }
            if (action != null) {
                TextButton(onClick = onAction) { Text(action, color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
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
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("连接你的 NAS", null) {}
        Card(Modifier.padding(20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("地址，如 192.168.1.10:5000") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(https, { https = it })
                    Spacer(Modifier.width(8.dp))
                    Text("使用 HTTPS（通常是 5001 端口）")
                }
                if (https) {
                    OutlinedTextField(
                        fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(),
                        label = { Text("证书 SHA-256 指纹（自签证书选填）") },
                        supportingText = { Text("请填写已独立核对的指纹；留空使用系统证书验证。") }
                    )
                } else Text("HTTP 会明文传输密码和文件，建议仅在可信局域网使用。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("账号") }
                )
                OutlinedTextField(
                    pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("密码") }, visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
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
                                onOk(d)
                            } catch (e: Exception) {
                                err = e.message ?: "登录失败"
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "登录中…" else "登录") }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun Main(dsm: Dsm, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var filePath by remember { mutableStateOf("") }
    val tabs = listOf("状态" to "📊", "存储" to "💽", "Docker" to "🐳", "文件" to "📁")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, emoji) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(emoji, fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Header("已连接 ${dsm.host}", "退出", onLogout)
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> StatusTab(dsm)
                    1 -> StorageTab(dsm)
                    2 -> DockerTab(dsm)
                    else -> FilesTab(dsm, filePath) { filePath = it }
                }
            }
        }
    }
}
