package com.liuyunli.proxy

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则集更新器（v0.5.8 体验重构版）。
 *
 * 与 0.5.7 的差别：
 * 1) 不再"多镜像 + 代理并发赛跑"。0.5.7 一次开 10 条并发抢同一份文件，代理分支
 *    因 407 必败却要占满 38s 超时，导致整体又慢又乱。现在改为按"最快源优先"的
 *    顺序串行回退，每个源短超时，命中即走。
 * 2) 每一项单独产出结果（ItemResult），页面按项展示：成功/失败、大小、耗时、来源。
 * 3) 支持取消：updateAll 每步检查 cancelled，页面按钮可从"取消"恢复。
 * 4) 代理只在 proxy 通道或直连全灭时兜底一次，且走手写 CONNECT 隧道（ProxyDownloader），
 *    修掉 HttpURLConnection 不回应 407 的硬伤。
 *
 * 边界：只有手动触发，不做订阅、不做定时。
 */
object RuleSetUpdater {
    private const val TAG = "RuleSetUpdater"
    private const val PREF = "rule_set_meta"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val MAX_RULES = 200_000

    /** 单源连接超时（秒）——DNS/拒绝连接通常秒级失败，不需要等太久 */
    private const val CONNECT_TIMEOUT = 4
    /** 单源读取超时（秒）——小文件 1~2s 下完，给小水管留余量 */
    private const val READ_TIMEOUT = 15
    /** 单个规则集的总时间预算（秒），超了直接判该项失败，不拖累后面 */
    private const val ITEM_BUDGET = 14

    private const val RAW_GEOSITE =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"
    private const val RAW_GEOIP =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs"
    private const val RAW_ADS =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"

    private const val VIA_DIRECT = "direct"
    private const val VIA_PROXY = "proxy"

    @Volatile private var cancelled = false

    /** 供页面"取消"按钮调用。 */
    fun requestCancel() {
        cancelled = true
    }

    fun isRunning(): Boolean = !cancelled && running

    @Volatile private var running = false

    /** 单项更新结果：页面直接按行渲染，不再是一坨汇总文案。 */
    data class ItemResult(
        val key: String,
        val label: String,
        val ok: Boolean,
        val detail: String,
        val bytes: Long = 0L,
        val elapsedMs: Long = 0L,
        val via: String = "",
    )

    private class Attempt(val url: String, val via: String)

    private class Job(
        val key: String,
        val name: String,
        val label: String,
        val builtin: List<String>,
    )

    fun libboxDir(context: Context): File =
        File(context.filesDir, "libbox").apply { if (!exists()) mkdirs() }

    fun malwareFile(context: Context): File = File(libboxDir(context), ProxyConfig.RULE_SET_MALWARE)

    fun lastUpdate(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0L)

    fun hasMalwareList(context: Context): Boolean =
        malwareFile(context).let { it.exists() && it.length() > 2000 }

    fun summary(context: Context): String {
        val t = lastUpdate(context)
        if (t <= 0L) return "内置规则集，未手动更新过"
        val s = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t))
        val extra = if (hasMalwareList(context)) "，恶意域名库已就绪" else ""
        return "上次更新 $s$extra"
    }

    private fun mirrors(raw: String, jsdelivrPath: String): List<String> = listOf(
        // jsdelivr 直连最快（实测 0.5~0.7s），放第一梯队
        "https://fastly.jsdelivr.net/gh/$jsdelivrPath",
        "https://cdn.jsdelivr.net/gh/$jsdelivrPath",
        raw,
        "https://ghfast.top/$raw",
        "https://gh-proxy.com/$raw",
    )

    private val GEOSITE_URLS = mirrors(RAW_GEOSITE, "SagerNet/sing-geosite@rule-set/geosite-cn.srs")
    private val GEOIP_URLS = mirrors(RAW_GEOIP, "SagerNet/sing-geoip@rule-set/geoip-cn.srs")
    private val ADS_URLS = mirrors(RAW_ADS, "SagerNet/sing-geosite@rule-set/geosite-category-ads-all.srs")

    private val MALWARE_URLS = listOf(
        "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-domains.txt",
        "https://malware-filter.gitlab.io/malware-filter/phishing-filter-domains.txt",
        "https://openphish.com/feed.txt",
    )

    /**
     * 按通道排出"先用哪些、后用哪些"：
     * probe（自动）—— 先直连各镜像，全灭才用代理兜底
     * direct       —— 只直连
     * proxy        —— 代理优先，代理全灭降级直连
     */
    private fun plan(urls: List<String>, channel: String, node: NodeConfig?): List<Attempt> {
        val list = ArrayList<Attempt>(urls.size + 2)
        val canProxy = node != null && node.isConfigured()
        if (channel == "proxy" && canProxy) urls.forEach { list.add(Attempt(it, VIA_PROXY)) }
        urls.forEach { list.add(Attempt(it, VIA_DIRECT)) }
        if (channel != "direct" && canProxy) urls.forEach { list.add(Attempt(it, VIA_PROXY)) }
        return list
    }

    /** 兼容旧调用：返回一句汇总文本，全部失败时抛异常。 */
    fun updateAll(context: Context): String {
        val results = updateAllDetailed(context, null)
        val ok = results.count { it.ok }
        val failed = results.filter { !it.ok }
        if (ok == 0) throw IllegalStateException("全部源不可用，检查网络或自定义源")
        val sb = StringBuilder("成功 $ok/${results.size}")
        if (failed.isNotEmpty()) sb.append("，失败：").append(failed.joinToString("、") { it.label })
        results.firstOrNull { it.key == "malware" && it.ok }?.let { sb.append("，恶意域名 ").append(it.detail) }
        return sb.toString()
    }

    /**
     * 逐项下载，每完成一项回调一次进度，便于页面边下边显示。必须在后台线程调用。
     */
    fun updateAllDetailed(
        context: Context,
        onProgress: ((ItemResult) -> Unit)?,
    ): List<ItemResult> {
        cancelled = false
        running = true
        val out = ArrayList<ItemResult>(4)
        try {
            val dir = libboxDir(context)
            val custom = parseCustom(context)
            val channel = RulePrefs.updateChannel(context)
            val node = NodeConfig.load(context)
            if (channel == "proxy" && !node.isConfigured()) {
                running = false
                Log.w(TAG, "代理通道不可用：未配置节点")
                return listOf(ItemResult("node", "代理节点", false, "未配置节点，请先在节点配置中填写代理地址", via = "—"))
            }

            fun emit(r: ItemResult) {
                out.add(r)
                onProgress?.invoke(r)
            }

            val jobs = listOf(
                Job("geosite", "geosite-cn.srs", "国内域名规则", GEOSITE_URLS),
                Job("geoip", "geoip-cn.srs", "国内 IP 规则", GEOIP_URLS),
                Job("ads", ProxyConfig.RULE_SET_ADS, "广告拦截规则", ADS_URLS),
            )
            for (job in jobs) {
                if (cancelled) break
                val urls = (custom[job.key] ?: emptyList()) + job.builtin
                emit(downloadOne(File(dir, job.name), job.key, job.label, urls, channel, node, true))
            }

            if (!cancelled) {
                emit(downloadMalware(context, custom, channel, node))
            }

            dir.listFiles { f -> f.name.endsWith(".part") || f.name.endsWith(".tmp") }?.forEach { it.delete() }

            if (out.any { it.ok }) {
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            }
            Log.i(TAG, "更新结束：" + out.joinToString(" | ") { "${it.key}=${if (it.ok) "ok" else "fail"}(${it.detail})" })
            return out
        } finally {
            running = false
        }
    }

    private fun downloadOne(
        dst: File,
        key: String,
        label: String,
        urls: List<String>,
        channel: String,
        node: NodeConfig,
        checkSrs: Boolean,
    ): ItemResult {
        val start = System.currentTimeMillis()
        val deadline = start + ITEM_BUDGET * 1000L
        val tmp = File(dst.parentFile, dst.name + ".part")
        var lastErr = "无可用源"
        for (a in plan(urls, channel, node)) {
            if (cancelled) return ItemResult(key, label, false, "已取消", via = "—")
            if (System.currentTimeMillis() > deadline) {
                lastErr = "超时（${ITEM_BUDGET}s 内未完成）"
                break
            }
            val t0 = System.currentTimeMillis()
            var viaName = if (a.via == VIA_PROXY) "代理" else "直连"
            try {
                tmp.delete()
                val via = if (a.via == VIA_PROXY) {
                    ProxyDownloader.fetchToFile(a.url, node, tmp, 200, checkSrs)
                } else {
                    directTo(tmp, a.url, checkSrs)
                    "直连"
                }
                viaName = via
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    if (!tmp.renameTo(dst)) throw IllegalStateException("写入失败")
                }
                val ms = System.currentTimeMillis() - t0
                val host = a.url.substringAfter("://").substringBefore('/').removePrefix("www.")
                Log.i(TAG, "[$key] 成功 $via ${a.url} ${ms}ms")
                val tag = if (a.via == VIA_PROXY) " · 代理" else ""
                return ItemResult(key, label, true, "${kb(dst.length())} · ${ms}ms · $host$tag", dst.length(), ms, via)
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "[$key] 源不可用 $viaName(${a.via}) ${a.url}: $lastErr")
                tmp.delete()
            }
        }
        return ItemResult(key, label, false, "失败：$lastErr", elapsedMs = System.currentTimeMillis() - start)
    }

    private fun downloadMalware(
        context: Context,
        custom: Map<String, List<String>>,
        channel: String,
        node: NodeConfig,
    ): ItemResult {
        val label = "恶意/钓鱼清单"
        val start = System.currentTimeMillis()
        val extraCustom = custom.filterKeys { it != "geosite" && it != "geoip" && it != "ads" }.values.flatten()
        val urls = ((custom["malware"] ?: emptyList()) + extraCustom + MALWARE_URLS).distinct()

        // 清单是纯文本小文件，多源并行拉取再合并，避免串行时被最慢的上游拖到 20s。
        val lines = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val okSources = java.util.concurrent.atomic.AtomicInteger(0)
        val exec = java.util.concurrent.Executors.newFixedThreadPool(minOf(3, urls.size.coerceAtLeast(1)))
        val futures = urls.map { url ->
            exec.submit {
                if (cancelled) return@submit
                val directOk = try {
                    val text = fetchTextDirect(url)
                    if (text.length > 200) okSources.incrementAndGet()
                    for (raw in text.lineSequence()) {
                        if (lines.size >= MAX_RULES) break
                        toDomainRule(raw)?.let { lines.add(it) }
                    }
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "[malware] 直连源不可用 $url: ${t.message}")
                    false
                }
                // 直连失败且允许多通道：单项走代理补一次
                if (!directOk && !cancelled && channel != "direct" && node.host.isNotBlank()) {
                    try {
                        val text = ProxyDownloader.fetchText(url, node)
                        if (text.length > 200) okSources.incrementAndGet()
                        for (raw in text.lineSequence()) {
                            if (lines.size >= MAX_RULES) break
                            toDomainRule(raw)?.let { lines.add(it) }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "[malware] 代理源不可用 $url: ${t.message}")
                    }
                }
            }
        }
        // 拿到主源就先收工：6s 内已有关键词库就直接用，最多等到 12s 兜底，
        // 不为了最慢的那个上游把整轮更新拖到十几秒。
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < 12000) {
            if (cancelled) break
            if (futures.all { it.isDone }) break
            // 至少两个主源（urlhaus / phishing）落地才提前收工，避免为了快而丢主清单
            if (okSources.get() >= 2 && System.currentTimeMillis() - waitStart >= 4000) break
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                break
            }
        }
        exec.shutdownNow()

        if (lines.size > 1000) {
            val dst = malwareFile(context)
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.writeText(lines.joinToString("\n"))
            dst.delete()
            if (!tmp.renameTo(dst)) return ItemResult("malware", label, false, "失败：写入失败")
            val ms = System.currentTimeMillis() - start
            return ItemResult(
                "malware", label, true,
                "${lines.size} 条 · ${kb(dst.length())} · ${ms}ms · ${okSources.get()} 个源",
                dst.length(), ms, if (okSources.get() > 0) "直连" else "—"
            )
        }
        val msg = if (cancelled) "已取消" else "失败：全部清单源不可用"
        return ItemResult("malware", label, false, msg, elapsedMs = System.currentTimeMillis() - start)
    }

    private fun kb(n: Long): String =
        if (n >= 1024 * 1024) String.format(Locale.CHINA, "%.1fMB", n / 1048576.0)
        else "${n / 1024}KB"

    private fun parseCustom(context: Context): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (raw in RulePrefs.customSources(context).lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf('=')
            val key = if (i > 0) line.substring(0, i).trim().lowercase(Locale.ROOT) else "malware"
            val url = if (i > 0) line.substring(i + 1).trim() else line
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            map.getOrPut(key) { ArrayList() }.add(url)
        }
        return map
    }

    private fun toDomainRule(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("#") || line.startsWith(";") || line.startsWith("//") || line.startsWith("!")) return null
        if (line.startsWith("domain_suffix:")) return line
        if (line.startsWith("0.0.0.0 ")) line = line.substringAfter(' ').trim()
        if (line.startsWith("127.0.0.1 ")) line = line.substringAfter(' ').trim()
        if (line.startsWith("http://") || line.startsWith("https://")) {
            line = line.substringAfter("://").substringBefore('/').substringBefore('?')
        }
        line = line.substringBefore(' ').trimStart('.').trimEnd('.')
        if (line.isEmpty() || !line.contains('.')) return null
        if (line.length > 100) return null
        return "domain_suffix:$line"
    }

    private fun conn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT * 1000
            readTimeout = READ_TIMEOUT * 1000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MarvisProxy/1.0")
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun fetchTextDirect(url: String, readSec: Int = 8): String {
        val c = conn(url)
        c.readTimeout = readSec * 1000
        try {
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun directTo(tmp: File, url: String, checkSrs: Boolean) {
        val c = conn(url)
        try {
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 32 * 1024) } }
            if (tmp.length() < 200) throw IllegalStateException("内容异常(${tmp.length()}B)")
            if (checkSrs) {
                val head = ByteArray(3)
                tmp.inputStream().use { it.read(head) }
                if (head[0] != 'S'.code.toByte() || head[1] != 'R'.code.toByte() || head[2] != 'S'.code.toByte()) {
                    throw IllegalStateException("非 SRS 文件")
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
