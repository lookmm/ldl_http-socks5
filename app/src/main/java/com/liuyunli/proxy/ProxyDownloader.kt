package com.liuyunli.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 手写 HTTP CONNECT 隧道的下载器。
 *
 * 为什么不用 HttpURLConnection 走代理：
 * 节点（gost 之类）要求 Proxy-Authorization: Basic xxx，而 HttpURLConnection 在
 * CONNECT 阶段遇到 407 不会回调 Authenticator（Oracle JDK 才支持，Android 的
 * OkHttp 实现不认），直接抛 "unexpected end of stream"，导致代理通道 100% 失败。
 *
 * 这里自己发 CONNECT、自己带 Basic 凭据，隧道建好后再在其上做 TLS + HTTP/1.1 请求。
 *
 * 只支持 http 代理（socks 走原路径）；支持 3xx 重定向、Content-Length 与 chunked、
 * 连接/读取超时。任何异常都抛出，交由上层快速切下一个源。
 */
object ProxyDownloader {
    private const val TAG = "ProxyDownloader"
    private const val MAX_REDIRECT = 4

    private fun brief(t: Throwable): String = t.javaClass.simpleName + ":" + (t.message ?: "")

    /** 下载 url 到 dst，成功后返回 "host · 通道说明"；失败抛异常。 */
    fun fetchToFile(url: String, node: NodeConfig, dst: File, minBytes: Long, checkSrs: Boolean): String {
        var current = url
        var redirects = 0
        while (true) {
            val outcome = once(current, node, dst)
            if (outcome.redirect != null) {
                if (redirects++ >= MAX_REDIRECT) throw IllegalStateException("重定向过多")
                current = outcome.redirect!!
                continue
            }
            if (dst.length() < minBytes) {
                val n = dst.length()
                dst.delete()
                throw IllegalStateException("内容异常(${n}B)")
            }
            if (checkSrs) {
                val head = ByteArray(3)
                dst.inputStream().use { it.read(head) }
                if (head[0] != 'S'.code.toByte() || head[1] != 'R'.code.toByte() || head[2] != 'S'.code.toByte()) {
                    dst.delete()
                    throw IllegalStateException("非 SRS 文件")
                }
            }
            return "代理 ${node.host}:${node.port}"
        }
    }

    /** 下载文本（给恶意域名清单用）。 */
    fun fetchText(url: String, node: NodeConfig): String {
        val dst = File.createTempFile("pd", ".txt", null)
        try {
            var current = url
            var redirects = 0
            while (true) {
                val outcome = once(current, node, dst)
                if (outcome.redirect != null) {
                    if (redirects++ >= MAX_REDIRECT) throw IllegalStateException("重定向过多")
                    current = outcome.redirect!!
                    continue
                }
                return dst.readText()
            }
        } finally {
            dst.delete()
        }
    }

    private class Outcome(val redirect: String?)

    private fun once(url: String, node: NodeConfig, dst: File): Outcome {
        val u = java.net.URL(url)
        val https = u.protocol.equals("https", true)
        val host = u.host
        val port = if (u.port > 0) u.port else if (https) 443 else 80
        var raw: Socket? = null
        try {
            raw = Socket()
            raw.connect(InetSocketAddress(node.host, node.port), 6000)
            raw.soTimeout = 15000

            val auth = StringBuilder("CONNECT ").append(host).append(':').append(port)
                .append(" HTTP/1.1\r\nHost: ").append(host).append(':').append(port)
                .append("\r\nUser-Agent: MarvisProxy/1.0\r\n")
            if (node.user.isNotBlank()) {
                val token = Base64.getEncoder()
                    .encodeToString((node.user + ":" + node.pass).toByteArray())
                auth.append("Proxy-Authorization: Basic ").append(token).append("\r\n")
            }
            auth.append("Proxy-Connection: keep-alive\r\n\r\n")

            val out = raw.getOutputStream()
            out.write(auth.toString().toByteArray())
            out.flush()

            val tunnelIn = BufferedInputStream(raw.getInputStream())
            val head = readHead(tunnelIn)
            val code = statusOf(head) ?: throw IllegalStateException("隧道无响应")
            if (code != 200) {
                if (code == 407) throw IllegalStateException("代理鉴权失败(407)")
                throw IllegalStateException("隧道建立失败($code)")
            }

            val base = raw ?: throw IllegalStateException("隧道未建立")
            val socket: Socket = if (https) {
                val ssl = layered(SSLSocketFactory.getDefault() as SSLSocketFactory, base, host, port)
                ssl.soTimeout = 15000
                ssl.startHandshake()
                ssl
            } else base

            val path = if (u.file.isNullOrEmpty()) "/" else u.file
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: MarvisProxy/1.0\r\n" +
                "Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            val so = socket.getOutputStream()
            so.write(req.toByteArray())
            so.flush()

            val din = BufferedInputStream(socket.getInputStream())
            val respHead = readHead(din)
            val status = statusOf(respHead) ?: throw IllegalStateException("响应异常")
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                val loc = headerOf(respHead, "location") ?: throw IllegalStateException("重定向缺 Location")
                return Outcome(loc)
            }
            if (status != 200) throw IllegalStateException("HTTP $status")

            dst.parentFile?.mkdirs()
            dst.outputStream().use { fo ->
                if ((headerOf(respHead, "transfer-encoding") ?: "").contains("chunked", true)) {
                    copyChunked(din, fo)
                } else {
                    val len = headerOf(respHead, "content-length")?.trim()?.toLongOrNull()
                    if (len != null) copyN(din, fo, len) else din.copyTo(fo, 16 * 1024)
                }
            }
            return Outcome(null)
        } catch (t: Throwable) {
            Log.w(TAG, "代理下载失败 ${brief(t)} $url")
            dst.delete()
            throw t
        } finally {
            try {
                raw?.close()
            } catch (_: Throwable) {
            }
        }
    }


    /**
     * android.jar 的 SSLSocketFactory 桩里没暴露 createSocket(Socket, String, int, boolean)
     * 这个分层重载，编译期取不到，所以用反射拿运行时的实现；反射失败返回明确异常，
     * 由上层快速回退到直连，不会卡住。
     */
    private fun layered(factory: SSLSocketFactory, base: Socket, host: String, port: Int): SSLSocket {
        return try {
            val m = factory.javaClass.getMethod(
                "createSocket",
                Socket::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            m.invoke(factory, base, host, port, true) as SSLSocket
        } catch (t: Throwable) {
            throw IllegalStateException("TLS 隧道建立失败:" + brief(t))
        }
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            sb.append(c)
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) break
            if (sb.length > 16384) throw IllegalStateException("响应头过长")
        }
        return sb.toString()
    }

    private fun statusOf(head: String): Int? {
        val line = head.lineSequence().firstOrNull() ?: return null
        val parts = line.split(' ')
        return if (parts.size >= 2) parts[1].toIntOrNull() else null
    }

    private fun headerOf(head: String, name: String): String? {
        val key = name.lowercase(Locale.ROOT) + ":"
        for (line in head.lineSequence()) {
            if (line.lowercase(Locale.ROOT).startsWith(key)) return line.substring(key.length).trim()
        }
        return null
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, len: Long) {
        var left = len
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) throw IllegalStateException("连接被提前关闭(剩 $left)")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun copyChunked(input: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val line = readAsciiLine(input) ?: throw IllegalStateException("chunk 结束异常")
            val size = line.trim().split(' ')[0].toIntOrNull(16) ?: throw IllegalStateException("chunk 头异常")
            if (size == 0) return
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n <= 0) throw IllegalStateException("chunk 数据不全")
                out.write(buf, 0, n)
                left -= n
            }
            readAsciiLine(input)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) break
            if (b != 13) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
