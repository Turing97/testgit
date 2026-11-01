package com.example.nasassistant

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Svc(val name: String, val image: String, val running: Boolean, val ports: List<Int>)
data class Conn(val who: String, val from: String, val type: String, val time: String)
data class LogItem(val level: String, val msg: String, val time: String)
class RangeIgnored : Exception()

class Dsm(val host: String, https: Boolean, fingerprint: String = "") {
    private val parsed = ((if (https) "https://" else "http://") + host).toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) { "请输入地址和端口，不要包含路径" }
    }
    val hostOnly: String = parsed.host.let { if (it.contains(":")) "[$it]" else it }
    private val base = parsed.toString().trimEnd('/')
    private var sid = ""

    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        if (https && fingerprint.isNotBlank()) {
            val normalized = fingerprint.replace(Regex("[\\s:]"), "").lowercase()
            require(normalized.matches(Regex("[0-9a-f]{64}"))) { "证书 SHA-256 指纹应为 64 位十六进制字符" }
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) { throw CertificateException("不支持客户端证书") }
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {
                    val cert = c?.firstOrNull() ?: throw CertificateException("没有服务器证书")
                    cert.checkValidity()
                    val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (actual != normalized) throw CertificateException("证书指纹不匹配，请重新核对")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            val targetHost = parsed.host
            builder.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { hostname, _ -> hostname == targetHost }
        }
        builder.build()
    }

    fun call(
        api: String, version: Int, method: String,
        extra: Map<String, String> = emptyMap(), cgi: String = "entry.cgi"
    ): JSONObject {
        val fb = FormBody.Builder()
            .add("api", api).add("version", version.toString()).add("method", method)
        extra.forEach { (k, v) -> fb.add(k, v) }
        if (sid.isNotEmpty()) fb.add("_sid", sid)
        val req = Request.Builder().url("$base/webapi/$cgi").post(fb.build()).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            val j = JSONObject(r.body!!.string())
            if (!j.optBoolean("success")) {
                val code = j.optJSONObject("error")?.optInt("code") ?: -1
                throw Exception(
                    when (code) {
                        400 -> "账号或密码错误"
                        403, 404 -> "该账号开启了二次验证，暂不支持"
                        105 -> "权限不足（需要管理员账号）"
                        102, 103 -> "NAS 不支持此功能（接口 $api 不存在）"
                        else -> "接口错误 $code"
                    }
                )
            }
            return j.optJSONObject("data") ?: JSONObject()
        }
    }

    fun login(user: String, pass: String) {
        sid = call(
            "SYNO.API.Auth", 3, "login",
            mapOf("account" to user, "passwd" to pass, "session" to "Core", "format" to "sid"),
            "auth.cgi"
        ).getString("sid")
    }

    // ---------- 系统状态 ----------
    fun utilization(): JSONObject = call(
        "SYNO.Core.System.Utilization", 1, "get",
        mapOf("type" to "\"current\"", "resource" to "[\"cpu\",\"memory\",\"network\"]")
    )

    // ---------- 电源 / SSH ----------
    fun power(shutdown: Boolean) {
        call("SYNO.Core.System", 1, if (shutdown) "shutdown" else "reboot",
            mapOf("local" to "true", "force" to "false"))
    }

    fun sshGet(): Pair<Boolean, Int> {
        val d = try { call("SYNO.Core.Terminal", 3, "get") } catch (e: Exception) { call("SYNO.Core.Terminal", 1, "get") }
        return d.optBoolean("enable_ssh") to d.optInt("ssh_port", 22)
    }

    fun sshSet(on: Boolean, port: Int) {
        val p = mapOf("enable_ssh" to on.toString(), "enable_telnet" to "false", "ssh_port" to port.toString())
        try { call("SYNO.Core.Terminal", 3, "set", p) } catch (e: Exception) { call("SYNO.Core.Terminal", 1, "set", p) }
    }

    // ---------- 在线用户 / 日志 ----------
    fun users(): List<Conn> {
        val d = call("SYNO.Core.CurrentConnection", 1, "list", mapOf("start" to "0", "limit" to "50"))
        val a = d.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Conn(o.optString("who"), o.optString("from"), o.optString("type"), o.optString("time"))
        }
    }

    fun logs(limit: Int): List<LogItem> {
        val l = limit.toString()
        val tries = listOf(
            "SYNO.Core.SyslogClient.Log" to mapOf("start" to "0", "limit" to l, "target" to "\"LOCAL\"", "logtype" to "\"syslog\""),
            "SYNO.Core.SyslogClient.Log" to mapOf("start" to "0", "limit" to l, "target" to "LOCAL", "logtype" to "syslog"),
            "SYNO.LogCenter.Log" to mapOf("start" to "0", "limit" to l)
        )
        var last: Exception? = null
        for ((api, p) in tries) {
            try {
                val d = call(api, 1, "list", p)
                val a = d.optJSONArray("items") ?: d.optJSONArray("logs") ?: JSONArray()
                return (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    val msg = o.optString("descr").ifEmpty { o.optString("msg") }.ifEmpty { o.optString("log") }
                    LogItem(o.optString("level").lowercase(), msg, o.optString("time"))
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("日志接口不可用")
    }

    // ---------- Docker ----------
    private fun collectPorts(x: Any?, out: MutableSet<Int>) {
        if (x is JSONObject) {
            val it = x.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = x.opt(k)
                if (k == "host_port" || k == "HostPort") {
                    val p = v?.toString()?.toIntOrNull()
                    if (p != null && p > 0) out.add(p)
                } else {
                    collectPorts(v, out)
                }
            }
        } else if (x is JSONArray) {
            for (i in 0 until x.length()) collectPorts(x.opt(i), out)
        }
    }

    private fun portsOf(o: JSONObject): List<Int> {
        val s = sortedSetOf<Int>()
        collectPorts(o, s)
        return s.toList()
    }

    fun dockerList(): List<Svc> {
        val p0 = mapOf("limit" to "-1", "offset" to "0")
        val d = try {
            call("SYNO.Docker.Container", 1, "list", p0 + ("type" to "\"all\""))
        } catch (e: Exception) {
            call("SYNO.Docker.Container", 1, "list", p0)
        }
        val arr = d.optJSONArray("containers") ?: JSONArray()
        val out = mutableListOf<Svc>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trimStart('/')
            val running = o.optString("status") == "running" ||
                o.optJSONObject("state")?.optBoolean("Running") == true
            var ports = portsOf(o)
            if (ports.isEmpty()) {
                ports = try {
                    portsOf(call("SYNO.Docker.Container", 1, "get", mapOf("name" to "\"$name\"")))
                } catch (e: Exception) {
                    emptyList()
                }
            }
            out.add(Svc(name, o.optString("image"), running, ports))
        }
        return out.sortedWith(compareByDescending<Svc> { it.running }.thenBy { it.name.lowercase() })
    }

    // ---------- 文件：下载 / 串流 ----------
    private fun dlUrl(path: String) = "$base/webapi/entry.cgi".toHttpUrl().newBuilder()
        .addQueryParameter("api", "SYNO.FileStation.Download")
        .addQueryParameter("version", "2")
        .addQueryParameter("method", "download")
        .addQueryParameter("path", path)
        .addQueryParameter("_sid", sid)
        .build()

    /** 给外部播放器直接串流播放用的地址 */
    fun streamUrl(path: String): String = dlUrl(path).toString()

    /** 返回本次写入的字节数；offset>0 时走断点续传 */
    fun download(
        path: String, out: OutputStream, total: Long, offset: Long,
        onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean
    ): Long {
        val rb = Request.Builder().url(dlUrl(path))
        if (offset > 0) rb.header("Range", "bytes=$offset-")
        client.newCall(rb.build()).execute().use { r ->
            if (offset > 0 && r.code == 200) throw RangeIgnored()
            if (!r.isSuccessful) throw Exception("下载失败 ${r.code}")
            val body = r.body!!
            if (body.contentType()?.subtype == "json") {
                val code = JSONObject(body.string()).optJSONObject("error")?.optInt("code")
                throw Exception("下载失败（接口错误 $code）")
            }
            val len = if (body.contentLength() > 0) body.contentLength()
            else if (total > offset) total - offset else 0L
            val buf = ByteArray(64 * 1024)
            var done = 0L
            body.byteStream().use { ins ->
                while (true) {
                    if (cancelled()) throw CancellationException("已取消")
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, len)
                }
            }
            return done
        }
    }
}
