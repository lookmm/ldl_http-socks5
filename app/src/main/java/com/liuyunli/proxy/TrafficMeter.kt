package com.liuyunli.proxy

import android.net.TrafficStats
import android.os.Process
import android.util.Log

/**
 * 流量统计：/proc/net/dev 与 /sys/class/net 在 App 沙箱内被 SELinux 拒绝，
 * 改用 TrafficStats 按本应用 uid 的 socket 字节数做差值统计。
 */
object TrafficMeter {
    private const val TAG = "LDLProxy"
    private const val UNSUPPORTED = -1L
    private const val RATE_MIN_INTERVAL_MS = 300L

    @Volatile private var uid = -1
    @Volatile private var baseRx = 0L
    @Volatile private var baseTx = 0L
    @Volatile private var usingUid = false

    private var lastSampleMs = 0L
    private var lastRxTotal = 0L
    private var lastTxTotal = 0L
    private var rxRate = 0L
    private var txRate = 0L

    fun reset() {
        val u = Process.myUid()
        uid = u
        val rx = uidRx(u)
        val tx = uidTx(u)
        if (rx >= 0L && tx >= 0L) {
            usingUid = true
            baseRx = rx
            baseTx = tx
            Log.i(TAG, "traffic baseline uid=$u rx=$rx tx=$tx")
        } else {
            usingUid = false
            baseRx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(0L)
            baseTx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(0L)
            Log.i(TAG, "traffic baseline sys rx=$baseRx tx=$baseTx")
        }
        synchronized(this) {
            lastSampleMs = 0L
            lastRxTotal = 0L
            lastTxTotal = 0L
            rxRate = 0L
            txRate = 0L
        }
    }

    fun read(): Pair<Long, Long> {
        if (usingUid) {
            val u = if (uid > 0) uid else Process.myUid()
            val rx = uidRx(u)
            val tx = uidTx(u)
            if (rx >= 0L && tx >= 0L) {
                if (rx < baseRx || tx < baseTx) {
                    baseRx = rx
                    baseTx = tx
                }
                return Pair((rx - baseRx).coerceAtLeast(0L), (tx - baseTx).coerceAtLeast(0L))
            }
        }
        val rx = runCatching { TrafficStats.getTotalRxBytes() }.getOrDefault(0L)
        val tx = runCatching { TrafficStats.getTotalTxBytes() }.getOrDefault(0L)
        return Pair((rx - baseRx).coerceAtLeast(0L), (tx - baseTx).coerceAtLeast(0L))
    }

    /**
     * (下行速率 B/s, 上行速率 B/s)。主界面与通知栏共用同一份采样，
     * 距上次采样不足 300ms 直接返回缓存值，避免多端调用互相把对方采样窗口打碎。
     */
    fun rates(): Pair<Long, Long> = synchronized(this) {
        if (!usingUid && baseRx == 0L && baseTx == 0L) return Pair(0L, 0L)
        val t = read()
        val now = System.currentTimeMillis()
        if (lastSampleMs == 0L) {
            lastSampleMs = now
            lastRxTotal = t.first
            lastTxTotal = t.second
        } else {
            val dt = now - lastSampleMs
            if (dt >= RATE_MIN_INTERVAL_MS) {
                rxRate = (t.first - lastRxTotal).coerceAtLeast(0L) * 1000L / dt
                txRate = (t.second - lastTxTotal).coerceAtLeast(0L) * 1000L / dt
                lastSampleMs = now
                lastRxTotal = t.first
                lastTxTotal = t.second
            }
        }
        Pair(rxRate, txRate)
    }

    private fun uidRx(u: Int): Long =
        runCatching { TrafficStats.getUidRxBytes(u) }.getOrDefault(UNSUPPORTED.toInt()).toLong()

    private fun uidTx(u: Int): Long =
        runCatching { TrafficStats.getUidTxBytes(u) }.getOrDefault(UNSUPPORTED.toInt()).toLong()
}
