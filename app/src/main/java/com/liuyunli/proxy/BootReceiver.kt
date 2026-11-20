package com.liuyunli.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机自启：仅在「自动连接」勾选且 VPN 授权仍有效时拉起服务。
 * Android 的 VpnService 授权不能被绕过，授权失效时这里静默跳过，由用户手动启动并授权。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!RulePrefs.autoConnect(context)) return
        if (ProxyVpnService.running) return
        if (VpnService.prepare(context) != null) {
            Log.i("MarvisProxy", "开机自启跳过：VPN 授权已失效")
            return
        }
        runCatching {
            val it = Intent(context, ProxyVpnService::class.java)
                .setAction(ProxyVpnService.ACTION_START)
            ContextCompat.startForegroundService(context, it)
            Log.i("MarvisProxy", "开机自启：已拉起代理服务")
        }.onFailure { Log.e("MarvisProxy", "开机自启失败", it) }
    }
}
