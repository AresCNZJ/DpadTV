package com.dpadtv.client

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 极小的局域网接收服务：给电视端「扫码添加片源」用。
 *
 * 流程：App 在 8765 端口起一个服务 → 二维码内容就是 http://<电视IP>:8765
 * → 手机浏览器扫码打开 → 粘贴 sources.json 提交 → 本服务把内容回调给 App 解析入库。
 *
 * 刻意不引第三方服务器库：只需要一个 ServerSocket + 极简 HTTP 解析，
 * 请求体最大只接受 256KB（一份 sources.json 通常几 KB）。
 */
class QrAddServer(
    private val port: Int = 8765,
    /** 收到 JSON 文本，返回成功导入的条数；抛异常表示解析失败 */
    private val onImport: (String) -> Int,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    val url: String get() = "http://$localIp:$port"

    private val localIp: String
        get() = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.hostAddress?.startsWith("192.168.") == true }
                ?.hostAddress
                ?: java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }

    fun start(): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            running = true
            thread(isDaemon = true) { acceptLoop() }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                serverSocket?.accept() ?: break
            } catch (e: Exception) {
                break
            }
            thread(isDaemon = true) { runCatching { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val out = socket.getOutputStream()
            when {
                path.startsWith("/save") -> {
                    if (contentLength <= 0 || contentLength > 256 * 1024) {
                        respond(out, "内容为空或过大")
                        return
                    }
                    val body = readBody(reader, contentLength)
                    val json = body.substringAfter("text=", "").let {
                        URLDecoder.decode(it, "UTF-8")
                    }.ifEmpty { body }
                    val count = try {
                        onImport(json)
                    } catch (e: Exception) {
                        -1
                    }
                    respond(out, if (count >= 0) "已导入 $count 个片源，可以关掉本页了" else "解析失败：请确认粘贴的是 sources.json 内容")
                }
                path.startsWith("/raw") -> {
                    if (contentLength <= 0 || contentLength > 256 * 1024) {
                        respond(out, "内容为空或过大")
                        return
                    }
                    val json = readBody(reader, contentLength)
                    val count = try {
                        onImport(json)
                    } catch (e: Exception) {
                        -1
                    }
                    respond(out, if (count >= 0) "已导入 $count 个片源" else "解析失败")
                }
                else -> respondHtml(out, page())
            }
        }
    }

    private fun readBody(reader: BufferedReader, length: Int): String {
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun respond(out: OutputStream, message: String) {
        respondHtml(
            out,
            "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                "<body style='font:16px/1.6 system-ui;padding:24px;background:#0b101a;color:#e5e7eb'>" +
                "<h2>$message</h2><p><a style='color:#38bdf8' href='/'>返回</a></p></body>",
        )
    }

    private fun respondHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(): String = """
        <!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
        <title>DpadTV 添加片源</title>
        <body style="font:16px/1.6 system-ui;padding:20px;background:#0b101a;color:#e5e7eb;max-width:720px;margin:0 auto">
        <h2 style="margin:0 0 4px">DpadTV 添加片源</h2>
        <p style="color:#94a3b8;margin:0 0 16px">把 sources.json 的内容粘进来，点提交即可导入到电视。</p>
        <form method="post" action="/save">
          <textarea name="text" rows="12" style="width:100%;box-sizing:border-box;padding:12px;border-radius:10px;
            border:1px solid #334155;background:#111827;color:#e5e7eb;font:14px/1.5 ui-monospace,monospace"
            placeholder='{"name":"...","sources":[{"name":"...","url":"..."}]}'></textarea>
          <button type="submit" style="margin-top:14px;width:100%;padding:14px;border:0;border-radius:10px;
            background:#38bdf8;color:#04121b;font-size:17px;font-weight:600">提交并导入</button>
        </form>
        <p style="color:#64748b;font-size:13px;margin-top:16px">只有同一局域网内的设备能访问这个地址。</p>
        </body>
    """.trimIndent()
}
