package com.liuyunli.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import io.nekohasekai.libbox.InterfaceUpdateListener
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import java.io.File

class ProxyVpnService : VpnService() {

    @Volatile
    var nodeName: String = ProxyConfig.DEFAULT_NODE_NAME
        private set

    companion object {
        private const val TAG = "MarvisProxy"
        const val ACTION_START = "com.liuyunli.proxy.START"
        const val ACTION_STOP = "com.liuyunli.proxy.STOP"

        /** 配置（节点/规则/网段）变更后原地重载内核，无需用户手动断开再连。 */
        const val ACTION_RELOAD = "com.liuyunli.proxy.RELOAD"
        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        /** 本次连接建立时间戳（毫秒），未连接为 0。 */
        @Volatile
        var connectedAtMs: Long = 0L
            private set

        /** 已连接时长（毫秒）。 */
        fun connectedDurationMs(): Long =
            if (running && connectedAtMs > 0L) System.currentTimeMillis() - connectedAtMs else 0L

        /** (下行字节, 上行字节)，本次连接累计。 */
        fun traffic(): Pair<Long, Long> =
            if (running) TrafficMeter.read() else Pair(0L, 0L)

        /** (下行速率 B/s, 上行速率 B/s)，与通知栏共用同一份采样。 */
        fun rates(): Pair<Long, Long> =
            if (running) TrafficMeter.rates() else Pair(0L, 0L)

    }

    @Volatile private var ruleSetDir: String? = null
    private var server: CommandServer? = null

    // 前台通知每 15 秒刷新一次时长/流量，避免高频刷新耗电
    private val notifHandler = Handler(Looper.getMainLooper())
    private val notifTicker = object : Runnable {
        override fun run() {
            if (!running) return
            updateNotification()
            notifHandler.postDelayed(this, 5_000L)
        }
    }
    private var tun: ParcelFileDescriptor? = null
    private var coreStarted = false
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var ifaceCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var myInterfaceName: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            val base = File(filesDir, "libbox")
            if (!base.exists()) base.mkdirs()
            val setup = SetupOptions()
            setup.setBasePath(base.absolutePath)
            setup.setWorkingPath(base.absolutePath)
            setup.setTempPath(cacheDir.absolutePath)
            setup.setFixAndroidStack(true)
            // 运行时效率：关闭内核 debug 日志（每条连接/DNS 都输出 DEBUG 会造成大量 IO 与 CPU 开销）
            runCatching { setup.setDebug(false) }
            runCatching { setup.setLogMaxLines(500) }
            ruleSetDir = installRuleSets(base)
            Libbox.setup(setup)
            Log.i(TAG, "libbox 初始化完成，版本 ${Libbox.version()}")
        } catch (t: Throwable) {
            lastError = "内核初始化失败: ${t.message}"
            Log.e(TAG, "libbox setup 失败", t)
        }
    }

    /**
     * 首次运行把 assets 内置规则集释放到 working 目录。
     * 已存在（含用户手动更新过的版本）一律不覆盖，避免每次启动把手动更新冲掉。
     */
    private fun installRuleSets(base: File): String? {
        val names = listOf("geosite-cn.srs", "geoip-cn.srs", "geosite-category-ads-all.srs")
        var ok = 0
        for (n in names) {
            val dst = File(base, n)
            if (dst.exists() && dst.length() > 200) {
                ok++
                continue
            }
            try {
                assets.open(n).use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
                ok++
            } catch (t: Throwable) {
                Log.e(TAG, "规则集 $n 释放失败", t)
            }
        }
        if (ok == 0) {
            Log.e(TAG, "规则集全部缺失，退化为全量代理")
            return null
        }
        Log.i(TAG, "规则集已就绪($ok/${names.size}): ${base.absolutePath}")
        return base.absolutePath
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCore()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // 前台通知已存在，直接重载内核即可
                if (coreStarted) reloadCore()
            }
            else -> {
                startAsForeground()
                startCore()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun startCore() {
        if (coreStarted) return
        val nodeConfig = NodeConfig.load(this)
        if (!nodeConfig.isConfigured()) {
            lastError = "未配置代理节点，请先填写节点地址与端口"
            Log.e(TAG, lastError!!)
            return
        }
        nodeName = nodeConfig.name
        lastError = null
        try {
            val srv = CommandServer(Handler(), PlatformImpl(this))
            srv.start()
            val override = OverrideOptions()
            override.setAutoRedirect(false)
            srv.startOrReloadService(
                ProxyConfig.build(
                    nodeConfig,
                    IgnoreList.load(this@ProxyVpnService),
                    ruleSetDir,
                    RulePrefs.adsBlock(this@ProxyVpnService),
                    malwareBlock = RulePrefs.malwareBlock(this@ProxyVpnService),
                    ignoreDomains = IgnoreList.loadDomains(this@ProxyVpnService)
                ),
                override,
            )
            server = srv
            coreStarted = true
            running = true
            connectedAtMs = System.currentTimeMillis()
            TrafficMeter.reset()
            Log.i(TAG, "内核已启动")
            notifHandler.removeCallbacks(notifTicker)
            notifHandler.postDelayed(notifTicker, 1_000L)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "启动失败", t)
            running = false
            stopCore()
            stopSelf()
            return
        }
        updateNotification()
    }

    private fun stopCore() {
        running = false
        coreStarted = false
        connectedAtMs = 0L
        notifHandler.removeCallbacks(notifTicker)
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        runCatching { tun?.close() }
        tun = null
        // 停止后必须撤掉前台通知，否则通知栏会残留"已连接"
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    /** 原地重载内核以应用新配置（节点/规则/网段），无需重新申请 VPN 授权。 */
    private fun reloadCore() {
        Log.i(TAG, "配置变更，重载内核")
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        coreStarted = false
        running = false
        connectedAtMs = 0L
        startAsForeground()
        startCore()
    }

    override fun onRevoke() {
        stopCore()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopCore()
        super.onDestroy()
    }


    fun getMyInterfaceName(): String? = myInterfaceName

    fun setMyInterfaceName(name: String) {
        myInterfaceName = name
        Log.i(TAG, "注册自身接口: " + name)
    }

    fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultInterfaceListener = listener
        pushDefaultInterface()
        if (ifaceCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    pushDefaultInterface()
                }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    pushDefaultInterface()
                }
                override fun onLost(network: Network) {
                    pushDefaultInterface()
                }
            }
            ifaceCallback = cb
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerDefaultNetworkCallback(cb)
            }.onFailure { Log.w(TAG, "注册网络回调失败", it) }
        }
    }

    fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        if (defaultInterfaceListener === listener) {
            defaultInterfaceListener = null
        }
        ifaceCallback?.let {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            ifaceCallback = null
        }
    }

    private fun pushDefaultInterface() {
        val listener = defaultInterfaceListener ?: return
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@runCatching
            val lp = cm.getLinkProperties(network) ?: return@runCatching
            val name = lp.interfaceName ?: return@runCatching
            if (name == myInterfaceName || name.startsWith("tun")) {
                Log.w(TAG, "跳过 TUN 接口作为默认接口: " + name)
                return@runCatching
            }
            var index = 0
            var mtu = 1500
            runCatching {
                java.net.NetworkInterface.getByName(name)?.let {
                    index = it.index
                    mtu = it.mtu
                }
            }
            val expensive = cm.isActiveNetworkMetered
            Log.i(TAG, "默认接口 " + name + " index=" + index + " mtu=" + mtu + " metered=" + expensive)
            listener.updateDefaultInterface(name, index, expensive, false)
        }.onFailure { Log.w(TAG, "推送默认接口失败", it) }
    }

    fun protectFd(fd: Int): Boolean = try {
        val ok = protect(fd)
        Log.i(TAG, "protect($fd) = $ok")
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "protect($fd) 失败", t)
        false
    }

    fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner? = null

    fun establishTun(options: TunOptions): Int {
        val builder = Builder()
        builder.setSession("MarvisProxy")
        // TUN 的 MTU 必须小于物理网卡 MTU（wlan0 通常 1500），
        // 否则 TLS 握手等大包会被分包/丢弃，表现"小包(mDNS/ping)能过、HTTPS 全挂"
        val mtu = if (options.mtu > 1400) 1400 else options.mtu
        builder.setMtu(mtu)
        addAddresses(builder, options.inet4Address)
        runCatching { builder.addRoute("0.0.0.0", 0) }
        // system 栈走内核转发，要求接口上真实存在配置声明的 IPv6 地址，
        // 否则内核启动会报 bind: cannot assign requested address（gvisor 用户态栈不依赖此项）
        val v6Count = runCatching { addAddresses(builder, options.inet6Address) }.getOrDefault(0)
        if (v6Count > 0) {
            runCatching { builder.addRoute("::", 0) }
                .onFailure { Log.w(TAG, "IPv6 路由下发失败: ${it.message}") }
        }
        runCatching {
            val dns = options.dnsServerAddress
            while (dns != null && dns.hasNext()) {
                builder.addDnsServer(dns.next())
            }
        }
        runCatching {
            var hasInclude = false
            val include = options.includePackage
            while (include != null && include.hasNext()) {
                hasInclude = true
                builder.addAllowedApplication(include.next())
            }
            // 未指定分应用时，把本 App 自身排除在 VPN 外：
            // 内核发起的出站连接（连代理、直连）直接走物理网络，杜绝 TUN 自环
            if (!hasInclude) {
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "排除自身失败: ${it.message}") }
            }
        }
        val pfd = builder.establish() ?: throw IllegalStateException("TUN 建立失败，可能未获得授权")
        tun = pfd
        Log.i(TAG, "TUN 已建立 fd=${pfd.fd} mtu=${options.mtu}")
        return pfd.fd
    }

    private fun addAddresses(builder: Builder, iterator: RoutePrefixIterator?): Int {
        if (iterator == null) return 0
        var added = 0
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            runCatching { builder.addAddress(prefix.address(), prefix.prefix()) }
                .onSuccess { added++ }
                .onFailure { Log.w(TAG, "addAddress 失败") }
        }
        return added
    }

    private inner class Handler : CommandServerHandler {
        override fun connectSSHAgent(): Int = -1

        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun serviceReload() {
            Log.i(TAG, "内核请求重载")
        }

        override fun serviceStop() {
            Log.i(TAG, "内核请求停止")
        }

        override fun setSystemProxyEnabled(enabled: Boolean) {
        }

        override fun triggerNativeCrash() {
        }

        override fun writeDebugMessage(message: String) {
            // 运行时效率：只保留告警/错误，INFO 与 DEBUG 丢弃，避免逐行落盘拖慢转发
            val m = message.trim()
            if (m.isEmpty()) return
            if (!m.contains("WARN") && !m.contains("ERROR") && !m.contains("FATAL")) return
            Log.w(TAG, "CORE $m")
            runCatching {
                val f = File(filesDir, "singbox.log")
                if (f.length() > 512 * 1024) f.delete()
                f.appendText(m + "\n")
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理状态",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    /** 通知栏一行：下行速率 上行速率 下行累计 上行累计。 */
    private fun notifText(): String {
        val r = TrafficMeter.rates()
        val t = TrafficMeter.read()
        // 顺序：上行速率 / 下行速率 / 上行统计 / 下行统计，紧凑格式 + 等宽补齐，单行平铺
        return listOf(
            "上行" + notifNum(r.second, true),
            "下行" + notifNum(r.first, true),
            "上传" + notifNum(t.second, false),
            "下载" + notifNum(t.first, false)
        ).joinToString(" ") { padNotifCell(it) }
    }

    /** 通知栏紧凑数字：1.5K / 4.2M / 512B，速率追加 /s。 */
    private fun notifNum(bytes: Long, perSec: Boolean): String {
        val n = when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024L * 1024 -> String.format("%.1fK", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format("%.1fM", bytes / 1048576.0)
            else -> String.format("%.2fG", bytes / 1073741824.0)
        }
        return if (perSec) "$n/s" else n
    }

    /** 按显示宽度补齐到 9（中文记 2 宽），四段视觉上等宽平分。 */
    private fun padNotifCell(s: String): String {
        var w = 0
        for (c in s) {
            w += if (c.code > 0x2E80) 2 else 1
        }
        return if (w >= 9) s else s + " ".repeat(9 - w)
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            running -> "已连接 · ${nodeName}"
            lastError != null -> "启动失败"
            else -> "未连接"
        }
        val text = when {
            running -> notifText()
            lastError != null -> lastError ?: ""
            else -> "点击打开"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
        return builder.build()
    }

    private fun updateNotification() {
        if (running) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
