package com.example.nasassistant

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class Dsm(host: String, https: Boolean, fingerprint: String) {
    private val base = (if (host.startsWith("https://", ignoreCase = true) || host.startsWith("http://", ignoreCase = true)) host else (if (https) "https://" else "http://") + host).trimEnd('/')
        .toHttpUrl().also { require(it.isHttps == https && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null) { "地址协议与开关必须一致，且不能包含路径" } }.toString().trimEnd('/')
    private var sid = ""

    // No custom trust is installed unless the user supplies an exact SHA-256 leaf certificate fingerprint.
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
            val targetHost = base.toHttpUrl().host
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
                        105 -> "权限不足（状态和存储需要管理员账号）"
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

    fun download(path: String, out: OutputStream) {
        val url = "$base/webapi/entry.cgi".toHttpUrl().newBuilder()
            .addQueryParameter("api", "SYNO.FileStation.Download")
            .addQueryParameter("version", "2")
            .addQueryParameter("method", "download")
            .addQueryParameter("path", path)
            .addQueryParameter("_sid", sid)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("下载失败 ${r.code}")
            r.body!!.byteStream().use { it.copyTo(out) }
        }
    }
}
