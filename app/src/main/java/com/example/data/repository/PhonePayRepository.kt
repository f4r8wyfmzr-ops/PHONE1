package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.Beneficiary
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.data.model.UserProfile
import com.example.data.network.CryptoSecurityManager
import com.example.data.network.InitiatePaymentRequest
import com.example.data.network.PspGateway
import com.example.data.network.PspGatewayResult
import com.example.data.network.PspNetworkClient
import com.example.domain.upi.FraudAndLimitsEngine
import com.example.domain.upi.FraudEvaluation
import com.example.domain.upi.UpiQrPayload
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

sealed interface PaymentDispatchOutcome {
    data class LaunchAuthorizedUpiApp(
        val transactionId: String,
        val pspOrderId: String,
        val txRef: String,
        val signedUpiIntentUri: String,
        val providerName: String,
    ) : PaymentDispatchOutcome

    data class FraudBlocked(
        val transactionId: String,
        val evaluation: FraudEvaluation,
    ) : PaymentDispatchOutcome

    data class PspApprovalRequired(
        val transactionId: String,
        val reason: String,
    ) : PaymentDispatchOutcome

    data class GatewayError(
        val transactionId: String,
        val code: String,
        val message: String,
    ) : PaymentDispatchOutcome
}

sealed interface VerificationOutcome {
    data class VerifiedSuccess(val transaction: UpiTransaction) : VerificationOutcome
    data class Pending(val transaction: UpiTransaction, val message: String) : VerificationOutcome
    data class Failed(val transaction: UpiTransaction, val reason: String) : VerificationOutcome
    data class Unavailable(val message: String) : VerificationOutcome
}

class PhonePayRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val pspGateway: PspGateway = PspNetworkClient(),
    private val cryptoManager: CryptoSecurityManager = CryptoSecurityManager(),
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    fun observeProfile(userId: String): Flow<UserProfile?> =
        db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(error, OperationType.GET, "users/$userId", auth)
                }
                throw error
            }

    suspend fun ensureProfileInitialized(
        displayName: String,
        email: String,
        preferredLanguage: String = "en",
    ): Result<UserProfile> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val snap = docRef.get().await()
        if (snap.exists()) {
            snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: UserProfile(id = uid, userId = uid, displayName = displayName, email = email)
        } else {
            val initial = UserProfile(
                id = uid,
                userId = uid,
                displayName = displayName.ifBlank { "Phone Pay User" },
                email = email.ifBlank { "user@phonepay.local" },
                preferredLanguage = preferredLanguage,
                biometricLockEnabled = true,
                dailyLimitPaise = 10_000_000L,
                perTxnLimitPaise = 5_000_000L,
            )
            docRef.set(initial.toCreateMap()).await()
            initial
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        val uid = auth.currentUser?.uid ?: "unauthenticated"
        handleFirestoreError(e, OperationType.CREATE, "users/$uid", auth)
    }

    suspend fun updateLanguage(languageCode: String): Result<Unit> = runCatching {
        val uid = requireUserId()
        val normalized = if (languageCode == "hi") "hi" else "en"
        val docRef = db.collection("users").document(uid)
        docRef.update(
            mapOf(
                "preferredLanguage" to normalized,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "users/${auth.currentUser?.uid}", auth)
    }

    suspend fun updateSecurityAndLimits(
        biometricLockEnabled: Boolean,
        dailyLimitPaise: Long,
        perTxnLimitPaise: Long,
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        val clampedDaily = dailyLimitPaise.coerceIn(100L, FraudAndLimitsEngine.NPCI_HARD_CAP_PAISE)
        val clampedPerTxn = perTxnLimitPaise.coerceIn(100L, clampedDaily)
        val docRef = db.collection("users").document(uid)
        docRef.update(
            mapOf(
                "biometricLockEnabled" to biometricLockEnabled,
                "dailyLimitPaise" to clampedDaily,
                "perTxnLimitPaise" to clampedPerTxn,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "users/${auth.currentUser?.uid}", auth)
    }

    fun observeTransactions(userId: String): Flow<List<UpiTransaction>> =
        db.collection("users").document(userId).collection("transactions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(UpiTransaction::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(error, OperationType.LIST, "users/$userId/transactions", auth)
                }
                throw error
            }

    suspend fun getTransaction(txId: String): Result<UpiTransaction?> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("transactions").document(txId)
        docRef.get().await()
            .toObject(UpiTransaction::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.GET, "users/${auth.currentUser?.uid}/transactions/$txId", auth)
    }

    fun observeBeneficiaries(userId: String): Flow<List<Beneficiary>> =
        db.collection("users").document(userId).collection("beneficiaries")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(Beneficiary::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                if (error is CancellationException && error.cause == null) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(error, OperationType.LIST, "users/$userId/beneficiaries", auth)
                }
                throw error
            }

    suspend fun recordInitiatedTransaction(tx: UpiTransaction): Result<String> = runCatching {
        val uid = requireUserId()
        val txId = tx.id.ifBlank { "tx_${UUID.randomUUID().toString().replace("-", "").take(20)}" }
        val docRef = db.collection("users").document(uid).collection("transactions").document(txId)
        docRef.set(tx.copy(id = txId, userId = uid).toClientCreateMap()).await()
        txId
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.CREATE, "users/${auth.currentUser?.uid}/transactions", auth)
    }

    suspend fun updateClientTransactionState(
        txId: String,
        newStatus: String,
        failureReason: String = "",
        pspAppPackage: String = "",
    ): Result<Unit> = runCatching {
        val uid = requireUserId()
        require(newStatus != TransactionStatus.VERIFIED_SUCCESS) {
            "Client cannot set VERIFIED_SUCCESS; provider confirmation is required."
        }
        val docRef = db.collection("users").document(uid).collection("transactions").document(txId)
        docRef.update(
            mapOf(
                "status" to newStatus,
                "failureReason" to failureReason.take(240),
                "pspAppPackage" to pspAppPackage.take(128),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        ).await()
        Unit
    }.onFailure { e ->
        if (e is CancellationException) throw e
        handleFirestoreError(e, OperationType.UPDATE, "users/${auth.currentUser?.uid}/transactions/$txId", auth)
    }

    suspend fun initiateAuthorizedPayment(
        qrPayload: UpiQrPayload,
        amountPaise: Long,
        note: String,
        profile: UserProfile,
        recentTransactions: List<UpiTransaction>,
        knownBeneficiaries: List<Beneficiary>,
        packageName: String,
        customBackendUrl: String = "",
    ): PaymentDispatchOutcome {
        val uid = requireUserId()
        val eval = FraudAndLimitsEngine.evaluate(
            payeeVpa = qrPayload.payeeVpa,
            amountPaise = amountPaise,
            isVerifiedMerchant = qrPayload.isVerifiedMerchant,
            profile = profile,
            recentTransactions = recentTransactions,
            knownBeneficiaries = knownBeneficiaries,
        )

        val flagNames = eval.flags.map { it.name }

        if (eval.isBlocked) {
            val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(20)}"
            val blockedTx = UpiTransaction(
                id = txId,
                userId = uid,
                pspOrderId = "FRAUD_BLOCK_${txId.takeLast(8)}",
                txRef = qrPayload.txRef.ifBlank { "PRECHECK_${txId.takeLast(8)}" },
                payeeVpa = qrPayload.payeeVpa,
                payeeName = qrPayload.payeeName,
                merchantCode = qrPayload.merchantCode,
                amountPaise = amountPaise,
                note = note,
                status = TransactionStatus.BLOCKED_FRAUD,
                pspVerified = false,
                utrNumber = "",
                failureReason = "Blocked by Fraud & Limits Engine: ${flagNames.joinToString(", ")}",
                fraudRiskScore = eval.riskScore,
                fraudFlags = flagNames,
            )
            recordInitiatedTransaction(blockedTx)
            return PaymentDispatchOutcome.FraudBlocked(txId, eval)
        }

        if (!pspGateway.isConfigured(customBackendUrl)) {
            val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(20)}"
            val reason = "Authorized PSP backend URL & bank/NPCI merchant approvals are not configured in this build. Genuine UPI transfers require a live HTTPS PSP gateway."
            val unconfiguredTx = UpiTransaction(
                id = txId,
                userId = uid,
                pspOrderId = "PSP_PENDING_SETUP_${txId.takeLast(8)}",
                txRef = qrPayload.txRef.ifBlank { "UNREGISTERED_${txId.takeLast(8)}" },
                payeeVpa = qrPayload.payeeVpa,
                payeeName = qrPayload.payeeName,
                merchantCode = qrPayload.merchantCode,
                amountPaise = amountPaise,
                note = note,
                status = TransactionStatus.PSP_UNAVAILABLE,
                pspVerified = false,
                utrNumber = "",
                failureReason = reason,
                fraudRiskScore = eval.riskScore,
                fraudFlags = flagNames,
            )
            recordInitiatedTransaction(unconfiguredTx)
            return PaymentDispatchOutcome.PspApprovalRequired(txId, reason)
        }

        val idToken = runCatching { auth.currentUser?.getIdToken(false)?.await()?.token }
            .getOrNull().orEmpty()
        if (idToken.isBlank()) {
            return PaymentDispatchOutcome.GatewayError(
                transactionId = "",
                code = "AUTH_TOKEN_MISSING",
                message = "Unable to obtain authenticated Firebase ID token for PSP API.",
            )
        }

        val fingerprint = cryptoManager.computeDeviceFingerprint(uid, packageName)
        val gatewayResult = pspGateway.initiatePayment(
            idToken = idToken,
            deviceFingerprint = fingerprint,
            request = InitiatePaymentRequest(
                payeeVpa = qrPayload.payeeVpa,
                payeeName = qrPayload.payeeName,
                merchantCode = qrPayload.merchantCode,
                amountPaise = amountPaise,
                note = note,
                qrSignature = qrPayload.signature,
                clientRiskScore = eval.riskScore,
                clientFraudFlags = flagNames,
            ),
            customBaseUrl = customBackendUrl,
        )

        return when (gatewayResult) {
            is PspGatewayResult.Success -> {
                val res = gatewayResult.data
                val txId = res.pspOrderId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120)
                val initiated = UpiTransaction(
                    id = txId,
                    userId = uid,
                    pspOrderId = res.pspOrderId,
                    txRef = res.txRef,
                    payeeVpa = qrPayload.payeeVpa,
                    payeeName = qrPayload.payeeName,
                    merchantCode = qrPayload.merchantCode,
                    amountPaise = amountPaise,
                    note = note,
                    status = TransactionStatus.AWAITING_PSP_CONFIRMATION,
                    pspVerified = false,
                    utrNumber = "",
                    failureReason = "",
                    fraudRiskScore = res.fraudRiskScore,
                    fraudFlags = res.fraudFlags,
                )
                recordInitiatedTransaction(initiated)
                upsertBeneficiary(uid, qrPayload.payeeVpa, qrPayload.payeeName, qrPayload.isVerifiedMerchant)
                PaymentDispatchOutcome.LaunchAuthorizedUpiApp(
                    transactionId = txId,
                    pspOrderId = res.pspOrderId,
                    txRef = res.txRef,
                    signedUpiIntentUri = res.upiIntentUri,
                    providerName = res.providerName,
                )
            }

            is PspGatewayResult.NotConfigured -> {
                val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(20)}"
                val tx = UpiTransaction(
                    id = txId,
                    userId = uid,
                    pspOrderId = "PSP_PENDING_SETUP_${txId.takeLast(8)}",
                    txRef = qrPayload.txRef.ifBlank { "UNREGISTERED_${txId.takeLast(8)}" },
                    payeeVpa = qrPayload.payeeVpa,
                    payeeName = qrPayload.payeeName,
                    merchantCode = qrPayload.merchantCode,
                    amountPaise = amountPaise,
                    note = note,
                    status = TransactionStatus.PSP_UNAVAILABLE,
                    pspVerified = false,
                    utrNumber = "",
                    failureReason = gatewayResult.reason,
                    fraudRiskScore = eval.riskScore,
                    fraudFlags = flagNames,
                )
                recordInitiatedTransaction(tx)
                PaymentDispatchOutcome.PspApprovalRequired(txId, gatewayResult.reason)
            }

            is PspGatewayResult.Error -> {
                val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(20)}"
                val tx = UpiTransaction(
                    id = txId,
                    userId = uid,
                    pspOrderId = "PSP_ERR_${txId.takeLast(8)}",
                    txRef = qrPayload.txRef.ifBlank { "ERR_${txId.takeLast(8)}" },
                    payeeVpa = qrPayload.payeeVpa,
                    payeeName = qrPayload.payeeName,
                    merchantCode = qrPayload.merchantCode,
                    amountPaise = amountPaise,
                    note = note,
                    status = TransactionStatus.FAILED,
                    pspVerified = false,
                    utrNumber = "",
                    failureReason = "${gatewayResult.code}: ${gatewayResult.message}",
                    fraudRiskScore = eval.riskScore,
                    fraudFlags = flagNames,
                )
                recordInitiatedTransaction(tx)
                PaymentDispatchOutcome.GatewayError(txId, gatewayResult.code, gatewayResult.message)
            }
        }
    }

    suspend fun verifyPaymentStatusWithPsp(
        tx: UpiTransaction,
        packageName: String,
        customBackendUrl: String = "",
    ): VerificationOutcome {
        val uid = requireUserId()
        if (!pspGateway.isConfigured(customBackendUrl)) {
            return VerificationOutcome.Unavailable(
                "PSP backend URL is not configured. Server-to-server status verification requires an authorized HTTPS PSP gateway."
            )
        }
        val idToken = runCatching { auth.currentUser?.getIdToken(false)?.await()?.token }
            .getOrNull().orEmpty()
        if (idToken.isBlank()) {
            return VerificationOutcome.Unavailable("Authentication token unavailable.")
        }

        val fingerprint = cryptoManager.computeDeviceFingerprint(uid, packageName)
        return when (val result = pspGateway.verifyPaymentStatus(idToken, fingerprint, tx.pspOrderId, customBackendUrl)) {
            is PspGatewayResult.Success -> {
                val res = result.data
                // Strict rule: ONLY treat as VERIFIED_SUCCESS if PSP confirms pspVerified == true AND provides real UTR
                if (res.pspVerified && res.status == TransactionStatus.VERIFIED_SUCCESS && res.utrNumber.isNotBlank()) {
                    VerificationOutcome.VerifiedSuccess(
                        tx.copy(
                            status = TransactionStatus.VERIFIED_SUCCESS,
                            pspVerified = true,
                            utrNumber = res.utrNumber,
                            failureReason = "",
                        )
                    )
                } else if (res.status == TransactionStatus.FAILED || res.status == TransactionStatus.USER_CANCELLED) {
                    val reason = res.failureReason.ifBlank { "Payment declined or failed at PSP/Bank." }
                    updateClientTransactionState(tx.id, TransactionStatus.FAILED, reason, tx.pspAppPackage)
                    VerificationOutcome.Failed(
                        tx.copy(status = TransactionStatus.FAILED, failureReason = reason),
                        reason,
                    )
                } else {
                    updateClientTransactionState(
                        tx.id,
                        TransactionStatus.PENDING_VERIFICATION,
                        res.failureReason,
                        tx.pspAppPackage,
                    )
                    VerificationOutcome.Pending(
                        tx.copy(status = TransactionStatus.PENDING_VERIFICATION),
                        res.failureReason.ifBlank { "Awaiting final settlement confirmation from NPCI/PSP." },
                    )
                }
            }
            is PspGatewayResult.NotConfigured -> VerificationOutcome.Unavailable(result.reason)
            is PspGatewayResult.Error -> VerificationOutcome.Unavailable("${result.code}: ${result.message}")
        }
    }

    private suspend fun upsertBeneficiary(
        uid: String,
        vpa: String,
        merchantName: String,
        isVerifiedMerchant: Boolean,
    ) {
        val vpaId = MessageDigest.getInstance("SHA-256")
            .digest(vpa.lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        val docRef = db.collection("users").document(uid).collection("beneficiaries").document(vpaId)
        runCatching {
            val snap = docRef.get().await()
            if (snap.exists()) {
                docRef.update(
                    mapOf(
                        "merchantName" to merchantName.take(120),
                        "isVerifiedMerchant" to isVerifiedMerchant,
                        "lastPaidAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                val beneficiary = Beneficiary(
                    id = vpaId,
                    userId = uid,
                    vpa = vpa,
                    merchantName = merchantName,
                    isVerifiedMerchant = isVerifiedMerchant,
                )
                docRef.set(beneficiary.toCreateMap()).await()
            }
        }
    }
}
