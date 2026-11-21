package com.liuyunli.proxy

import android.content.Context

/**
 * 本地忽略列表：命中这些 CIDR 的流量一律直连，不进代理。
 * 默认值含用户两个爱快 LAN 段 + 标准私有地址段，用户可在主页增删。
 */
object IgnoreList {

    private const val PREF = "marvis_proxy"
    private const val KEY = "ignore_cidrs"

    /** 默认忽略：两个爱快内网段（192.168.58.0/24、8.8.6.0/24）+ 通用私有段 */
    val DEFAULTS = listOf(
        "192.168.58.0/24",
        "8.8.6.0/24",
        "192.168.0.0/16",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "169.254.0.0/16",
        "100.64.0.0/10"
    )

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, list.joinToString("\n"))
            .apply()
    }

    fun add(context: Context, cidr: String): Boolean {
        val value = normalize(cidr) ?: return false
        val current = load(context).toMutableList()
        if (current.contains(value)) return false
        current.add(value)
        save(context, current)
        return true
    }

    fun remove(context: Context, cidr: String) {
        val current = load(context).toMutableList()
        current.remove(cidr)
        save(context, current)
    }

    fun reset(context: Context) {
        save(context, DEFAULTS)
    }

    /** 接受 a.b.c.d/n 或裸 IP（自动补 /32），容忍空格 */
    private fun normalize(input: String): String? {
        val text = input.trim().replace(" ", "")
        val parts = text.split("/")
        if (parts.size > 2) return null
        val ip = parts[0]
        val prefix = if (parts.size == 2) (parts[1].toIntOrNull() ?: return null) else 32
        if (prefix !in 0..32) return null
        val octets = ip.split(".")
        if (octets.size != 4) return null
        for (octet in octets) {
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
        }
        return "$ip/$prefix"
    }

    // ---------------- 本地忽略域名（直连） ----------------
    private const val KEY_DOMAINS = "ignore_domains"

    fun loadDomains(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DOMAINS, null) ?: return emptyList()
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveDomains(context: Context, list: List<String>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOMAINS, list.joinToString("\n")).apply()
    }

    fun addDomain(context: Context, input: String): Boolean {
        val d = normalizeDomain(input) ?: return false
        val cur = loadDomains(context).toMutableList()
        if (cur.any { it.equals(d, true) }) return false
        cur.add(d)
        saveDomains(context, cur)
        return true
    }

    fun removeDomain(context: Context, domain: String) {
        saveDomains(context, loadDomains(context).filter { !it.equals(domain, true) })
    }

    fun resetDomains(context: Context) {
        saveDomains(context, emptyList())
    }

    /** 接受 example.com / .example.com / *.example.com / localhost / https://a.b.com/path；通配保留为 *.example.com */
    fun normalizeDomain(input: String): String? {
        var t = input.trim().lowercase()
        t = t.removePrefix("http://").removePrefix("https://")
        t = t.substringBefore('/').substringBefore(':')
        if (t == "localhost") return "localhost"
        val wildcard = t.startsWith("*.")
        t = t.removePrefix("*.").removePrefix(".")
        if (t.isEmpty() || !t.contains('.')) return null
        if (t.contains("..")) return null
        if (!Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$").matches(t)) return null
        return if (wildcard) "*.$t" else t
    }
}
