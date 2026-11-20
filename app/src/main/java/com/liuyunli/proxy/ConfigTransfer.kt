package com.liuyunli.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/**
 * 节点配置的导入导出：
 * - 文本串格式 LYVPN1:<base64(JSON)>，可直接粘贴到聊天框、记事本
 * - 同一个串编码成二维码，另一台机器扫码/复制粘贴即可恢复，免手填
 * 注意：导出串内含明文密码，只走剪贴板与屏幕，不写文件、不上传。
 */
object ConfigTransfer {

    private const val HEAD = "LYVPN1:"

    fun exportText(node: NodeConfig): String {
        val json = node.toJsonObj().toString()
        return HEAD + Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** 解析导入串，兼容用户多带了空格/换行/前后缀的情况 */
    fun parse(text: String): NodeConfig? {
        val t = text.trim()
        val idx = t.indexOf(HEAD)
        if (idx < 0) return null
        val body = t.substring(idx + HEAD.length).trim().substringBefore('\n').substringBefore(' ')
        return runCatching {
            val json = String(Base64.decode(body, Base64.NO_WRAP), Charsets.UTF_8)
            val n = NodeConfig.fromJsonObj(JSONObject(json))
            if (n.isConfigured()) n else null
        }.getOrNull()
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("节点配置", text))
    }

    fun readClipboard(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    /**
     * 直接生成二维码位图。调用方必须在后台线程调（编码 + 51 万像素填充约几十毫秒），
     * UI 侧只做一次 drawImage，避免主线程逐格绘制导致卡顿。
     */
    fun renderBitmap(text: String, sizePx: Int = 720): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                pixels[off + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
