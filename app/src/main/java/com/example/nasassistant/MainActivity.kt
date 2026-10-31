package com.example.nasassistant

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
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
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val d = dsm
            if (d == null) Login { dsm = it } else Main(d) { dsm = null }
        }
    }
}

@Composable
fun Login(onOk: (Dsm) -> Unit) {
    var host by remember { mutableStateOf("") }
    var https by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text("群晖助手", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (https) "地址，如 nas.example.com:5001" else "地址，如 192.168.1.10:5000") }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(https, { https = it })
            Spacer(Modifier.width(8.dp))
            Text(if (https) "HTTPS 加密连接" else "HTTP 明文连接")
        }
        if (https) {
            OutlinedTextField(
                fingerprint, { fingerprint = it }, Modifier.fillMaxWidth(),
                label = { Text("证书 SHA-256 指纹（自签证书时填写）") },
                supportingText = { Text("仅填写已独立核对的指纹；留空使用系统证书验证。") }
            )
        } else Text("HTTP 会明文传输账号、密码和文件，建议仅在可信局域网使用。", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("账号") }
        )
        OutlinedTextField(
            pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("密码") }, visualTransformation = PasswordVisualTransformation()
        )
        Button(
            enabled = !busy && host.isNotBlank() && user.isNotBlank(),
            onClick = {
                busy = true; err = ""
                scope.launch {
                    try {
                        val d = Dsm(host.trim(), https, fingerprint.trim())
                        withContext(Dispatchers.IO) { d.login(user.trim(), pass) }
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

@Composable
fun Main(dsm: Dsm, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("状态", "存储", "文件").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> StatusTab(dsm)
                1 -> StorageTab(dsm)
                else -> FilesTab(dsm)
            }
        }
        TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.End)) { Text("退出登录") }
    }
}

@Composable
fun Meter(label: String, pct: Int) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("$label  $pct%")
        LinearProgressIndicator(progress = { pct.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun StatusTab(dsm: Dsm) {
    var loaded by remember { mutableStateOf(false) }
    var cpu by remember { mutableIntStateOf(0) }
    var mem by remember { mutableIntStateOf(0) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val d = withContext(Dispatchers.IO) { dsm.call("SYNO.Core.System.Utilization", 1, "get") }
                val c = d.getJSONObject("cpu")
                cpu = c.optInt("user_load") + c.optInt("system_load") + c.optInt("other_load")
                mem = d.getJSONObject("memory").optInt("real_usage")
                val n = d.optJSONArray("network") ?: JSONArray()
                for (i in 0 until n.length()) {
                    val o = n.getJSONObject(i)
                    if (o.optString("device") == "total") { rx = o.optLong("rx"); tx = o.optLong("tx") }
                }
                loaded = true
                err = ""
            } catch (e: Exception) {
                loaded = false
                err = e.message ?: "错误"
            }
            delay(3000)
        }
    }
    Column(Modifier.padding(16.dp)) {
        if (loaded) {
            Meter("CPU", cpu)
            Meter("内存", mem)
            Spacer(Modifier.height(8.dp))
            Text("下载 ${fmt(rx)}/s    上传 ${fmt(tx)}/s")
        } else if (err.isEmpty()) Text("正在读取设备状态…")
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
    }
}

data class Vol(val name: String, val status: String, val used: Long, val total: Long)
data class Disk(val name: String, val model: String, val temp: Int, val status: String)

@Composable
fun StorageTab(dsm: Dsm) {
    var vols by remember { mutableStateOf(listOf<Vol>()) }
    var disks by remember { mutableStateOf(listOf<Disk>()) }
    var err by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(n) {
        try {
            val d = withContext(Dispatchers.IO) { dsm.call("SYNO.Storage.CGI.Storage", 1, "load_info") }
            val va = d.optJSONArray("volumes") ?: JSONArray()
            vols = (0 until va.length()).map {
                val o = va.getJSONObject(it)
                val s = o.optJSONObject("size")
                Vol(
                    o.optString("vol_path", o.optString("id")), o.optString("status"),
                    s?.optString("used")?.toLongOrNull() ?: 0L,
                    s?.optString("total")?.toLongOrNull() ?: 0L
                )
            }
            val da = d.optJSONArray("disks") ?: JSONArray()
            disks = (0 until da.length()).map {
                val o = da.getJSONObject(it)
                Disk(o.optString("name"), o.optString("model"), o.optInt("temp"), o.optString("status"))
            }
            err = ""
        } catch (e: Exception) {
            err = e.message ?: "错误"
        }
    }
    LazyColumn(Modifier.padding(16.dp)) {
        item { Button(onClick = { n++ }) { Text("刷新") } }
        if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error) }
        item { Text("存储空间", style = MaterialTheme.typography.titleMedium) }
        items(vols) { v ->
            val pct = if (v.total > 0) (v.used * 100 / v.total).toInt() else 0
            Meter("${v.name}（${v.status}）${fmt(v.used)} / ${fmt(v.total)}", pct)
        }
        item { Spacer(Modifier.height(12.dp)); Text("硬盘", style = MaterialTheme.typography.titleMedium) }
        items(disks) { Text("${it.name}  ${it.model}  ${it.temp}°C  ${it.status}", Modifier.padding(vertical = 6.dp)) }
    }
}

data class FItem(val name: String, val path: String, val dir: Boolean, val size: Long)

fun saveFile(ctx: Context, dsm: Dsm, f: FItem) {
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NAS")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
        ?: throw Exception("无法创建文件")
    try {
        ctx.contentResolver.openOutputStream(uri)!!.use { dsm.download(f.path, it) }
    } catch (e: Exception) {
        ctx.contentResolver.delete(uri, null, null)
        throw e
    }
}

@Composable
fun FilesTab(dsm: Dsm) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(listOf<FItem>()) }
    var msg by remember { mutableStateOf("") }
    BackHandler(path.isNotEmpty()) { path = path.substringBeforeLast("/") }
    LaunchedEffect(path) {
        try {
            val root = path.isEmpty()
            val d = withContext(Dispatchers.IO) {
                if (root) dsm.call("SYNO.FileStation.List", 2, "list_share")
                else dsm.call("SYNO.FileStation.List", 2, "list", mapOf("folder_path" to path, "additional" to "[\"size\"]"))
            }
            val a = d.optJSONArray(if (root) "shares" else "files") ?: JSONArray()
            list = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                FItem(
                    o.optString("name"), o.optString("path"), root || o.optBoolean("isdir"),
                    o.optJSONObject("additional")?.optLong("size") ?: 0L
                )
            }.sortedWith(compareByDescending<FItem> { it.dir }.thenBy { it.name.lowercase() })
            msg = ""
        } catch (e: Exception) {
            msg = e.message ?: "错误"
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text(if (path.isEmpty()) "共享文件夹" else path, style = MaterialTheme.typography.titleMedium)
        if (msg.isNotEmpty()) Text(msg)
        LazyColumn {
            items(list) { f ->
                Text(
                    if (f.dir) "📁 ${f.name}" else "📄 ${f.name}  ${fmt(f.size)}",
                    Modifier.fillMaxWidth().clickable {
                        if (f.dir) path = f.path
                        else scope.launch {
                            msg = "下载中…"
                            msg = try {
                                withContext(Dispatchers.IO) { saveFile(ctx, dsm, f) }
                                "已保存到 下载/NAS/${f.name}"
                            } catch (e: Exception) { e.message ?: "下载失败" }
                        }
                    }.padding(vertical = 12.dp)
                )
            }
        }
    }
}
