package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

object TransactionStatus {
    const val INITIATED = "INITIATED"
    const val AWAITING_PSP_CONFIRMATION = "AWAITING_PSP_CONFIRMATION"
    const val PENDING_VERIFICATION = "PENDING_VERIFICATION"
    const val VERIFIED_SUCCESS = "VERIFIED_SUCCESS"
    const val FAILED = "FAILED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val BLOCKED_FRAUD = "BLOCKED_FRAUD"
    const val PSP_UNAVAILABLE = "PSP_UNAVAILABLE"

    fun isTerminal(status: String): Boolean = status in setOf(
        VERIFIED_SUCCESS,
        FAILED,
        USER_CANCELLED,
        BLOCKED_FRAUD,
        PSP_UNAVAILABLE,
    )
}

@IgnoreExtraProperties
data class UpiTransaction(
    @DocumentId val id: String = "",
    val userId: String = "",
    val pspOrderId: String = "",
    val txRef: String = "",
    val payeeVpa: String = "",
    val payeeName: String = "",
    val merchantCode: String = "",
    val amountPaise: Long = 0L,
    val currency: String = "INR",
    val note: String = "",
    val status: String = TransactionStatus.INITIATED,
    val pspVerified: Boolean = false,
    val utrNumber: String = "",
    val failureReason: String = "",
    val fraudRiskScore: Int = 0,
    val fraudFlags: List<String> = emptyList(),
    val pspAppPackage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toClientCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "pspOrderId" to pspOrderId.take(128),
        "txRef" to txRef.take(128),
        "payeeVpa" to payeeVpa.take(128),
        "payeeName" to payeeName.take(120),
        "merchantCode" to merchantCode.take(16),
        "amountPaise" to amountPaise.coerceIn(100L, 10_000_000L),
        "currency" to "INR",
        "note" to note.take(120),
        // Client SDK may never write VERIFIED_SUCCESS or pspVerified=true
        "status" to if (status == TransactionStatus.VERIFIED_SUCCESS) TransactionStatus.PENDING_VERIFICATION else status,
        "pspVerified" to false,
        "utrNumber" to "",
        "failureReason" to failureReason.take(240),
        "fraudRiskScore" to fraudRiskScore.coerceIn(0, 100),
        "fraudFlags" to fraudFlags.take(10),
        "pspAppPackage" to pspAppPackage.take(128),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}
