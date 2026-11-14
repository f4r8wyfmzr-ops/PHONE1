package com.example.data.network

import android.net.Uri
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

sealed interface PspGatewayResult<out T> {
    data class Success<T>(val data: T) : PspGatewayResult<T>
    data class NotConfigured(val reason: String) : PspGatewayResult<Nothing>
    data class Error(val code: String, val message: String) : PspGatewayResult<Nothing>
}

interface PspGateway {
    fun isConfigured(customBaseUrl: String = ""): Boolean
    suspend fun initiatePayment(
        idToken: String,
        deviceFingerprint: String,
        request: InitiatePaymentRequest,
        customBaseUrl: String = "",
    ): PspGatewayResult<InitiatePaymentResponse>

    suspend fun verifyPaymentStatus(
        idToken: String,
        deviceFingerprint: String,
        orderId: String,
        customBaseUrl: String = "",
    ): PspGatewayResult<PaymentStatusResponse>
}

class PspNetworkClient : PspGateway {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private fun resolveBaseUrl(customBaseUrl: String): String? {
        val candidate = customBaseUrl.trim().ifBlank { BuildConfig.PSP_BACKEND_BASE_URL.trim() }
        if (candidate.isBlank()) return null
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        return if (candidate.endsWith("/")) candidate else "$candidate/"
    }

    override fun isConfigured(customBaseUrl: String): Boolean = resolveBaseUrl(customBaseUrl) != null

    private fun createService(baseUrl: String): PspApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
            redactHeader("X-Device-Fingerprint")
        }

        val clientBuilder = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)

        val certPin = BuildConfig.PSP_CERT_SHA256_PIN.trim()
        val host = Uri.parse(baseUrl).host
        if (certPin.startsWith("sha256/") && !host.isNullOrBlank()) {
            clientBuilder.certificatePinner(
                CertificatePinner.Builder()
                    .add(host, certPin)
                    .build()
            )
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PspApiService::class.java)
    }

    override suspend fun initiatePayment(
        idToken: String,
        deviceFingerprint: String,
        request: InitiatePaymentRequest,
        customBaseUrl: String,
    ): PspGatewayResult<InitiatePaymentResponse> {
        val baseUrl = resolveBaseUrl(customBaseUrl)
            ?: return PspGatewayResult.NotConfigured(
                "PSP_BACKEND_NOT_CONFIGURED: Deploy the secure PSP backend over HTTPS and set PSP_BACKEND_BASE_URL with RBI-authorized bank/PSP merchant credentials."
            )

        return try {
            val service = createService(baseUrl)
            val response = service.initiatePayment(
                bearerToken = "Bearer $idToken",
                idempotencyKey = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis().toString(),
                deviceFingerprint = deviceFingerprint,
                request = request,
            )
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    PspGatewayResult.Success(body)
                } else {
                    PspGatewayResult.Error("EMPTY_RESPONSE", "PSP backend returned an empty response.")
                }
            } else {
                val rawErr = response.errorBody()?.string().orEmpty()
                val parsedErr = runCatching { moshi.adapter(PspErrorBody::class.java).fromJson(rawErr) }.getOrNull()
                val code = parsedErr?.code ?: "HTTP_${response.code()}"
                val msg = parsedErr?.message ?: "PSP gateway rejected payment initiation (${response.code()})."
                if (code == "PSP_CREDENTIALS_NOT_CONFIGURED") {
                    PspGatewayResult.NotConfigured(msg)
                } else {
                    PspGatewayResult.Error(code, msg)
                }
            }
        } catch (e: Exception) {
            PspGatewayResult.Error(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Encrypted connection to PSP backend failed.",
            )
        }
    }

    override suspend fun verifyPaymentStatus(
        idToken: String,
        deviceFingerprint: String,
        orderId: String,
        customBaseUrl: String,
    ): PspGatewayResult<PaymentStatusResponse> {
        val baseUrl = resolveBaseUrl(customBaseUrl)
            ?: return PspGatewayResult.NotConfigured(
                "PSP_BACKEND_NOT_CONFIGURED: Cannot verify transaction status without an authorized HTTPS PSP backend."
            )

        return try {
            val service = createService(baseUrl)
            val response = service.getPaymentStatus(
                bearerToken = "Bearer $idToken",
                timestampMs = System.currentTimeMillis().toString(),
                deviceFingerprint = deviceFingerprint,
                orderId = orderId,
            )
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    PspGatewayResult.Success(body)
                } else {
                    PspGatewayResult.Error("EMPTY_RESPONSE", "Empty status payload from PSP backend.")
                }
            } else {
                val rawErr = response.errorBody()?.string().orEmpty()
                val parsedErr = runCatching { moshi.adapter(PspErrorBody::class.java).fromJson(rawErr) }.getOrNull()
                PspGatewayResult.Error(
                    code = parsedErr?.code ?: "HTTP_${response.code()}",
                    message = parsedErr?.message ?: "Failed to verify status with PSP (${response.code()}).",
                )
            }
        } catch (e: Exception) {
            PspGatewayResult.Error(
                code = "NETWORK_ERROR",
                message = e.localizedMessage ?: "Failed to reach PSP status endpoint.",
            )
        }
    }
}
