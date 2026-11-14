package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class Beneficiary(
    @DocumentId val id: String = "",
    val userId: String = "",
    val vpa: String = "",
    val merchantName: String = "",
    val isVerifiedMerchant: Boolean = false,
    @ServerTimestamp val firstPaidAt: Timestamp? = null,
    @ServerTimestamp val lastPaidAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "vpa" to vpa.take(128),
        "merchantName" to merchantName.take(120),
        "isVerifiedMerchant" to isVerifiedMerchant,
        "firstPaidAt" to FieldValue.serverTimestamp(),
        "lastPaidAt" to FieldValue.serverTimestamp(),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp(),
    )
}
