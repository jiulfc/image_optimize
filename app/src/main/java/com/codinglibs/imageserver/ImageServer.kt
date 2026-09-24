package com.codinglibs.imageserver

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class ImageServer(private val context: Context) {

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
    }

    data class ImageItem(val uri: Uri, val mime: String)

    class PendingDelete(val uri: Uri, val sender: IntentSender) {
        val latch = CountDownLatch(1)
        @Volatile var success = false
    }

    private val cr: ContentResolver = context.contentResolver
    private val images = mutableListOf<ImageItem>()
    private val videos = mutableListOf<ImageItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null

    @Volatile var running = false
    @Volatile var port = 0
    @Volatile var onDeletePending: ((PendingDelete) -> Unit)? = null
    @Volatile private var pendingRef: PendingDelete? = null
    @Volatile var token: String? = null
    @Volatile var onAuthRequested: (() -> Unit)? = null
    @Volatile private var authRequested = false
    @Volatile private var authDenied = false
    @Volatile private var lastSeen = 0L

    fun start(portToTry: Int = 8080): Boolean {
        return try {
            loadImages()
            loadVideos()
            serverSocket = ServerSocket(portToTry)
            port = serverSocket!!.localPort
            running = true
            Thread { acceptLoop() }.start()
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun localIp(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.let { return it.hostAddress ?: "" }
        }
        return "127.0.0.1"
    }

    fun grantAccess(approved: Boolean) {
        authRequested = false
        if (approved) {
            token = UUID.randomUUID().toString().replace("-", "")
            lastSeen = System.currentTimeMillis()
        } else {
            authDenied = true
        }
    }

    fun isAuthPending(): Boolean = authRequested && token == null

    fun confirmDelete(confirmed: Boolean) {
        val pd = pendingRef ?: return
        pd.success = confirmed && try {
            cr.delete(pd.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
        pendingRef = null
        pd.latch.countDown()
    }

    private fun listFor(type: String?): MutableList<ImageItem> =
        if (type == "video") videos else images

    private fun loadImages() {
        val selection = (if (Build.VERSION.SDK_INT >= 30) "${MediaStore.Images.Media.IS_TRASHED}=0 AND " else "") +
            "${MediaStore.Images.Media.MIME_TYPE} IN ('image/jpeg','image/png','image/gif','image/webp','image/bmp')"
        val list = mutableListOf<ImageItem>()
        cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE),
            selection, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list += ImageItem(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    c.getString(mimeCol) ?: "image/jpeg"
                )
            }
        }
        synchronized(images) {
            images.clear()
            images.addAll(list)
        }
    }

    private fun loadVideos() {
        val selection = if (Build.VERSION.SDK_INT >= 30) "${MediaStore.Video.Media.IS_TRASHED}=0" else null
        val list = mutableListOf<ImageItem>()
        cr.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.MIME_TYPE),
            selection, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                list += ImageItem(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    c.getString(mimeCol) ?: "video/mp4"
                )
            }
        }
        synchronized(videos) {
            videos.clear()
            videos.addAll(list)
        }
    }

    private fun acceptLoop() {
        // ponytail: thread per connection; single-user tool, add a pool if it grows
        while (running) {
            val sock = try { serverSocket!!.accept() } catch (e: Exception) { break }
            Thread { handle(sock) }.start()
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")
                val out = s.getOutputStream()
                when {
                    path == "/auth" -> handleAuth(out)
                    path == "/" && validToken(query) -> out.respond(200, "text/html; charset=utf-8", html(param(query, "type") ?: "image"))
                    path == "/" -> servePending(out)
                    path == "/img" && validToken(query) -> serveMedia(out, query, rangeHeader)
                    path == "/del" && method == "POST" && validToken(query) -> doDelete(out, query)
                    else -> out.respond(403, "text/plain", "forbidden")
                }
            }
        } catch (e: Exception) {
            // client aborts mid-transfer (common when seeking video) — must not kill the process
            Log.d("MediaServer", "connection closed: ${e.message}")
        }
    }

    private fun serveMedia(out: OutputStream, query: String, rangeHeader: String?) {
        val type = param(query, "type") ?: "image"
        val i = indexOf(query) ?: return out.respond(400, "text/plain", "bad index")
        val list = listFor(type)
        val item = synchronized(list) { list.getOrNull(i) } ?: return out.respond(404, "text/plain", "no media")
        val pfd = try { cr.openFileDescriptor(item.uri, "r") } catch (e: Exception) { null }
            ?: return out.respond(404, "text/plain", "cannot open media")
        pfd.use {
            val size = it.statSize
            val seekable = size > 0
            var start = 0L
            var end = if (seekable) size - 1 else Long.MAX_VALUE
            var partial = false
            if (seekable && rangeHeader != null) {
                Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)?.let { m ->
                    partial = true
                    start = m.groupValues[1].toLongOrNull() ?: 0
                    end = (m.groupValues[2].toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                }
            }
            FileInputStream(it.fileDescriptor).use { fis ->
                // seek before headers so a failed seek can abort without a response;
                // ponytail: Android 10-13 MediaProvider pipe fds can't lseek, read-through is O(offset) but works
                val buf = ByteArray(65536)
                var left = start
                try {
                    while (left > 0) {
                        val skipped = fis.skip(left)
                        if (skipped > 0) {
                            left -= skipped
                        } else {
                            val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) return
                            left -= n
                        }
                    }
                } catch (e: IOException) {
                    Log.d("MediaServer", "seek failed, dropping request", e)
                    return
                }
                val head = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${item.mime}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (partial) {
                        append("Content-Range: bytes $start-$end/$size\r\n")
                        append("Content-Length: ${end - start + 1}\r\n")
                    } else if (seekable) {
                        append("Content-Length: $size\r\n")
                    }
                    append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                val toSend = if (partial) end - start + 1 else Long.MAX_VALUE
                var sent = 0L
                while (sent < toSend) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), toSend - sent).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
            }
        }
    }

    private fun doDelete(out: OutputStream, query: String) {
        val type = param(query, "type") ?: "image"
        val i = indexOf(query) ?: return out.respond(400, "text/plain", "bad index")
        val list = listFor(type)
        val item = synchronized(list) { list.getOrNull(i) } ?: return out.respond(404, "text/plain", "no media")
        if (deleteItem(item)) {
            synchronized(list) { list.removeAt(i) }
            out.respond(200, "text/plain", "ok")
        } else {
            out.respond(500, "text/plain", "delete failed")
        }
    }

    private fun deleteItem(item: ImageItem): Boolean {
        return try {
            cr.delete(item.uri, null, null) > 0
        } catch (e: RecoverableSecurityException) {
            val pd = PendingDelete(item.uri, e.userAction.actionIntent.intentSender)
            pendingRef = pd
            mainHandler.post { onDeletePending?.invoke(pd) }
            // ponytail: blocks this connection 60s waiting for user confirm; fine for single user
            if (!pd.latch.await(60, TimeUnit.SECONDS)) false else pd.success
        } catch (e: Exception) {
            false
        }
    }

    private fun validToken(query: String): Boolean {
        val t = token ?: return false
        if (param(query, "token") != t) return false
        if (System.currentTimeMillis() - lastSeen > SESSION_TIMEOUT_MS) {
            token = null
            authRequested = false
            authDenied = false
            return false
        }
        lastSeen = System.currentTimeMillis()
        return true
    }

    private fun handleAuth(out: OutputStream) {
        token?.let {
            out.respond(200, "application/json", "{\"token\":\"$it\"}")
            return
        }
        if (authDenied) {
            out.respond(200, "application/json", "{\"denied\":true}")
            return
        }
        if (!authRequested) {
            authRequested = true
            mainHandler.post { onAuthRequested?.invoke() }
        }
        out.respond(200, "application/json", "{\"pending\":true}")
    }

    private fun servePending(out: OutputStream) {
        val body = try { readAsset("pending.html") } catch (e: Exception) { "waiting for approval" }
        out.respond(200, "text/html; charset=utf-8", body)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun indexOf(query: String): Int? =
        query.split('&').firstOrNull { it.startsWith("i=") }?.substring(2)?.toIntOrNull()

    private fun param(query: String, name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun OutputStream.respond(code: Int, type: String, body: String) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n$body").toByteArray()
        )
    }

    private fun html(type: String): String {
        val total = synchronized(listFor(type)) { listFor(type).size }
        val template = readAsset("index.html")
        return template.replace("__TOTAL__", total.toString()).replace("__TYPE__", type)
    }
}
