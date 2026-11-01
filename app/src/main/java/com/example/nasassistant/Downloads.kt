package com.example.nasassistant

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*

data class Task(
    val id: Int, val name: String, val path: String, val dir: Boolean, val size: Long,
    val state: Int = WAIT, val done: Long = 0, val total: Long = 0, val speed: Long = 0,
    val uri: Uri? = null, val mime: String = "", val error: String = ""
) {
    companion object {
        const val WAIT = 0
        const val RUN = 1
        const val PAUSE = 2
        const val DONE = 3
        const val FAIL = 4
        const val CANCEL = 5
    }
}

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

object Downloads {
    val tasks = mutableStateListOf<Task>()
    @Volatile var newlyDone = 0
    private var seq = 0
    private val jobs = HashMap<Int, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var dsm: Dsm? = null
    private const val MAX = 2

    fun bind(ctx: Context, d: Dsm?) { app = ctx.applicationContext; dsm = d }

    fun get(id: Int): Task? = tasks.firstOrNull { it.id == id }

    private fun upd(id: Int, f: (Task) -> Task) {
        synchronized(this) {
            val i = tasks.indexOfFirst { it.id == id }
            if (i >= 0) tasks[i] = f(tasks[i])
        }
    }

    private fun drop(id: Int) { synchronized(this) { tasks.removeAll { it.id == id } } }

    fun enqueue(f: FItem) {
        val c = app ?: return
        synchronized(this) {
            val name = if (f.dir) f.name + ".zip" else f.name
            tasks.add(Task(++seq, name, f.path, f.dir, if (f.dir) 0L else f.size, total = if (f.dir) 0L else f.size))
        }
        c.startForegroundService(Intent(c, DownloadService::class.java))
        pump()
    }

    @Synchronized
    fun pump() {
        var running = tasks.count { it.state == Task.RUN }
        for (t in tasks.toList()) {
            if (running >= MAX) break
            if (t.state == Task.WAIT) {
                upd(t.id) { it.copy(state = Task.RUN) }
                start(t.id)
                running++
            }
        }
    }

    fun pause(id: Int) {
        val t = get(id) ?: return
        if (t.state == Task.RUN) { upd(id) { it.copy(state = Task.PAUSE, speed = 0) }; jobs[id]?.cancel() }
        else if (t.state == Task.WAIT) upd(id) { it.copy(state = Task.PAUSE) }
    }

    fun resume(id: Int) {
        val t = get(id) ?: return
        if (t.state == Task.PAUSE) {
            upd(id) { it.copy(state = Task.WAIT) }
            app?.let { it.startForegroundService(Intent(it, DownloadService::class.java)) }
            pump()
        }
    }

    fun retry(id: Int) {
        val t = get(id) ?: return
        if (t.state == Task.FAIL) {
            upd(id) { it.copy(state = Task.WAIT, done = 0, error = "", uri = null) }
            app?.let { it.startForegroundService(Intent(it, DownloadService::class.java)) }
            pump()
        }
    }

    fun cancel(id: Int) {
        val t = get(id) ?: return
        val c = app
        if (t.state == Task.RUN) {
            upd(id) { it.copy(state = Task.CANCEL) }
            jobs[id]?.cancel()
        } else {
            if (t.state != Task.DONE) t.uri?.let { try { c?.contentResolver?.delete(it, null, null) } catch (ex: Exception) {} }
            drop(id)
        }
    }

    fun pauseAll() { tasks.toList().forEach { pause(it.id) } }
    fun resumeAll() { tasks.toList().forEach { resume(it.id) } }

    fun clearAll() {
        tasks.toList().forEach { cancel(it.id) }
        bind(app ?: return, null)
    }

    private fun start(id: Int) {
        jobs[id] = scope.launch {
            val c = app ?: return@launch
            val d = dsm ?: return@launch
            val cr = c.contentResolver
            val t = get(id) ?: return@launch
            try {
                var uri = t.uri
                if (uri == null) {
                    val mime = if (t.dir) "application/zip" else mimeOf(t.name).let { if (it == "*/*") "application/octet-stream" else it }
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, t.name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NAS")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("无法创建文件")
                    upd(id) { it.copy(uri = uri, mime = mime) }
                }
                val u = uri!!
                val from = t.done
                var lastT = System.currentTimeMillis()
                var lastB = from
                var lastUpd = 0L
                fun write(offset: Long): Long {
                    lastB = offset
                    return cr.openOutputStream(u, if (offset > 0) "wa" else "wt")!!.use { out ->
                        d.download(t.path, out, t.size, offset, { n, len ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpd > 300) {
                                val abs = offset + n
                                val sp = (abs - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                                lastT = now; lastB = abs; lastUpd = now
                                upd(id) { it.copy(done = abs, total = if (len > 0) offset + len else it.total, speed = sp) }
                            }
                        }, { !isActive })
                    }
                }
                val got = try { from + write(from) } catch (e: RangeIgnored) { write(0) }
                cr.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                upd(id) { it.copy(state = Task.DONE, done = got, total = got, speed = 0) }
                newlyDone++
            } catch (e: CancellationException) {
                if (get(id)?.state != Task.PAUSE) {
                    get(id)?.uri?.let { try { cr.delete(it, null, null) } catch (ex: Exception) {} }
                    drop(id)
                }
            } catch (e: Exception) {
                get(id)?.uri?.let { try { cr.delete(it, null, null) } catch (ex: Exception) {} }
                upd(id) { it.copy(state = Task.FAIL, error = e.message ?: "下载失败", speed = 0, uri = null, done = 0) }
            } finally {
                jobs.remove(id)
                pump()
            }
        }
    }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private val ch = "dl"

    override fun onBind(i: Intent?): IBinder? = null

    private fun build(): Notification {
        val run = Downloads.tasks.filter { it.state == Task.RUN }
        val first = run.firstOrNull()
        val pct = if (first != null && first.total > 0) (first.done * 100 / first.total).toInt() else 0
        val spd = run.sumOf { it.speed }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("群晖助手 · 下载中 ${Downloads.tasks.count { it.state == Task.RUN || it.state == Task.WAIT }} 项")
            .setContentText(if (first != null) "${first.name}  $pct% · ${fmt(spd)}/s" else "等待中…")
            .setProgress(100, pct, first == null || first.total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "下载", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (true) {
                    delay(1000)
                    val active = Downloads.tasks.count { it.state == Task.RUN || it.state == Task.WAIT }
                    if (active == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        val n = Downloads.newlyDone
                        Downloads.newlyDone = 0
                        if (n > 0) {
                            nm.notify(2, Notification.Builder(this@DownloadService, ch)
                                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                .setContentTitle("下载完成").setContentText("$n 个文件已保存到 下载/NAS")
                                .setAutoCancel(true).build())
                        }
                        stopSelf()
                        break
                    }
                    nm.notify(1, build())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
