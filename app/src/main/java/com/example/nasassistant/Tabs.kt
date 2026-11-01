package com.example.nasassistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

fun volPct(v: Vol): Int = if (v.total > 0) (v.used * 100 / v.total).toInt() else 0
fun volBrush(p: Int): Brush = if (p >= 80) WarmBrush else CoolBrush

@Composable
fun Dot(c: Color = Green) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(c))
}

@Composable
fun MetricCard(label: String, pct: Int, sub: String, brush: Brush, modifier: Modifier) {
    Glass(modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp)) {
            Text(label, color = Sub, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("$pct%", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Sub, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Track(pct / 100f, brush)
        }
    }
}

@Composable
fun DiskCard(d: Disk, modifier: Modifier) {
    val ok = d.status.contains("normal")
    Glass(modifier) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(d.name, color = Sub, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("${d.temp}°", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Dot(if (ok) Green else Rose)
                Text(if (ok) "正常" else d.status, color = Sub, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(d.model, color = Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun OverviewTab(dsm: Dsm, onLogout: () -> Unit) {
    var cpu by remember { mutableIntStateOf(0) }
    var mem by remember { mutableIntStateOf(0) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    val hist = remember { mutableStateListOf<Long>() }
    var raw by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var vols by remember { mutableStateOf<List<Vol>?>(null) }
    var disks by remember { mutableStateOf(listOf<Disk>()) }
    var stErr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val r = withContext(Dispatchers.IO) { loadStorage(dsm) }
            vols = r.first; disks = r.second
        } catch (e: Exception) { stErr = e.message ?: "错误"; vols = emptyList() }
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

    // ---- 概况文字 ----
    val vl = vols
    val worst = vl?.maxByOrNull { volPct(it) }
    val badVol = vl?.firstOrNull { !it.status.contains("normal") }
    val badDisk = disks.firstOrNull { !it.status.contains("normal") }
    val attention = (worst != null && volPct(worst) >= 85) || badVol != null || badDisk != null
    val heroTitle = when {
        vl == null -> "读取中…"
        attention -> "需要注意"
        else -> "运行正常"
    }
    val heroSub = when {
        vl == null -> ""
        worst != null && volPct(worst) >= 85 -> "${worst.name} 空间已使用 ${volPct(worst)}%"
        badVol != null -> "${badVol.name} 状态：${badVol.status}"
        badDisk != null -> "${badDisk.name} 状态：${badDisk.status}"
        else -> "存储和硬盘状态良好"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader("概览", sub = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Dot(); SubText(dsm.host)
            }
        }) { PillButton("退出", onLogout) }

        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text("NAS 状态", color = Sub, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        heroTitle, fontSize = 27.sp, fontWeight = FontWeight.Medium,
                        color = if (vl != null && attention) Amber else if (vl != null) Green else Color.White
                    )
                    if (heroSub.isNotEmpty()) Text(heroSub, color = Sub, fontSize = 14.sp)
                    if (vl != null) {
                        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(0.14f)))
                        Text("${vl.size} 个卷 · ${disks.size} 块硬盘", color = Sub, fontSize = 14.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    "CPU", cpu, if (cpu < 50) "负载较低" else if (cpu < 80) "负载中等" else "负载偏高",
                    if (cpu >= 85) WarmBrush else CoolBrush, Modifier.weight(1f)
                )
                MetricCard(
                    "内存", mem, if (mem < 60) "内存充裕" else if (mem < 85) "使用中等" else "内存紧张",
                    if (mem >= 85) WarmBrush else LavBrush, Modifier.weight(1f)
                )
            }

            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 14.dp)) {
                    Text("网络", color = Sub, fontSize = 15.sp)
                    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(0.14f)))
                    Row {
                        Text("↓ ${fmt(rx)}/s", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("↑ ${fmt(tx)}/s", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                        val w = size.width
                        val h = size.height
                        val pad = 8.dp.toPx()
                        for (i in 1..4) drawLine(Color.White.copy(0.12f), Offset(w * i / 5f, 0f), Offset(w * i / 5f, h))
                        if (hist.size > 1) {
                            val mx = (hist.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
                            val pts = hist.mapIndexed { i, v ->
                                Offset(pad + (w - 2 * pad) * i / (hist.size - 1), (h - pad) - (h - 2 * pad) * (v / mx))
                            }
                            val path = Path()
                            path.moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) {
                                val a = pts[i - 1]; val b = pts[i]
                                val cx = (a.x + b.x) / 2
                                path.cubicTo(cx, a.y, cx, b.y, b.x, b.y)
                            }
                            drawPath(path, Gold, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawCircle(Color.White, 5.dp.toPx(), pts.last())
                        }
                    }
                }
            }

            val vv = vl ?: emptyList()
            if (vv.isNotEmpty()) {
                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row {
                            Text("存储空间", color = Sub, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${vv.size} 个卷", color = Sub, fontSize = 14.sp)
                        }
                        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(0.14f)))
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            vv.forEach { v ->
                                val p = volPct(v)
                                Column {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(v.name, fontSize = 16.sp)
                                        if (!v.status.contains("normal")) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("注意", color = Amber, fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text("$p%", fontSize = 30.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Track(p / 100f, volBrush(p))
                                    Spacer(Modifier.height(6.dp))
                                    Text("${fmt(v.used)} / ${fmt(v.total)}", color = Sub, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            disks.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { DiskCard(it, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (stErr.isNotEmpty()) Text("存储：$stErr", color = Rose, fontSize = 13.sp)
            if (err.isNotEmpty()) Text(err, color = Rose, fontSize = 13.sp)
            if (loaded && cpu == 0 && mem == 0) {
                Text("接口返回（如果 CPU 和内存一直是 0，请把这段发给开发者）：$raw", color = Sub, fontSize = 11.sp)
            }
            Spacer(Modifier.height(170.dp))
        }
    }
}
