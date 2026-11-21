package com.liuyunli.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v3 手机侧分流配置：一个 http 出站 + tun 入站 + 客户端本地规则集分流。
 *
 * 与 v2（fakeip 全量丢代理）的区别：
 * 1. 不再使用 fakeip，DNS 返回真实 IP，由手机端自行判断国内外。
 * 2. DNS 分流：命中 geosite-cn / *.cn 的域名走本地 UDP DNS（223.5.5.5），
 *    其余境外域名走 DoH（1.1.1.1，detour=proxy）避免污染。
 * 3. 路由分流：域名嗅探后，geosite-cn（国内域名）/ geoip-cn（国内 IP）直连，
 *    其余走代理。这样 4G 出门时访问国内网站完全不经家宽中继。
 * 4. 规则集使用随 APK 打包的 sing-box binary rule-set（assets 中的 .srs），
 *    首次启动释放到 files/libbox 目录，本地匹配、零网络开销。
 *
 * ruleSetDir 为 null 时（规则集缺失）退化为全量走代理，保证内核仍可启动。
 */
object ProxyConfig {
    // 代码与 APK 内不预置任何真实节点地址、账号或密码。
    // 真实节点一律由 NodeConfig 从本地配置读取（界面填写 / 导入），保证可脱敏分发。
    const val DEFAULT_NODE_NAME = "家宽代理"

    private const val RULE_SET_GEOSITE = "geosite-cn.srs"
    private const val RULE_SET_GEOIP = "geoip-cn.srs"
    const val RULE_SET_ADS = "geosite-category-ads-all.srs"
    /** 恶意软件 / 钓鱼域名列表（纯文本，每行 domain_suffix:example.com，由 RuleSetUpdater 下载） */
    const val RULE_SET_MALWARE = "malware-domains.txt"

    /** 内联域名上限：sing-box 直接解析 domain_suffix 数组，避免依赖 rule-set 的 source 格式 */
    private const val MAX_INLINE_DOMAINS = 50_000

    fun build(
        node: NodeConfig,
        ignoreCidrs: List<String> = IgnoreList.DEFAULTS,
        ruleSetDir: String? = null,
        adsBlock: Boolean = false,
        malwareBlock: Boolean = false,
        ignoreDomains: List<String> = emptyList()
    ): String {
        val root = JSONObject()
        val hasRuleSet = !ruleSetDir.isNullOrBlank()
        // 广告拦截级：开关打开且规则集文件真实存在才启用，缺失自动降级
        val hasAds = adsBlock && hasRuleSet &&
            java.io.File(ruleSetDir!!, RULE_SET_ADS).let { it.exists() && it.length() > 200 }
        // 恶意软件拦截级：同理，域名库缺失自动降级，不影响正常上网。
        // 清单是文本域名表，直接内联成 domain_suffix 规则，不走 rule-set（source 格式只认 JSON，容易踩格式坑）。
        val malwareDomains = if (malwareBlock && hasRuleSet) {
            parseDomains(File(ruleSetDir!!, RULE_SET_MALWARE))
        } else emptyList()
        val hasMalware = malwareDomains.size >= 100

        // ---------- DNS：国内直连解析，境外 DoH 经代理 ----------
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                // 国内 DNS：直连，返回真实国内 IP
                put(JSONObject().apply {
                    put("tag", "dns-local")
                    put("type", "udp")
                    put("server", "223.5.5.5")
                })
                put(JSONObject().apply {
                    put("tag", "dns-bak")
                    put("type", "udp")
                    put("server", "119.29.29.29")
                })
                // 境外 DNS：DoH 经代理出站，避免国内 DNS 污染
                put(JSONObject().apply {
                    put("tag", "dns-proxy")
                    put("type", "https")
                    put("server", "1.1.1.1")
                    put("path", "/dns-query")
                    put("detour", "proxy")
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", "cloudflare-dns.com")
                    })
                })
            })
            put("rules", JSONArray().apply {
                // 国内域名（含 .cn）走本地 DNS
                if (hasRuleSet) {
                    put(JSONObject().apply {
                        put("rule_set", "geosite-cn")
                        put("server", "dns-local")
                    })
                }
                put(JSONObject().apply {
                    put("domain_suffix", JSONArray().put(".cn"))
                    put("server", "dns-local")
                })
            })
            put("final", "dns-proxy")
            put("strategy", "ipv4_only")
            put("independent_cache", true)
        })

        // ---------- TUN 入站 ----------
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                put("mtu", 1400)   // 与 VpnService.Builder 的 MTU 保持一致
                put("auto_route", true)
                put("strict_route", false)
                put("stack", "gvisor")   // system 栈需 netlink 路由权限，本机 SELinux 拒绝 bind netlink_route_socket，导致 TCP 全断
            })
        })

        // ---------- 实验项：缓存持久化，减少重复 DNS 解析 ----------
        root.put("experimental", JSONObject().apply {
            put("cache_file", JSONObject().apply {
                put("enabled", true)
                put("store_fakeip", false)
            })
        })

        // ---------- 出站 ----------
        root.put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
            put(JSONObject().apply {
                put("type", if (node.type == "socks") "socks" else "http")
                put("tag", "proxy")
                put("server", node.host)
                put("server_port", node.port)
                put("domain_resolver", "dns-local")
                if (node.user.isNotBlank()) put("username", node.user)
                if (node.pass.isNotBlank()) put("password", node.pass)
                if (node.tls && node.type != "socks") {
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", node.host)
                    })
                }
            })
        })

        // ---------- 路由 ----------
        root.put("route", JSONObject().apply {
            // 本地规则集
            if (hasRuleSet) {
                put("rule_set", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "geosite-cn")
                        put("type", "local")
                        put("format", "binary")
                        put("path", "$ruleSetDir/$RULE_SET_GEOSITE")
                    })
                    put(JSONObject().apply {
                        put("tag", "geoip-cn")
                        put("type", "local")
                        put("format", "binary")
                        put("path", "$ruleSetDir/$RULE_SET_GEOIP")
                    })
                    if (hasAds) {
                        put(JSONObject().apply {
                            put("tag", "geosite-ads")
                            put("type", "local")
                            put("format", "binary")
                            put("path", "$ruleSetDir/$RULE_SET_ADS")
                        })
                    }
                })
            }
            put("rules", JSONArray().apply {
                // 1) 劫持 tun 内所有 DNS 查询交给 dns 模块（必须第一条）
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                // 2) 域名嗅探：后续规则可用域名匹配，HTTP 出站也能还原域名
                put(JSONObject().put("action", "sniff"))
                // 3) QUIC(UDP 443) 直接拒绝：HTTP 代理不支持 UDP
                put(JSONObject().apply {
                    put("network", "udp")
                    put("port", 443)
                    put("action", "reject")
                })
                // 4) 用户可编辑的本地忽略列表 -> 直连
                if (ignoreCidrs.isNotEmpty()) {
                    put(JSONObject().apply {
                        put("ip_cidr", JSONArray().apply {
                            ignoreCidrs.forEach { put(it) }
                        })
                        put("outbound", "direct")
                    })
                }
                // 4.1) 用户自定义忽略域名（含全部子域）-> 直连
                if (ignoreDomains.isNotEmpty()) {
                    put(JSONObject().apply {
                        put("domain_suffix", JSONArray().apply {
                            // *.a.com -> .a.com（仅子域）；a.com 保持匹配自身及子域
                            ignoreDomains.forEach { d ->
                                put(if (d.startsWith("*.")) "." + d.removePrefix("*.") else d)
                            }
                        })
                        put("outbound", "direct")
                    })
                }
                // 5) 其它私有地址 -> 直连
                put(JSONObject().apply {
                    put("ip_is_private", true)
                    put("outbound", "direct")
                })
                // 5.5) 广告域名 -> 直接拒绝（页面可关）
                if (hasAds) {
                    put(JSONObject().apply {
                        put("rule_set", "geosite-ads")
                        put("action", "reject")
                    })
                }
                // 5.6) 恶意软件 / 钓鱼域名 -> 直接拒绝（页面可关）
                if (hasMalware) {
                    put(JSONObject().apply {
                        put("domain_suffix", JSONArray(malwareDomains))
                        put("action", "reject")
                    })
                }
                // 6) 国内域名 / 国内 IP -> 直连（手机侧分流核心）
                // 6.0) 一切 .cn 域名直连（独立于规则集文件，缺失时也生效）
                put(JSONObject().apply {
                    put("domain_suffix", JSONArray().put(".cn"))
                    put("outbound", "direct")
                })
                if (hasRuleSet) {
                    put(JSONObject().apply {
                        put("rule_set", "geosite-cn")
                        put("outbound", "direct")
                    })
                    put(JSONObject().apply {
                        put("rule_set", "geoip-cn")
                        put("outbound", "direct")
                    })
                }
                // 7) 其它 UDP 一律直连：避免被 HTTP 代理黑洞
                put(JSONObject().apply {
                    put("network", "udp")
                    put("outbound", "direct")
                })
            })
            put("final", "proxy")
            put("auto_detect_interface", true)
            put("default_domain_resolver", "dns-local")
        })

        return root.toString()
    }

    /**
     * 解析恶意域名清单：兼容 domain_suffix:x / 裸域名 / hosts 行，
     * 任何脏行（HTML、JSON、注释）直接跳过，保证生成的 configuration.json 永远合法。
     */
    private fun parseDomains(file: File): List<String> {
        if (!file.exists() || file.length() < 100) return emptyList()
        val out = LinkedHashSet<String>()
        try {
            file.bufferedReader().useLines { seq ->
                for (raw in seq) {
                    if (out.size >= MAX_INLINE_DOMAINS) break
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") ||
                        line.startsWith("//") || line.startsWith("!") || line.startsWith("{")
                    ) continue
                    val host = (if (line.startsWith("domain_suffix:")) line.substringAfter(':')
                    else line.substringBefore(' ')).trim().trimStart('.').trimEnd('.')
                    if (host.isEmpty() || host.length > 253 || !host.contains('.')) continue
                    if (host.any { it.isWhitespace() || it == '"' || it == ',' || it == ':' }) continue
                    out.add(host)
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return ArrayList(out)
    }
}
