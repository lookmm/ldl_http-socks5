package com.liuyunli.proxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 版本更新：GitHub 仓库的 version.json 作为唯一元数据源，APK 走 Release 附件。
 *
 * 下载通道与规则集更新完全复用一套逻辑（RulePrefs.updateChannel + ProxyDownloader）：
 *   自动 —— 先直连各镜像，全灭再用代理兜底
 *   直连 —— 只直连
 *   代理 —— 先用当前节点代理，全灭降级直连
 *
 * 元数据源五个：jsdelivr(fastly/cdn) 在国内通常最快，raw 与两个 GitHub 反代(ghfast/gh-proxy)兜底。
 * 拿到后比较 versionCode，大于本机才提示。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val REPO = "lookmm/ldl_http-socks5"

    private const val RAW_META = "https://raw.githubusercontent.com/$REPO/main/version.json"

    /** 与 RuleSetUpdater.mirrors 同一套镜像梯度：jsdelivr 最快，GitHub 反代兜底。 */
    private val META_URLS = listOf(
        "https://fastly.jsdelivr.net/gh/$REPO@main/version.json",
        "https://cdn.jsdelivr.net/gh/$REPO@main/version.json",
        RAW_META,
        "https://ghfast.top/$RAW_META",
        "https://gh-proxy.com/$RAW_META",
    )

    /** Release 附件同样给反代镜像：国内直连 objects.githubusercontent.com 常失败。 */
    private fun apkMirrors(url: String): List<String> = listOf(
        "https://ghfast.top/$url",
        "https://gh-proxy.com/$url",
        url,
    )

    private const val CONNECT_TIMEOUT = 4
    private const val READ_TIMEOUT = 8
    private const val APK_READ_TIMEOUT = 30
    /** 检查总预算：源再多也不拖过 20s，超时直接判失败让用户重试 */
    private const val CHECK_BUDGET_MS = 20_000L
    private const val MIN_APK = 100_000L

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val md5: String,
        val size: Long,
        val notes: String,
    ) {
        fun label(): String = "v$versionName（$versionCode）"
    }

    fun currentCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }.getOrDefault(0)

    fun currentName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun channelLabel(channel: String): String = when (channel) {
        "proxy" -> "代理"
        "direct" -> "直连"
        else -> "自动（直连优先，代理兜底）"
    }

    /** 检查最新版本。全部源失败时抛异常，异常信息可直接展示。 */
    fun check(context: Context): Info {
        val channel = RulePrefs.updateChannel(context)
        val node = NodeConfig.load(context)
        var lastErr = "无可用源"
        val deadline = System.currentTimeMillis() + CHECK_BUDGET_MS
        for ((url, viaProxy) in attempts(META_URLS, channel, node)) {
            if (System.currentTimeMillis() > deadline) {
                lastErr = "检查超时（20s）"
                break
            }
            try {
                val text = if (viaProxy) ProxyDownloader.fetchText(url, node) else directText(url)
                parse(text)?.let { return it }
                lastErr = "元数据格式不对"
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "元数据源不可用 $url(proxy=$viaProxy): $lastErr")
            }
        }
        throw IllegalStateException(lastErr)
    }

    /** 下载 APK 并校验 MD5，返回就绪文件。必须在后台线程调用。 */
    fun download(context: Context, info: Info): File {
        val dst = apkFile(context)
        val channel = RulePrefs.updateChannel(context)
        val node = NodeConfig.load(context)
        var lastErr = "下载失败"
        for ((url, viaProxy) in attempts(apkMirrors(info.url), channel, node)) {
            try {
                dst.delete()
                if (viaProxy) {
                    ProxyDownloader.fetchToFile(url, node, dst, MIN_APK, false)
                } else {
                    directToFile(url, dst)
                }
                if (!dst.exists() || dst.length() < MIN_APK) {
                    throw IllegalStateException("文件异常(${if (dst.exists()) dst.length() else 0}B)")
                }
                if (info.md5.isNotBlank() && !md5(dst).equals(info.md5, true)) {
                    dst.delete()
                    throw IllegalStateException("MD5 校验失败")
                }
                Log.i(TAG, "下载完成 ${dst.length()}B via=${if (viaProxy) "proxy" else "direct"}")
                return dst
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "下载失败 $url(proxy=$viaProxy): $lastErr")
                dst.delete()
            }
        }
        throw IllegalStateException(lastErr)
    }

    /** 调起系统安装器。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updatefile", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }

    fun apkFile(context: Context): File =
        File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    // ---------------- 内部 ----------------

    /** 与 RuleSetUpdater.plan 同构的通道排序：proxy 优先 / 直连优先，另一侧兜底。 */
    private fun attempts(urls: List<String>, channel: String, node: NodeConfig): List<Pair<String, Boolean>> {
        val canProxy = node.isConfigured()
        val out = ArrayList<Pair<String, Boolean>>(urls.size * 2)
        if (channel == "proxy" && canProxy) urls.forEach { out.add(it to true) }
        urls.forEach { out.add(it to false) }
        if (channel != "direct" && canProxy) urls.forEach { out.add(it to true) }
        return out
    }

    private fun directText(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun directToFile(url: String, dst: File) {
        val c = open(url, APK_READ_TIMEOUT)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            c.inputStream.use { ins ->
                dst.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, readSec: Int = READ_TIMEOUT): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT * 1000
            readTimeout = readSec * 1000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ldl-http-socks5-updater")
            setRequestProperty("Accept", "application/json, application/octet-stream, */*")
        }

    private fun parse(text: String): Info? = runCatching {
        val o = JSONObject(text)
        Info(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            url = o.optString("url", ""),
            md5 = o.optString("md5", ""),
            size = o.optLong("size", 0L),
            notes = o.optString("notes", ""),
        )
    }.getOrNull()?.takeIf { it.versionCode > 0 && it.url.startsWith("http") }

    private fun md5(file: File): String {
        val d = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
