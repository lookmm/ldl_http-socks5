package com.liuyunli.proxy

import android.content.Context

/** 分流规则开关的持久化（只存用户可见的两个开关，保持极简）。 */
object RulePrefs {
    private const val PREF = "rule_prefs"
    private const val KEY_ADS = "ads_block"
    private const val KEY_MALWARE = "malware_block"
    private const val KEY_AUTO = "auto_connect"

    fun adsBlock(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ADS, false)

    fun setAdsBlock(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADS, on).apply()
    }
    /** 恶意软件 / 钓鱼域名拦截：命中恶意域名列表直接拒绝。 */
    fun malwareBlock(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_MALWARE, false)

    fun setMalwareBlock(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_MALWARE, on).apply()
    }

    private const val KEY_CUSTOM_SRC = "custom_sources"

    /** 自动连接：勾选后 App 启动 / 手机开机自动连（需 VPN 授权仍有效）。 */
    fun autoConnect(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

    fun setAutoConnect(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    }

    /** 用户自定义更新源：每行 key=URL，key 为 geosite / geoip / ads / malware，可重复同名。 */
    fun customSources(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_CUSTOM_SRC, "") ?: ""

    fun setCustomSources(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_SRC, value).apply()
    }

    private const val KEY_UPDATE_CHANNEL = "update_channel"

    /**
     * 规则集更新走的网络通道：
     * auto   —— 直连镜像与自建节点代理并发赛跑，谁先下完用谁（默认）
     * direct —— 只走物理网络直连（多镜像竞速）
     * proxy  —— 只经自建节点代理出站（GFW 环境下最稳）
     */
    fun updateChannel(context: Context): String {
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_UPDATE_CHANNEL, "auto") ?: "auto"
        return if (v == "direct" || v == "proxy") v else "auto"
    }

    fun setUpdateChannel(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_UPDATE_CHANNEL, value).apply()
    }
}
