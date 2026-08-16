// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES/GCM 加解密工具。
 *
 * 用途：加密存储敏感信息（如 AI API Key），保证：
 * - 明文永不落盘：只保存 Base64(IV + 密文)
 * - 密钥由系统 Keystore 保管（硬件级或系统级保护），应用进程无法导出
 * - 备份恢复后密钥不可用（Keystore 不随备份迁移）→ 解密失败时由调用方清空密文，重新配置即可
 *
 * 注意：加解密操作必须在 IO 线程执行（Keystore 操作可能触发用户认证/阻塞）。
 */
object SecurePrefs {

    private const val KEY_ALIAS = "blancall_secure_prefs"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12  // GCM 标准 IV 长度

    /** 获取或创建 Keystore 中的 AES 密钥（幂等） */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * 加密字符串，返回 Base64(IV + 密文)；失败返回 null（调用方应清空存储）。
     */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /**
     * 解密 Base64(IV + 密文)；失败返回 null（如备份恢复后密钥不可用）。
     */
    fun decrypt(data: String): String? {
        return try {
            val raw = Base64.decode(data, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) {
                null
            } else {
                val iv = raw.copyOfRange(0, IV_LENGTH)
                val encrypted = raw.copyOfRange(IV_LENGTH, raw.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }
}
