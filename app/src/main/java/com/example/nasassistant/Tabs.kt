package com.example.nasassistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class Vol(val name: String, val status: String, val used: Long, val total: Long)
data class Disk(val name: String, val model: String, val temp: Int, val status: String)

fun loadStorage(dsm: Dsm): Pair<List<Vol>, List<Disk>> {
    val d = dsm.call("SYNO.Storage.CGI.Storage", 1, "load_info")
    val va = d.optJSONArray("volumes") ?: JSONArray()
    val vols = (0 until va.length()).map {
        val o = va.getJSONObject(it)
        val s = o.optJSONObject("size")
        Vol(
            o.optString("vol_path", o.optString("id")), o.optString("status"),
            s?.optString("used")?.toLongOrNull() ?: 0L, s?.optString("total")?.toLongOrNull() ?: 0L
        )
    }
    val da = d.optJSONArray("disks") ?: JSONArray()
    val disks = (0 until da.length()).map {
        val o = da.getJSONObject(it)
        Disk(o.optString("name"), o.optString("model"), o.optInt("temp"), o.optString("status"))
    }
    return vols to disks
}

fun pctColor(p: Int): Color = if (p >= 90) Rose else if (p >= 80) Amber else Accent
fun volPct(v: Vol): Int = if (v.total > 0) (v.used * 100 / v.total).toInt() else 0

@Composable
fun Dot(c: Color = Green) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(c))
}

@Composable
fun Gauge(label: String, pct: Int, color: Color, modifier: Modifier) {
    Glass(modifier, radius = 26.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Ring(pct / 100f, 96.dp, 9.dp, if (pct >= 90) Rose else color) {
                Text("$pct%", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = Sub, fontSize = 13.sp)
        }
    }
}

@Composable
fun StatusTab(dsm: Dsm, onLogout: () -> Unit) {
    var cpu by remember { mutableIntStateOf(0) }
    var mem by remember { mutableIntStateOf(0) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    val hist = remember { mutableStateListOf<Long>() }
    var raw by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var vols by remember { mutableStateOf(listOf<Vol>()) }
    var disks by remember { mutableStateOf(listOf<Disk>()) }

    LaunchedEffect(Unit) {
        try {
            val r = withContext(Dispatchers.IO) { loadStorage(dsm) }
            vols = r.first; disks = r.second
        } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val d = withContext(Dispatchers.IO) { dsm.utilization() }
                raw = d.toString().take(260)
                val c = d.optJSONObject("cpu")
                if (c != null) {
                    var s = c.optInt("user_load") + c.optInt("system_load") + c.optInt("other_load")
                    if (s == 0) s = c.optInt("total_load")
                    cpu = s
                }
                val m = d.optJSONObject("memory")
                if (m != null) {
                    var p = m.optInt("real_usage")
                    if (p == 0) {
                        val tot = m.optLong("total_real")
                        val av = m.optLong("avail_real")
                        if (tot > 0) p = (100 - av * 100 / tot).toInt()
                    }
                    mem = p
                }
                val n = d.optJSONArray("network") ?: JSONArray()
                var sr = 0L; var st = 0L; var found = false
                for (i in 0 until n.length()) {
                    val o = n.getJSONObject(i)
                    val dev = o.optString("device")
                    if (dev == "total") { rx = o.optLong("rx"); tx = o.optLong("tx"); found = true }
                    else if (dev != "lo") { sr += o.optLong("rx"); st += o.optLong("tx") }
                }
                if (!found) { rx = sr; tx = st }
                hist.add(rx); if (hist.size > 24) hist.removeAt(0)
                loaded = true; err = ""
            } catch (e: Exception) {
                err = e.message ?: "错误"
            }
            delay(3000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("状态", sub = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Dot(); SubText("${dsm.host} · 在线")
            }
        }) { PillButton("退出", onLogout) }
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Gauge("CPU", cpu, Accent, Modifier.weight(1f))
                Gauge("内存", mem, Lav, Modifier.weight(1f))
            }
            Glass(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SubText("下载"); Text("${fmt(rx)}/s", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Canvas(Modifier.size(90.dp, 36.dp)) {
                        if (hist.size > 1) {
                            val mx = (hist.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
                            val p = Path()
                            hist.forEachIndexed { i, v ->
                                val x = size.width * i / (hist.size - 1)
                                val y = size.height - size.height * (v / mx) * 0.9f - 2f
                                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                            }
                            drawPath(p, Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        SubText("上传"); Text("${fmt(tx)}/s", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (vols.isNotEmpty()) {
                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row {
                            Text("存储空间", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            SubText("${vols.size} 个卷")
                        }
                        vols.forEach { v ->
                            val p = volPct(v)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.name, fontSize = 14.sp)
                                    if (!v.status.contains("normal")) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("注意", color = Amber, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("$p%", color = pctColor(p), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                GlassBar(p / 100f, pctColor(p))
                                Text("${fmt(v.used)} / ${fmt(v.total)}", color = Sub, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (disks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    disks.forEach { d ->
                        Glass(radius = 22.dp) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Dot(if (d.status.contains("normal")) Green else Rose)
                                Text("${d.name} · ${d.temp}°C", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color = Rose, fontSize = 13.sp)
            if (loaded && cpu == 0 && mem == 0) {
                Text("接口返回（如果 CPU 和内存一直是 0，请把这段发给开发者）：$raw", color = Sub, fontSize = 11.sp)
            }
            Spacer(Modifier.height(150.dp))
        }
    }
}

@Composable
fun StorageTab(dsm: Dsm) {
    var vols by remember { mutableStateOf(listOf<Vol>()) }
    var disks by remember { mutableStateOf(listOf<Disk>()) }
    var err by remember { mutableStateOf("") }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(n) {
        try {
            val r = withContext(Dispatchers.IO) { loadStorage(dsm) }
            vols = r.first; disks = r.second; err = ""
        } catch (e: Exception) { err = e.message ?: "错误" }
    }
    Column(Modifier.fillMaxSize()) {
        TabHeader("存储", sub = { SubText("${vols.size} 个卷 · ${disks.size} 块硬盘") }) { PillButton("刷新", { n++ }) }
        LazyColumn(
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (err.isNotEmpty()) item { Text(err, color = Rose, fontSize = 13.sp) }
            items(vols) { v ->
                val p = volPct(v)
                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("$p%", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = pctColor(p))
                        }
                        GlassBar(p / 100f, pctColor(p), 10.dp)
                        Text(
                            "${fmt(v.used)} / ${fmt(v.total)}  ·  ${v.status}",
                            color = if (v.status.contains("normal")) Sub else Amber, fontSize = 12.sp
                        )
                    }
                }
            }
            item { Text("硬盘", color = Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(disks) { d ->
                val ok = d.status.contains("normal")
                Glass(Modifier.fillMaxWidth(), radius = 22.dp) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(d.model, color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${d.temp}°C", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (ok) "正常" else d.status, color = if (ok) Green else Rose, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
