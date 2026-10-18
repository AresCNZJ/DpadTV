package com.dpadtv.client

import android.content.Context
import android.net.ConnectivityManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 极小的局域网接收服务：给电视端「扫码添加片源」用。
 *
 * · 手机扫码打开网页 → 在手机上选文件（FileReader 读成文本），或直接粘贴 → 提交
 * · 服务端只接收纯文本，不解 multipart，逻辑简单可靠
 * · IP 优先取 ConnectivityManager 的「当前活动网络」，避免多网卡（VPN / 热点 / 虚拟网卡）选错网段
 */
class QrAddServer(
    private val context: Context,
    private val port: Int = 8765,
    /** 收到 JSON / M3U 文本，返回成功导入的条数；抛异常表示解析失败 */
    private val onImport: (String) -> Int,
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    val url: String get() = "http://${localIp()}:$port"

    /** 当前活动网络的 IPv4 地址；取不到时退回遍历网卡，再退回 127.0.0.1 */
    fun localIp(): String {
        // ① 首选：系统认为当前正在用的网络（Wi-Fi 或以太网）
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                val ip = lp?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                if (ip != null) return ip.hostAddress ?: "127.0.0.1"
            }
        }
        // ② 退回：遍历网卡，跳过常见虚拟/隧道网卡，优先私网段
        runCatching {
            val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .filterNot { n ->
                    val name = n.name.lowercase()
                    name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                        name.startsWith("rmnet") || name.contains("vpn") || name.startsWith("dummy")
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
            val preferred = candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull { ip ->
                    val p = ip.split('.')
                    p.size == 4 && p[0] == "172" && (p[1].toIntOrNull() ?: 0) in 16..31
                }
                ?: candidates.firstOrNull()
            if (preferred != null) return preferred
        }
        return "127.0.0.1"
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
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
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
                    if (contentLength <= 0 || contentLength > 2 * 1024 * 1024) {
                        respond(out, "内容为空或过大（上限 2 MB）")
                        return
                    }
                    val body = readBody(reader, contentLength)
                    val raw = body.substringAfter("text=", body)
                    val text = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    val count = try {
                        onImport(text)
                    } catch (e: Exception) {
                        -1
                    }
                    respond(
                        out,
                        if (count >= 0) {
                            "已导入 $count 个片源，可以关掉本页了"
                        } else {
                            "解析失败：请确认内容是一份 sources.json 或 M3U"
                        },
                    )
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
                "<body style='font:17px/1.7 system-ui;padding:28px;background:#0b101a;color:#e5e7eb'>" +
                "<h2 style='margin:0 0 12px'>$message</h2>" +
                "<p><a style='color:#38bdf8;text-decoration:none' href='/'>← 返回再传一个</a></p></body>",
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
<body style="font:16px/1.6 system-ui;padding:20px;background:#0b101a;color:#e5e7eb;max-width:760px;margin:0 auto">
<h2 style="margin:0 0 4px">DpadTV 添加片源</h2>
<p style="color:#94a3b8;margin:0 0 18px">
  两种方式：<b>选文件</b>（手机上直接挑 sources.json），或者 <b>粘贴文本</b>，然后提交。
</p>

<label style="display:block;padding:16px;border:1px dashed #38bdf8;border-radius:12px;
  text-align:center;color:#38bdf8;cursor:pointer;margin-bottom:14px">
  📄 选择文件（.json / .txt / .m3u）
  <input type="file" id="file" accept=".json,.txt,.m3u,.m3u8,application/json,text/plain"
         style="display:none">
</label>
<div id="hint" style="color:#64748b;font-size:14px;margin:0 0 14px;text-align:center"></div>

<form method="post" action="/save">
  <textarea name="text" id="text" rows="12" style="width:100%;box-sizing:border-box;padding:12px;
    border-radius:10px;border:1px solid #334155;background:#111827;color:#e5e7eb;
    font:14px/1.5 ui-monospace,monospace"
    placeholder='{"name":"...","sources":[{"name":"...","url":"..."}]}'></textarea>
  <button type="submit" style="margin-top:14px;width:100%;padding:15px;border:0;border-radius:10px;
    background:#38bdf8;color:#04121b;font-size:17px;font-weight:600">提交并导入</button>
</form>

<p style="color:#64748b;font-size:13px;margin-top:18px">
  只有同一局域网内的设备能访问本页。打不开的话，先确认手机和电视连在同一个路由器上。
</p>

<script>
document.getElementById('file').addEventListener('change', function (e) {
  var f = e.target.files[0];
  if (!f) return;
  var r = new FileReader();
  r.onload = function () {
    document.getElementById('text').value = r.result;
    document.getElementById('hint').textContent =
      '已读取 ' + f.name + '（' + Math.round(f.size / 1024) + ' KB），点下面的按钮导入';
  };
  r.onerror = function () {
    document.getElementById('hint').textContent = '读取失败，请改用粘贴文本';
  };
  r.readAsText(f);
});
</script>
</body>
    """.trimIndent()
}
