package com.example.domain.upi

import com.example.data.model.Beneficiary
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.data.model.UserProfile
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UpiQrAndFraudTest {

    @Test
    fun parseStandardUpiUri_extractsMerchantAndAmount() {
        val raw = "upi://pay?pa=bharatstore@okaxis&pn=Bharat%20Kirana%20Store&mc=5411&am=245.50&cu=INR&tn=Groceries&tr=TXN9981"
        val result = UpiQrParser.parse(raw)
        assertTrue(result.isSuccess)
        val qr = result.getOrThrow()
        assertEquals("bharatstore@okaxis", qr.payeeVpa)
        assertEquals("Bharat Kirana Store", qr.payeeName)
        assertEquals(24550L, qr.amountPaise)
        assertEquals("5411", qr.merchantCode)
        assertTrue(qr.isVerifiedMerchant)
        assertTrue(qr.isFixedAmount)
    }

    @Test
    fun parseInvalidQr_returnsFailure() {
        val result = UpiQrParser.parse("https://example.com/not-a-upi-qr")
        assertTrue(result.isFailure)
    }

    @Test
    fun fraudEngine_blocksNewPayeeAboveCoolingLimit() {
        val profile = UserProfile(
            userId = "u1",
            dailyLimitPaise = 10_000_000L,
            perTxnLimitPaise = 5_000_000L,
        )
        val eval = FraudAndLimitsEngine.evaluate(
            payeeVpa = "friend@ybl",
            amountPaise = 600_000L, // INR 6,000 > INR 5,000 cooling cap
            isVerifiedMerchant = false,
            profile = profile,
            recentTransactions = emptyList(),
            knownBeneficiaries = emptyList(),
        )
        assertTrue(eval.isBlocked)
        assertTrue(eval.flags.contains(FraudFlag.NEW_PAYEE_COOLING_LIMIT))
    }

    @Test
    fun fraudEngine_allowsVerifiedMerchantWithinDailyLimit() {
        val profile = UserProfile(
            userId = "u1",
            dailyLimitPaise = 10_000_000L,
            perTxnLimitPaise = 5_000_000L,
        )
        val eval = FraudAndLimitsEngine.evaluate(
            payeeVpa = "supermart@okicici",
            amountPaise = 150_000L, // INR 1,500
            isVerifiedMerchant = true,
            profile = profile,
            recentTransactions = listOf(
                UpiTransaction(
                    payeeVpa = "other@okhdfcbank",
                    amountPaise = 100_000L,
                    status = TransactionStatus.VERIFIED_SUCCESS,
                    createdAt = Timestamp.now(),
                )
            ),
            knownBeneficiaries = listOf(
                Beneficiary(vpa = "supermart@okicici")
            ),
        )
        assertFalse(eval.isBlocked)
        assertEquals(100_000L, eval.dailySpentPaise)
    }
}
