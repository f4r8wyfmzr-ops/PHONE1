package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val preferredLanguage: String = "en",
    val biometricLockEnabled: Boolean = true,
    val dailyLimitPaise: Long = 10_000_000L, // INR 1,00,000 NPCI cap
    val perTxnLimitPaise: Long = 5_000_000L, // INR 50,000 default single txn cap
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName.ifBlank { "Phone Pay User" }.take(80),
        "email" to email.ifBlank { "user@phonepay.local" }.take(120),
        "preferredLanguage" to if (preferredLanguage == "hi") "hi" else "en",
        "biometricLockEnabled" to biometricLockEnabled,
        "dailyLimitPaise" to dailyLimitPaise.coerceIn(100L, 10_000_000L),
        "perTxnLimitPaise" to perTxnLimitPaise.coerceIn(100L, dailyLimitPaise.coerceIn(100L, 10_000_000L)),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}
