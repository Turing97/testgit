package com.example.nasassistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
fun Meter(title: String, pct: Int, sub: String = "") {
    val color = if (pct >= 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("$pct%", style = MaterialTheme.typography.headlineSmall, color = color)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pct.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = color
            )
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StatusTab(dsm: Dsm) {
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
                err = ""
            } catch (e: Exception) {
                err = e.message ?: "错误"
            }
            delay(3000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Meter("CPU", cpu)
        Meter("内存", mem)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("⬇ 下载", style = MaterialTheme.typography.labelMedium)
                    Text("${fmt(rx)}/s", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("⬆ 上传", style = MaterialTheme.typography.labelMedium)
                    Text("${fmt(tx)}/s", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
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
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("存储空间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { n++ }) { Text("刷新") }
            }
        }
        if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error) }
        items(vols) { v ->
            val pct = if (v.total > 0) (v.used * 100 / v.total).toInt() else 0
            Meter(v.name, pct, "${fmt(v.used)} / ${fmt(v.total)}  ·  ${v.status}")
        }
        item { Text("硬盘", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(disks) { d ->
            val ok = d.status == "normal"
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text(d.model, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${d.temp}°C", style = MaterialTheme.typography.titleMedium)
                        Text(
                            d.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
