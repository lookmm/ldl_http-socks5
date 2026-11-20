package com.liuyunli.proxy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码落盘加密：Android Keystore 生成不可导出的 AES-256 密钥，AES/GCM 加密后存字符串。
 * 存储格式：enc:v1:<base64(iv + ciphertext)>；无前缀的旧值按明文兼容读取，下次保存时自动升级为密文。
 * 密钥随应用卸载销毁；解密失败（如系统密钥被清）时退回空串，由用户重新填写。
 */
object SecretStore {

    private const val PREFIX = "enc:v1:"
    private const val KEY_ALIAS = "lyvpn_node_pass"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val key = key()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (t: Throwable) {
            // 加密不可用时退回明文，保证功能不中断
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !isEncrypted(stored)) return stored
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN)
            )
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
