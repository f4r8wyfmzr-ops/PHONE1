package com.example.data.network

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class InitiatePaymentRequest(
    val payeeVpa: String,
    val payeeName: String,
    val merchantCode: String,
    val amountPaise: Long,
    val currency: String = "INR",
    val note: String,
    val qrSignature: String,
    val clientRiskScore: Int,
    val clientFraudFlags: List<String>,
)

@JsonClass(generateAdapter = true)
data class InitiatePaymentResponse(
    val pspOrderId: String,
    val txRef: String,
    val upiIntentUri: String,
    val providerName: String,
    val status: String,
    val expiresAtEpochMs: Long,
    val fraudRiskScore: Int,
    val fraudFlags: List<String> = emptyList(),
    val failureReason: String? = null,
)

@JsonClass(generateAdapter = true)
data class PaymentStatusResponse(
    val pspOrderId: String,
    val txRef: String,
    val status: String,
    val pspVerified: Boolean,
    val utrNumber: String = "",
    val bankReference: String = "",
    val verifiedAtIso: String = "",
    val failureReason: String = "",
)

@JsonClass(generateAdapter = true)
data class PspErrorBody(
    val code: String? = null,
    val message: String? = null,
)

interface PspApiService {
    @POST("api/v1/payments/initiate")
    suspend fun initiatePayment(
        @Header("Authorization") bearerToken: String,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Header("X-Request-Timestamp") timestampMs: String,
        @Header("X-Device-Fingerprint") deviceFingerprint: String,
        @Body request: InitiatePaymentRequest,
    ): Response<InitiatePaymentResponse>

    @GET("api/v1/payments/{orderId}/status")
    suspend fun getPaymentStatus(
        @Header("Authorization") bearerToken: String,
        @Header("X-Request-Timestamp") timestampMs: String,
        @Header("X-Device-Fingerprint") deviceFingerprint: String,
        @Path("orderId") orderId: String,
    ): Response<PaymentStatusResponse>
}
