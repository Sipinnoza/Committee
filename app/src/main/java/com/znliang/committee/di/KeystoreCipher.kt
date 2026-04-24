package com.znliang.committee.di

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 加密工具 — 用于加密存储 API keys。
 *
 * 使用 AES/GCM 加密，密钥保存在 Android Keystore（硬件安全模块支持）。
 * 加密结果格式: base64(IV + ciphertext)
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val KEYSTORE_ALIAS = "committee_api_key_alias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /** 前缀标识已加密的值，避免双重加密 */
    const val ENCRYPTED_PREFIX = "ENC:"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) return existingKey.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return plainText
        if (plainText.startsWith(ENCRYPTED_PREFIX)) return plainText // already encrypted
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Encryption failed: ${e.message}")
            plainText // fallback to plaintext on failure
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isBlank()) return cipherText
        if (!cipherText.startsWith(ENCRYPTED_PREFIX)) return cipherText // not encrypted (legacy)
        return try {
            val combined = Base64.decode(
                cipherText.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP
            )
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH, iv)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed: ${e.message}")
            cipherText.removePrefix(ENCRYPTED_PREFIX) // fallback
        }
    }
}
