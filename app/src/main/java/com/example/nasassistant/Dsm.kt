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
                        102, 103 -> "NAS 不支持此功能（没装 Docker / Container Manager？）"
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
        val base = mapOf("limit" to "-1", "offset" to "0")
        val d = try {
            call("SYNO.Docker.Container", 1, "list", base + ("type" to "\"all\""))
        } catch (e: Exception) {
            call("SYNO.Docker.Container", 1, "list", base)
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

    // ---------- 下载（支持进度、取消，文件夹会打包成 zip） ----------
    fun download(
        path: String, out: OutputStream, total: Long,
        onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean
    ): Long {
        val url = "$base/webapi/entry.cgi".toHttpUrl().newBuilder()
            .addQueryParameter("api", "SYNO.FileStation.Download")
            .addQueryParameter("version", "2")
            .addQueryParameter("method", "download")
            .addQueryParameter("path", path)
            .addQueryParameter("_sid", sid)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("下载失败 ${r.code}")
            val body = r.body!!
            if (body.contentType()?.subtype == "json") {
                val code = JSONObject(body.string()).optJSONObject("error")?.optInt("code")
                throw Exception("下载失败（接口错误 $code）")
            }
            val len = if (body.contentLength() > 0) body.contentLength() else total
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
