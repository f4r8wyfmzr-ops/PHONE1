package com.example.data.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedEnvelope(
    val ivBase64: String,
    val cipherTextBase64: String,
    val algorithm: String = "AES-256-GCM",
)

/**
 * Hardware-backed Android Keystore AES-256-GCM security manager for sensitive payload
 * integrity and device attestation fingerprints. Never stores or processes UPI PINs.
 */
class CryptoSecurityManager {
    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    @Volatile
    private var cachedFallbackKey: SecretKey? = null

    fun getOrCreateAesKey(): SecretKey {
        val ks = keyStore
        if (ks != null) {
            val existing = runCatching {
                (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            }.getOrNull()
            if (existing != null) return existing

            val generated = runCatching {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE,
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }.getOrNull()
            if (generated != null) return generated
        }

        // JVM/Robolectric test fallback when AndroidKeyStore provider is unavailable
        cachedFallbackKey?.let { return it }
        val fallbackGen = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }
        return fallbackGen.generateKey().also { cachedFallbackKey = it }
    }

    fun encryptAesGcm(plaintext: String): EncryptedEnvelope {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedEnvelope(
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            cipherTextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
        )
    }

    fun decryptAesGcm(envelope: EncryptedEnvelope): String {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(envelope.ivBase64, Base64.NO_WRAP)
        val cipherBytes = Base64.decode(envelope.cipherTextBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    fun computeDeviceFingerprint(userId: String, packageName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$userId|$packageName|phonepay-android-v1"
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "phonepay_api_envelope_aes256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
