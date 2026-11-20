package com.liuyunli.proxy

import android.content.Context
import org.json.JSONObject

/**
 * 节点配置：单节点，所有字段可在界面编辑并持久化，代码/APK 内不含任何真实地址与凭据。
 * pass 落盘前经 SecretStore 加密（Keystore AES-GCM），读取时透明解密并兼容旧明文。
 */
data class NodeConfig(
    val name: String = ProxyConfig.DEFAULT_NODE_NAME,
    val type: String = "http",
    val host: String = "",
    val port: Int = 0,
    val user: String = "",
    val pass: String = "",
    val tls: Boolean = false,
) {
    fun isConfigured(): Boolean = host.isNotBlank() && port in 1..65535

    /** 脱敏展示：liu***@host:port，不含密码 */
    fun masked(): String {
        if (!isConfigured()) return "未配置节点"
        val who = if (user.isBlank()) "" else user.take(3) + "***@"
        return who + host + ":" + port
    }

    /** 日志用短标识：只到 host，不含账号密码 */
    fun brief(): String = if (isConfigured()) "$type://$host:$port" else "unconfigured"

    fun typeLabel(): String = when {
        type == "socks" -> "SOCKS5"
        tls -> "HTTPS"
        else -> "HTTP"
    }

    /** 旧接口保留：不带加密的 JSON 串（仅供内核构造/日志，勿落盘） */
    fun toJson(): String = toJsonObj().toString()

    /** 导出/导入用的结构化表示（含明文密码，仅内存流转，不落盘） */
    fun toJsonObj(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("host", host)
        put("port", port)
        put("user", user)
        put("pass", pass)
        put("tls", tls)
    }

    companion object {
        private const val PREF = "node_cfg"
        private const val KEY = "current"

        fun fromJsonObj(o: JSONObject): NodeConfig = NodeConfig(
            name = o.optString("name", ProxyConfig.DEFAULT_NODE_NAME),
            type = o.optString("type", "http"),
            host = o.optString("host", ""),
            port = o.optInt("port", 0),
            user = o.optString("user", ""),
            pass = o.optString("pass", ""),
            tls = o.optBoolean("tls", false),
        )

        fun load(ctx: Context): NodeConfig {
            val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            if (raw.isNullOrBlank()) return NodeConfig()
            return runCatching {
                val o = JSONObject(raw)
                val storedPass = o.optString("pass", "")
                val n = fromJsonObj(o).copy(pass = SecretStore.decrypt(storedPass))
                if (n.pass.isNotBlank() && !SecretStore.isEncrypted(storedPass)) {
                    save(ctx, n) // 旧明文自动升级为密文
                }
                n
            }.getOrDefault(NodeConfig())
        }

        fun save(ctx: Context, node: NodeConfig) {
            val o = node.toJsonObj()
            o.put("pass", SecretStore.encrypt(node.pass))
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, o.toString()).apply()
        }
    }
}
