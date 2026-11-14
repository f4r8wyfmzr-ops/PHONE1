package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CryptoSecurityManagerTest {

    @Test
    fun encryptAndDecryptAesGcm_roundTripsCleanly() {
        val manager = CryptoSecurityManager()
        val secret = """{"payeeVpa":"merchant@okaxis","amountPaise":12500}"""
        val envelope = manager.encryptAesGcm(secret)
        assertNotEquals(secret, envelope.cipherTextBase64)
        val decrypted = manager.decryptAesGcm(envelope)
        assertEquals(secret, decrypted)
    }

    @Test
    fun computeDeviceFingerprint_isDeterministicSha256() {
        val manager = CryptoSecurityManager()
        val fp1 = manager.computeDeviceFingerprint("uid_1", "com.aistudio.phonepay.kxqzmw")
        val fp2 = manager.computeDeviceFingerprint("uid_1", "com.aistudio.phonepay.kxqzmw")
        assertEquals(64, fp1.length)
        assertEquals(fp1, fp2)
    }
}
