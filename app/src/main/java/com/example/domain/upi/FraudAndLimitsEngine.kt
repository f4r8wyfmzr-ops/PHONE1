package com.example.domain.upi

import com.example.data.model.Beneficiary
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.data.model.UserProfile

enum class FraudFlag {
    EXCEEDS_NPCI_SINGLE_CAP,
    EXCEEDS_USER_PER_TXN_LIMIT,
    EXCEEDS_DAILY_CAP,
    NEW_PAYEE_COOLING_LIMIT,
    HIGH_VELOCITY_HOURLY,
    DUPLICATE_TXN_WINDOW,
    SUSPICIOUS_VPA_PATTERN,
    UNVERIFIED_PERSONAL_HIGH_VALUE,
}

data class FraudEvaluation(
    val riskScore: Int,
    val isBlocked: Boolean,
    val flags: List<FraudFlag>,
    val dailySpentPaise: Long,
    val remainingDailyPaise: Long,
    val effectiveMaxSinglePaise: Long,
    val isNewPayeeCoolingActive: Boolean,
)

object FraudAndLimitsEngine {
    const val NPCI_HARD_CAP_PAISE = 10_000_000L // INR 1,00,000
    const val NEW_PAYEE_COOLING_CAP_PAISE = 500_000L // INR 5,000 first 24h NPCI standard
    const val HOURLY_VELOCITY_MAX = 10
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    private const val ONE_HOUR_MS = 60L * 60L * 1000L
    private const val TWO_MIN_MS = 2L * 60L * 1000L

    private val SUSPICIOUS_KEYWORDS = listOf("lottery", "prize", "kycupdate", "refunddesk", "claimnow", "freegift")

    fun evaluate(
        payeeVpa: String,
        amountPaise: Long,
        isVerifiedMerchant: Boolean,
        profile: UserProfile,
        recentTransactions: List<UpiTransaction>,
        knownBeneficiaries: List<Beneficiary>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): FraudEvaluation {
        val flags = mutableListOf<FraudFlag>()
        var riskScore = 0

        val dayAgoMs = nowEpochMs - ONE_DAY_MS
        val hourAgoMs = nowEpochMs - ONE_HOUR_MS
        val twoMinAgoMs = nowEpochMs - TWO_MIN_MS

        // Only count verified or in-flight/pending outflows toward 24h spending cap
        val dailySpentPaise = recentTransactions
            .filter { tx ->
                val createdMs = tx.createdAt?.toDate()?.time ?: nowEpochMs
                createdMs >= dayAgoMs &&
                    (tx.status == TransactionStatus.VERIFIED_SUCCESS ||
                        tx.status == TransactionStatus.AWAITING_PSP_CONFIRMATION ||
                        tx.status == TransactionStatus.PENDING_VERIFICATION)
            }
            .sumOf { it.amountPaise }

        val dailyCap = minOf(profile.dailyLimitPaise, NPCI_HARD_CAP_PAISE)
        val remainingDailyPaise = (dailyCap - dailySpentPaise).coerceAtLeast(0L)

        // Check new payee 24-hour cooling window
        val matchingBeneficiary = knownBeneficiaries.firstOrNull {
            it.vpa.equals(payeeVpa.trim(), ignoreCase = true)
        }
        val firstPaidMs = matchingBeneficiary?.firstPaidAt?.toDate()?.time
        val isNewPayeeCoolingActive = if (isVerifiedMerchant) {
            false
        } else {
            firstPaidMs == null || (nowEpochMs - firstPaidMs) < ONE_DAY_MS
        }

        val perTxnLimit = minOf(profile.perTxnLimitPaise, dailyCap)
        val coolingCap = if (isNewPayeeCoolingActive) NEW_PAYEE_COOLING_CAP_PAISE else perTxnLimit
        val effectiveMaxSinglePaise = minOf(perTxnLimit, remainingDailyPaise, coolingCap).coerceAtLeast(0L)

        if (amountPaise > NPCI_HARD_CAP_PAISE) {
            flags += FraudFlag.EXCEEDS_NPCI_SINGLE_CAP
            riskScore += 50
        }
        if (amountPaise > profile.perTxnLimitPaise) {
            flags += FraudFlag.EXCEEDS_USER_PER_TXN_LIMIT
            riskScore += 35
        }
        if (dailySpentPaise + amountPaise > dailyCap) {
            flags += FraudFlag.EXCEEDS_DAILY_CAP
            riskScore += 45
        }
        if (isNewPayeeCoolingActive && amountPaise > NEW_PAYEE_COOLING_CAP_PAISE) {
            flags += FraudFlag.NEW_PAYEE_COOLING_LIMIT
            riskScore += 40
        }

        val hourlyAttempts = recentTransactions.count { tx ->
            val createdMs = tx.createdAt?.toDate()?.time ?: 0L
            createdMs >= hourAgoMs && tx.status != TransactionStatus.PSP_UNAVAILABLE
        }
        if (hourlyAttempts >= HOURLY_VELOCITY_MAX) {
            flags += FraudFlag.HIGH_VELOCITY_HOURLY
            riskScore += 50
        }

        val hasRecentDuplicate = recentTransactions.any { tx ->
            val createdMs = tx.createdAt?.toDate()?.time ?: 0L
            createdMs >= twoMinAgoMs &&
                tx.payeeVpa.equals(payeeVpa.trim(), ignoreCase = true) &&
                tx.amountPaise == amountPaise &&
                tx.status != TransactionStatus.FAILED &&
                tx.status != TransactionStatus.USER_CANCELLED &&
                tx.status != TransactionStatus.PSP_UNAVAILABLE
        }
        if (hasRecentDuplicate) {
            flags += FraudFlag.DUPLICATE_TXN_WINDOW
            riskScore += 45
        }

        val lowerVpa = payeeVpa.lowercase()
        if (SUSPICIOUS_KEYWORDS.any { lowerVpa.contains(it) }) {
            flags += FraudFlag.SUSPICIOUS_VPA_PATTERN
            riskScore += 65
        }

        if (!isVerifiedMerchant && amountPaise >= 2_000_000L) { // >= INR 20,000 to non-merchant
            flags += FraudFlag.UNVERIFIED_PERSONAL_HIGH_VALUE
            riskScore += 20
        }

        val clampedRisk = riskScore.coerceIn(0, 100)
        val hardBlockFlags = setOf(
            FraudFlag.EXCEEDS_NPCI_SINGLE_CAP,
            FraudFlag.EXCEEDS_USER_PER_TXN_LIMIT,
            FraudFlag.EXCEEDS_DAILY_CAP,
            FraudFlag.NEW_PAYEE_COOLING_LIMIT,
            FraudFlag.HIGH_VELOCITY_HOURLY,
            FraudFlag.DUPLICATE_TXN_WINDOW,
            FraudFlag.SUSPICIOUS_VPA_PATTERN,
        )
        val isBlocked = flags.any { it in hardBlockFlags } || clampedRisk >= 70

        return FraudEvaluation(
            riskScore = clampedRisk,
            isBlocked = isBlocked,
            flags = flags,
            dailySpentPaise = dailySpentPaise,
            remainingDailyPaise = remainingDailyPaise,
            effectiveMaxSinglePaise = effectiveMaxSinglePaise,
            isNewPayeeCoolingActive = isNewPayeeCoolingActive,
        )
    }
}
