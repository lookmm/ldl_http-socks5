package com.liuyunli.proxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// 首页 GitHub 入口
private const val GITHUB_REPO = "https://github.com/lookmm/ldl_http-socks5"

class MainActivity : ComponentActivity() {

    companion object {
        // 页面是否可见，用于把后台轮询降频
        @Volatile
        var uiVisible: Boolean = false
    }

    override fun onResume() {
        super.onResume()
        uiVisible = true
    }

    override fun onPause() {
        super.onPause()
        uiVisible = false
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startProxy()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        // 勾选了自动连接：App 启动即自动连，VPN 授权失效则只提示、不弹窗
        var autoHint: String? = null
        if (RulePrefs.autoConnect(this) && !ProxyVpnService.running) {
            if (VpnService.prepare(this) == null) {
                startProxy()
            } else {
                autoHint = "自动连接已开启，但 VPN 授权已失效，请手动启动一次并授权"
            }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(
                        onToggle = { toggleProxy() },
                        onReload = { reloadProxy() },
                        initialHint = autoHint,
                    )
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toggleProxy() {
        if (ProxyVpnService.running) {
            stopProxy()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            startProxy()
        }
    }

    private fun startProxy() {
        val intent = Intent(this, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopProxy() {
        val intent = Intent(this, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_STOP)
        startService(intent)
    }

    /** 已在连接中时，配置变更后原地重载内核，无需用户手动断开重连。 */
    private fun reloadProxy() {
        if (!ProxyVpnService.running) return
        val intent = Intent(this, ProxyVpnService::class.java)
            .setAction(ProxyVpnService.ACTION_RELOAD)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
private fun App(onToggle: () -> Unit, onReload: () -> Unit, initialHint: String? = null) {
    val context = LocalContext.current
    val pages = listOf("连接", "节点", "规则")
    var page by remember { mutableStateOf(0) }
    var inSettings by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf(ProxyVpnService.running) }
    var error by remember { mutableStateOf(ProxyVpnService.lastError) }
    var node by remember { mutableStateOf(NodeConfig.load(context)) }
    var showPass by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    var ignoreList by remember { mutableStateOf(IgnoreList.load(context)) }
    var ignoreDomains by remember { mutableStateOf(IgnoreList.loadDomains(context)) }
    var adsBlock by remember { mutableStateOf(RulePrefs.adsBlock(context)) }
    var malwareBlock by remember { mutableStateOf(RulePrefs.malwareBlock(context)) }
    var ruleSetInfo by remember { mutableStateOf(RuleSetUpdater.summary(context)) }
    var updateChannel by remember { mutableStateOf(RulePrefs.updateChannel(context)) }
    // 通道处只显示别名「当前代理」，真实节点由下载时 NodeConfig.load 实时读取
    val updateNodeText = "当前代理"
    val updateNodeReady = remember(updateChannel, node) { node.isConfigured() }
    var updating by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    val updateResults = remember { mutableStateListOf<RuleSetUpdater.ItemResult>() }
    var showSrcEdit by remember { mutableStateOf(false) }
    var customSrc by remember { mutableStateOf(RulePrefs.customSources(context)) }

    val scope = rememberCoroutineScope()
    var newVersion by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var dlDone by remember { mutableStateOf(0L) }
    var dlTotal by remember { mutableStateOf(-1L) }
    var dlStart by remember { mutableStateOf(0L) }

    var autoConnect by remember { mutableStateOf(RulePrefs.autoConnect(context)) }
    var hint by remember { mutableStateOf<String?>(initialHint) }

    // 顶部提示只做短暂反馈：8 秒后自动消退，避免切到别的页面仍一直挂着
    LaunchedEffect(hint) {
        val h = hint ?: return@LaunchedEffect
        delay(8000)
        if (hint == h) hint = null
    }

    // 启动静默查一次新版本（失败静默，不打扰）
    LaunchedEffect(Unit) {
        val r = runCatching { withContext(Dispatchers.IO) { UpdateChecker.check(context) } }
        r.onSuccess { i ->
            if (i.versionCode > UpdateChecker.currentCode(context)) newVersion = i
        }
    }
    var qrText by remember { mutableStateOf<String?>(null) }

    var durationMs by remember { mutableStateOf(0L) }
    var rxBytes by remember { mutableStateOf(0L) }
    var txBytes by remember { mutableStateOf(0L) }
    var rxRate by remember { mutableStateOf(0L) }
    var txRate by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            if (MainActivity.uiVisible) {
                running = ProxyVpnService.running
                error = ProxyVpnService.lastError
                durationMs = ProxyVpnService.connectedDurationMs()
                val t = ProxyVpnService.traffic()
                rxBytes = t.first
                txBytes = t.second
                val r = ProxyVpnService.rates()
                rxRate = r.first
                txRate = r.second
                delay(1000)
            } else {
                delay(3000)
            }
        }
    }

    // 配置变更后的统一处理：连接中直接重载生效，未连接则提示下次启动生效
    fun applyChange(msg: String) {
        if (running) {
            onReload()
            hint = "$msg，正在重连生效"
        } else {
            hint = "$msg，启动后生效"
        }
    }

    // 扫码导入：相机页只负责扫出 LYVPN1: 串，解析落盘仍走同一套 ConfigTransfer
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            val n = r.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let { ConfigTransfer.parse(it) }
            if (n == null) {
                hint = "扫到的不是节点配置二维码"
            } else {
                NodeConfig.save(context, n)
                node = n
                qrText = null
                applyChange("节点配置已导入：" + n.masked())
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部标题栏：右上角「设置」，设置页内变为「返回」
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (inSettings) "设置" else pages[page],
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(onClick = { inSettings = !inSettings }) {
                Text(
                    when {
                        inSettings -> "返回"
                        page == 1 -> "导入导出"
                        else -> "设置"
                    }
                )
            }
        }

        if (hint != null) {
            Text(
                text = hint ?: "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                inSettings -> SettingsPage(
                    node = node,
                    autoConnect = autoConnect,
                    onAutoConnect = { on ->
                        autoConnect = on
                        RulePrefs.setAutoConnect(context, on)
                        hint = if (on) "已开启自动连接：下次打开 App 或手机重启后自动连"
                        else "已关闭自动连接：改为手动启动"
                    },
                    qrText = qrText,
                    onQrToggle = {
                        if (qrText == null) {
                            if (!node.isConfigured()) {
                                hint = "请先配置节点再导出"
                            } else {
                                qrText = ConfigTransfer.exportText(node)
                                hint = "二维码含明文密码，仅自己扫"
                            }
                        } else {
                            qrText = null
                        }
                    },
                    onCopy = {
                        if (!node.isConfigured()) {
                            hint = "请先配置节点再导出"
                        } else {
                            ConfigTransfer.copy(context, ConfigTransfer.exportText(node))
                            hint = "配置已复制到剪贴板（含明文密码，别外发）"
                        }
                    },
                    onScan = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) },
                    onImport = {
                        val raw = ConfigTransfer.readClipboard(context)
                        val n = raw?.let { ConfigTransfer.parse(it) }
                        if (n == null) {
                            hint = "剪贴板里没有可识别的节点配置"
                        } else {
                            NodeConfig.save(context, n)
                            node = n
                            qrText = null
                            applyChange("节点配置已导入：${n.masked()}")
                        }
                    },
                )
                page == 0 -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (running) "已连接" else "未连接",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            NodeRow("已连接时长", if (running) fmtDuration(durationMs) else "—")
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "上行 " + if (running) fmtTiny(txRate, true) else "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "下行 " + if (running) fmtTiny(rxRate, true) else "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "上传 " + if (running) fmtTiny(txBytes) else "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "下载 " + if (running) fmtTiny(rxBytes) else "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Text(
                        text = if (node.isConfigured()) "当前节点：" + node.name.ifBlank { node.masked() } + "（" + node.typeLabel() + "）"
                        else "未配置节点，请到「节点」页填写",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )

                    if (!running && error != null) {
                        var errExpanded by remember { mutableStateOf(false) }
                        val msg = error ?: ""
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                                .clickable { errExpanded = !errExpanded }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(16.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "!",
                                        color = MaterialTheme.colorScheme.onError,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "连接失败",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (msg.length > 40) {
                                    Text(
                                        text = if (errExpanded) "收起" else "详情",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            Text(
                                text = msg.ifBlank { "未知原因，请检查节点配置后重试" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = if (errExpanded) 12 else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                            )
                        }
                    }

                    Button(
                        onClick = onToggle,
                        modifier = Modifier.padding(top = 28.dp)
                    ) {
                        Text(if (running) "停止" else "启动")
                    }

                    val nv = newVersion
                    val status = updateStatus
                    if (nv != null || status != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                .clickable {
                                    if (nv != null && !updateBusy) {
                                        updateBusy = true
                                        updateStatus = "正在下载 " + nv.label() + " …"
                                        dlDone = 0L
                                        dlTotal = if (nv.size > 0) nv.size else -1L
                                        dlStart = System.currentTimeMillis()
                                        scope.launch {
                                            val r = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    UpdateChecker.download(context, nv) { d, t ->
                                                        scope.launch {
                                                            dlDone = d
                                                            if (t > 0) dlTotal = t
                                                        }
                                                    }
                                                }
                                            }
                                            updateBusy = false
                                            r.onSuccess { f ->
                                                updateStatus = "下载完成（" + f.length() / 1024 / 1024 + "MB），正在调起安装"
                                                runCatching { UpdateChecker.install(context, f) }
                                                    .onFailure { updateStatus = "无法调起安装：" + (it.message ?: "") }
                                            }.onFailure {
                                                updateStatus = "下载失败：" + (it.message ?: "网络不可用")
                                            }
                                        }
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                Text(
                                    text = status ?: ("发现新版本 " + nv!!.label() + "，点此下载更新"),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val note = nv?.notes
                                if (note != null && note.isNotBlank() && status == null) {
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                if (updateBusy) {
                                    val frac = if (dlTotal > 0) (dlDone.toFloat() / dlTotal).coerceIn(0f, 1f) else 0f
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val secs = ((System.currentTimeMillis() - dlStart) / 1000).coerceAtLeast(1)
                                        val speed = dlDone / 1024 / secs
                                        Text(
                                            text = if (dlTotal > 0)
                                                "${dlDone / 1024 / 1024.0f} / ${dlTotal / 1024 / 1024.0f} MB · ${(frac * 100).toInt()}% · ${speed}KB/s"
                                            else "${dlDone / 1024} KB · ${speed}KB/s",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = {
                                            UpdateChecker.cancelDownload()
                                            updateStatus = "正在取消…"
                                        }) { Text("取消") }
                                    }
                                }
                            }
                        }
                    }

                    // 彩蛋：启动按钮与 GitHub 入口之间的自嘲文案 + 狗头 emoji
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "程序完全由牛马AI生成，那人一个代码都没敲！",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                        Image(
                            painter = painterResource(R.drawable.emoji_dog),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp)
                        )
                    }

                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = "打开 GitHub 仓库",
                        tint = Color(0xFF6B7280),
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .size(34.dp)
                            .clickable {
                                val ok = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO))
                                    )
                                }.isSuccess
                                if (!ok) hint = "没有可用的浏览器"
                            }
                    )

                    Text(
                        text = "分流顺序：本地网段 → 私有地址 → 广告拦截 → 恶意软件拦截 → 国内直连 → 其余走代理",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 20.dp, bottom = 20.dp)
                    )
                }
                page == 1 -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            NodeRow("节点名", node.name)
                            NodeRow("类型", node.typeLabel())
                            NodeRow("地址", node.host.ifBlank { "（未配置）" })
                            NodeRow("端口", if (node.port > 0) node.port.toString() else "（未配置）")
                            NodeRow("账号", node.user.ifBlank { "（无）" })
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("密码", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (showPass) node.pass.ifBlank { "（无）" } else "••••••••",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = { showPass = !showPass }) {
                                        Text(if (showPass) "隐藏" else "显示")
                                    }
                                }
                            }
                            NodeRow(
                                "是否加密",
                                when {
                                    node.type == "socks" -> "否（SOCKS5 无 TLS）"
                                    node.tls -> "是（TLS）"
                                    else -> "否"
                                }
                            )
                            NodeRow("密码存储", if (node.pass.isBlank()) "—" else "Keystore 密文")
                            if (!editing) {
                                TextButton(onClick = { editing = true }, modifier = Modifier.padding(top = 4.dp)) {
                                    Text(if (node.isConfigured()) "编辑节点" else "填写节点")
                                }
                            } else {
                                var eName by remember(node) { mutableStateOf(node.name) }
                                var eType by remember(node) { mutableStateOf(node.type) }
                                var eHost by remember(node) { mutableStateOf(node.host) }
                                var ePort by remember(node) { mutableStateOf(if (node.port > 0) node.port.toString() else "") }
                                var eUser by remember(node) { mutableStateOf(node.user) }
                                var ePass by remember(node) { mutableStateOf(node.pass) }
                                var eTls by remember(node) { mutableStateOf(node.tls) }
                                var eHint by remember { mutableStateOf<String?>(null) }

                                OutlinedTextField(
                                    value = eName,
                                    onValueChange = { eName = it },
                                    label = { Text("节点名") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("类型", style = MaterialTheme.typography.bodySmall)
                                    if (eType == "http") {
                                        Button(onClick = { eType = "http" }, modifier = Modifier.padding(start = 8.dp)) { Text("HTTP") }
                                    } else {
                                        OutlinedButton(onClick = { eType = "http" }, modifier = Modifier.padding(start = 8.dp)) { Text("HTTP") }
                                    }
                                    if (eType == "socks") {
                                        Button(onClick = { eType = "socks" }, modifier = Modifier.padding(start = 8.dp)) { Text("SOCKS5") }
                                    } else {
                                        OutlinedButton(onClick = { eType = "socks" }, modifier = Modifier.padding(start = 8.dp)) { Text("SOCKS5") }
                                    }
                                }
                                OutlinedTextField(
                                    value = eHost,
                                    onValueChange = { eHost = it },
                                    label = { Text("地址（域名或 IP）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                OutlinedTextField(
                                    value = ePort,
                                    onValueChange = { ePort = it.filter { c -> c.isDigit() } },
                                    label = { Text("端口") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                OutlinedTextField(
                                    value = eUser,
                                    onValueChange = { eUser = it },
                                    label = { Text("账号（可留空）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                OutlinedTextField(
                                    value = ePass,
                                    onValueChange = { ePass = it },
                                    label = { Text("密码（可留空）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Switch(
                                        checked = eTls,
                                        onCheckedChange = { eTls = it },
                                        enabled = eType != "socks"
                                    )
                                    Text(
                                        text = "启用 TLS（HTTPS 代理）",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                Row(modifier = Modifier.padding(top = 8.dp)) {
                                    Button(onClick = {
                                        val p = ePort.trim().toIntOrNull()
                                        if (eHost.isBlank() || p == null || p !in 1..65535) {
                                            eHint = "地址不能为空，端口需为 1-65535"
                                        } else {
                                            val n = node.copy(
                                                name = eName.trim().ifBlank { "节点" },
                                                type = eType,
                                                host = eHost.trim(),
                                                port = p,
                                                user = eUser.trim(),
                                                pass = ePass,
                                                tls = if (eType == "socks") false else eTls,
                                            )
                                            NodeConfig.save(context, n)
                                            node = n
                                            editing = false
                                            applyChange("节点配置已更新")
                                        }
                                    }) {
                                        Text("保存")
                                    }
                                    TextButton(
                                        onClick = { editing = false },
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text("取消")
                                    }
                                }
                                if (eHint != null) {
                                    Text(
                                        text = eHint ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "不想手填可在「设置」里用二维码或剪贴板把配置导过来。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp, bottom = 20.dp)
                    )
                }
                else -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    var input by remember { mutableStateOf("") }
                    var domainInput by remember { mutableStateOf("") }
                    Text(
                        text = "分流规则",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.padding(end = 8.dp)) {
                                    Text("广告域名拦截", style = MaterialTheme.typography.bodyMedium)
                                    Text("命中内置广告规则集直接拒绝", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = adsBlock,
                                    onCheckedChange = { on ->
                                        adsBlock = on
                                        RulePrefs.setAdsBlock(context, on)
                                        applyChange(if (on) "广告拦截已开启" else "广告拦截已关闭")
                                    }
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.padding(end = 8.dp)) {
                                    Text("恶意软件拦截", style = MaterialTheme.typography.bodyMedium)
                                    Text("命中恶意软件 / 钓鱼域名库直接拒绝", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = malwareBlock,
                                    onCheckedChange = { on ->
                                        malwareBlock = on
                                        RulePrefs.setMalwareBlock(context, on)
                                        if (on && !RuleSetUpdater.hasMalwareList(context)) {
                                            applyChange("恶意软件拦截已开启，需先点下面「开始下载更新」下载域名库")
                                        } else {
                                            applyChange(if (on) "恶意软件拦截已开启" else "恶意软件拦截已关闭")
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        text = "规则集更新",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = "点按钮即下载最新规则集（4 项），不是只检查版本",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(text = ruleSetInfo, style = MaterialTheme.typography.bodySmall)
                            TextButton(
                                contentPadding = PaddingValues(0.dp),
                                onClick = {
                                    val next = when (updateChannel) {
                                        "auto" -> "direct"
                                        "direct" -> "proxy"
                                        else -> "auto"
                                    }
                                    RulePrefs.setUpdateChannel(context, next)
                                    updateChannel = next
                                    updateMsg = "更新通道已切换：" + when (next) {
                                        "direct" -> "直连（只走物理网络）"
                                        "proxy" -> if (updateNodeReady) "代理（经 $updateNodeText 出站）" else "代理不可用：请先在节点页填写代理地址"
                                        else -> "自动（先走直连最快源，失败再用代理兜底）"
                                    }
                                }
                            ) {
                                Text(
                                    text = when (updateChannel) {
                                        "direct" -> "通道：直连 ▸"
                                        "proxy" -> "通道：代理 $updateNodeText ▸"
                                        else -> "通道：自动 ▸"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        if (updating) {
                                            RuleSetUpdater.requestCancel()
                                            updateMsg = "正在取消…"
                                        } else {
                                            updating = true
                                            updateResults.clear()
                                            updateMsg = "开始下载，共 4 项…"
                                            Thread {
                                                var err: String? = null
                                                var done = 0
                                                try {
                                                    RuleSetUpdater.updateAllDetailed(context) { r ->
                                                        done++
                                                        Handler(Looper.getMainLooper()).post {
                                                            updateResults.add(r)
                                                            updateMsg = "进行中：已完成 " + done + "/4"
                                                        }
                                                    }
                                                } catch (t: Throwable) {
                                                    err = t.message ?: t.javaClass.simpleName
                                                }
                                                Handler(Looper.getMainLooper()).post {
                                                    updating = false
                                                    val ok = updateResults.count { it.ok }
                                                    val total = updateResults.size
                                                    updateMsg = when {
                                                        err != null -> "更新中断：" + err
                                                        ok == 0 -> "全部失败，可切换通道后重试"
                                                        ok < total -> "完成 " + ok + "/" + total + "，失败项看下方明细"
                                                        else -> "更新完成，4 项全部成功"
                                                    }
                                                    ruleSetInfo = RuleSetUpdater.summary(context)
                                                    // 域名库已下载完成，"需先下载域名库"的旧提示立即清掉
                                                    if (ok > 0 && hint?.contains("需先点下面") == true) hint = null
                                                    if (ProxyVpnService.running && ok > 0) onReload()
                                                }
                                            }.start()
                                        }
                                    }
                                ) {
                                    Text(if (updating) "取消" else "开始下载更新")
                                }
                            }
                            if (updateMsg != null) {
                                Text(
                                    text = updateMsg!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (updateResults.isNotEmpty()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                    updateResults.forEach { r ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = (if (r.ok) "✓ " else "✗ ") + r.label,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                text = r.detail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = {
                                showSrcEdit = !showSrcEdit
                                customSrc = RulePrefs.customSources(context)
                            }) {
                                Text(if (showSrcEdit) "收起自定义源" else "自定义更新源")
                            }
                            if (showSrcEdit) {
                                OutlinedTextField(
                                    value = customSrc,
                                    onValueChange = { customSrc = it },
                                    label = { Text("每行 key=URL，key 取 geosite / geoip / ads / malware") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3
                                )
                                Text(
                                    text = "留空则用内置多源：官方 → ghfast.top → gh-proxy.com → jsdelivr 自动回退；填了优先走你的源，内置仍兜底。无 key 的 URL 一律当恶意域名清单。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Button(onClick = {
                                        RulePrefs.setCustomSources(context, customSrc)
                                        showSrcEdit = false
                                        updateMsg = "自定义源已保存，点「开始下载更新」生效"
                                    }) {
                                        Text("保存")
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "顺序：本地网段 → 私有地址 → 广告拦截 → 恶意软件拦截 → .cn 及国内规则集直连 → 其余走代理",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Text(
                        text = "本地忽略网段（直连，不走代理）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        text = "爱快 LAN 段默认已加入，可自行增删；改动后重启连接生效",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            if (ignoreList.isEmpty()) {
                                Text(
                                    text = "（列表为空，全部流量走代理）",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                            ignoreList.forEach { cidr ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = cidr, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = {
                                        IgnoreList.remove(context, cidr)
                                        ignoreList = IgnoreList.load(context)
                                        applyChange("已移除 $cidr")
                                    }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("新增 IP 或网段，如 10.0.0.5 或 192.168.1.0/24") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Button(onClick = {
                            if (input.isBlank()) {
                                hint = "请输入 IP 或网段"
                                return@Button
                            }
                            val adding = input.trim()
                            val ok = IgnoreList.add(context, adding)
                            if (ok) {
                                input = ""
                                ignoreList = IgnoreList.load(context)
                                applyChange("已添加 $adding")
                            } else {
                                hint = "格式不对或已存在"
                            }
                        }) {
                            Text("添加")
                        }
                        TextButton(
                            onClick = {
                                IgnoreList.reset(context)
                                ignoreList = IgnoreList.load(context)
                                applyChange("已恢复默认网段")
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("恢复默认")
                        }
                    }
                    Text(
                        text = "本地忽略域名（直连，不走代理）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        text = "填 example.com 即该域名及其全部子域直连；填 *.example.com 只匹配子域；localhost 也支持。改动后重启连接生效",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            if (ignoreDomains.isEmpty()) {
                                Text(
                                    text = "（未配置忽略域名）",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                            ignoreDomains.forEach { d ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = d, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = {
                                        IgnoreList.removeDomain(context, d)
                                        ignoreDomains = IgnoreList.loadDomains(context)
                                        applyChange("已移除 $d")
                                    }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("新增域名，如 example.com 或 *.example.com 或 localhost") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Button(onClick = {
                            if (domainInput.isBlank()) {
                                hint = "请输入域名"
                                return@Button
                            }
                            val adding = domainInput.trim()
                            if (IgnoreList.addDomain(context, adding)) {
                                domainInput = ""
                                ignoreDomains = IgnoreList.loadDomains(context)
                                applyChange("已添加域名 $adding")
                            } else {
                                hint = "域名格式不对或已存在"
                            }
                        }) {
                            Text("添加")
                        }
                        TextButton(
                            onClick = {
                                IgnoreList.resetDomains(context)
                                ignoreDomains = IgnoreList.loadDomains(context)
                                applyChange("已清空忽略域名")
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("清空")
                        }
                    }
                    Box(modifier = Modifier.padding(bottom = 20.dp))
                }
            }
        }

        if (!inSettings) {
            NavigationBar {
                pages.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = page == i,
                        onClick = { page = i },
                        icon = { Text(if (page == i) "●" else "○") },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

private fun appVersion(context: android.content.Context): String = runCatching {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    val name = pi.versionName ?: "?"
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    name + "（" + code + "）"
}.getOrDefault("?")

@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channel = remember { RulePrefs.updateChannel(context) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var dlDone by remember { mutableStateOf(0L) }
    var dlTotal by remember { mutableStateOf(-1L) }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("当前版本 " + appVersion(context), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "下载通道：" + UpdateChecker.channelLabel(channel) + "（与规则集更新共用）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !checking && !downloading,
                    onClick = {
                        checking = true
                        status = "正在检查…"
                        scope.launch {
                            val r = runCatching { withContext(Dispatchers.IO) { UpdateChecker.check(context) } }
                            checking = false
                            r.onSuccess { i ->
                                val cur = UpdateChecker.currentCode(context)
                                if (i.versionCode > cur) {
                                    info = i
                                    status = "发现新版本 " + i.label()
                                } else {
                                    info = null
                                    status = "已是最新版本（远端 " + i.label() + "）"
                                }
                            }.onFailure { status = "检查失败：" + (it.message ?: "网络不可用") }
                        }
                    }
                ) {
                    Text(if (checking) "检查中…" else "检查更新")
                }
                val i = info
                if (i != null) {
                    Button(
                        enabled = !checking,
                        onClick = {
                            if (downloading) {
                                UpdateChecker.cancelDownload()
                                status = "正在取消…"
                            } else {
                            downloading = true
                            status = "正在下载 " + i.label() + " …"
                            dlDone = 0L
                            dlTotal = if (i.size > 0) i.size else -1L
                            scope.launch {
                                val r = runCatching {
                                    withContext(Dispatchers.IO) {
                                        UpdateChecker.download(context, i) { d, t ->
                                            scope.launch {
                                                dlDone = d
                                                if (t > 0) dlTotal = t
                                            }
                                        }
                                    }
                                }
                                downloading = false
                                r.onSuccess { f ->
                                    status = "下载完成（" + f.length() / 1024 / 1024 + "MB），正在调起安装"
                                    runCatching { UpdateChecker.install(context, f) }
                                        .onFailure { status = "无法调起安装：" + (it.message ?: "") }
                                }.onFailure { status = "下载失败：" + (it.message ?: "网络不可用") }
                            }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(if (downloading) "取消下载" else "下载并安装")
                    }
                }
            }
            if (downloading) {
                val frac = if (dlTotal > 0) (dlDone.toFloat() / dlTotal).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    text = if (dlTotal > 0)
                        "${dlDone / 1024 / 1024.0f} / ${dlTotal / 1024 / 1024.0f} MB · ${(frac * 100).toInt()}%"
                    else "${dlDone / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            info?.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    node: NodeConfig,
    autoConnect: Boolean,
    onAutoConnect: (Boolean) -> Unit,
    qrText: String?,
    onScan: () -> Unit,
    onQrToggle: () -> Unit,
    onCopy: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = "启动",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text("自动连接", style = MaterialTheme.typography.bodyMedium)
                        Text("上次是连接状态时，App 启动或手机重启后自动连", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoConnect, onCheckedChange = onAutoConnect)
                }
            }
        }

        Text(
            text = "配置导入导出",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "内容是 " + node.masked() + " 的完整节点信息，含明文密码，别发到群里。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text("复制到剪贴板")
                    }
                    OutlinedButton(
                        onClick = onImport,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    ) {
                        Text("从剪贴板导入")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onQrToggle) {
                        Text(if (qrText == null) "显示二维码" else "收起二维码")
                    }
                    TextButton(onClick = onScan) {
                        Text("扫码导入")
                    }
                }
                if (qrText != null) {
                    val qrBitmap by produceState<Bitmap?>(initialValue = null, qrText) {
                        value = withContext(Dispatchers.Default) {
                            ConfigTransfer.renderBitmap(qrText ?: "", 720)
                        }
                    }
                    val bmp = qrBitmap
                    if (bmp == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = "正在生成二维码…",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "节点配置二维码",
                            modifier = Modifier.size(240.dp).padding(top = 6.dp)
                        )
                        Text(
                            text = "另一台机器：打开 App → 设置 → 扫码导入，对着这块屏幕扫一下即可。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = "软件更新",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        UpdateSection()

        Text(
            text = "关于",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                NodeRow("版本", appVersion(context))
                NodeRow("内核", "sing-box Libbox")
                NodeRow("更新节点", "服务端删掉旧版本 APK 后，第 1-5 位追新版号")
            }
        }
        Box(modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun NodeRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
