package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.PreferencesStore
import com.example.data.model.Beneficiary
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.data.model.UserProfile
import com.example.data.network.PspNetworkClient
import com.example.data.repository.PaymentDispatchOutcome
import com.example.data.repository.PhonePayRepository
import com.example.data.repository.VerificationOutcome
import com.example.domain.upi.FraudAndLimitsEngine
import com.example.domain.upi.FraudEvaluation
import com.example.domain.upi.UpiQrPayload
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface ActivePaymentFlowState {
    data object Idle : ActivePaymentFlowState
    data object Processing : ActivePaymentFlowState
    data class AwaitingUpiAppReturn(
        val transactionId: String,
        val pspOrderId: String,
        val txRef: String,
        val signedUpiIntentUri: String,
        val providerName: String,
    ) : ActivePaymentFlowState
    data class ResultScreen(
        val transaction: UpiTransaction,
        val headline: String,
        val detailMessage: String,
        val isPspUnconfigured: Boolean = false,
        val isFraudBlocked: Boolean = false,
    ) : ActivePaymentFlowState
}

class PhonePayViewModel(
    private val repository: PhonePayRepository,
    private val preferencesStore: PreferencesStore,
    private val currentUserId: String,
    private val userDisplayName: String,
    private val userEmail: String,
    private val packageName: String,
) : ViewModel() {

    private val pspNetworkClient = PspNetworkClient()

    val profileState: StateFlow<UiState<UserProfile>> = repository.observeProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile>> { profile ->
            UiState.Success(
                profile ?: UserProfile(
                    id = currentUserId,
                    userId = currentUserId,
                    displayName = userDisplayName,
                    email = userEmail,
                )
            )
        }
        .catch { error ->
            val fsErr = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsErr == null && error is CancellationException) throw error
            val root = fsErr ?: error
            Log.w(TAG, "Profile stream error", root)
            emit(UiState.Error(root.message ?: "Failed to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val transactionsState: StateFlow<UiState<List<UpiTransaction>>> = repository.observeTransactions(currentUserId)
        .map<List<UpiTransaction>, UiState<List<UpiTransaction>>> { UiState.Success(it) }
        .catch { error ->
            val fsErr = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsErr == null && error is CancellationException) throw error
            val root = fsErr ?: error
            Log.w(TAG, "Transactions stream error", root)
            emit(UiState.Error(root.message ?: "Failed to load transactions"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val beneficiariesState: StateFlow<List<Beneficiary>> = repository.observeBeneficiaries(currentUserId)
        .catch { error ->
            if (error is CancellationException && error.cause == null) throw error
            Log.w(TAG, "Beneficiaries stream error", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val customBackendUrl: StateFlow<String> = preferencesStore.customBackendUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val isPspConfigured: StateFlow<Boolean> = customBackendUrl
        .map { pspNetworkClient.isConfigured(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), pspNetworkClient.isConfigured(""))

    val dailySummary: StateFlow<FraudEvaluation> = combine(
        profileState,
        transactionsState,
        beneficiariesState,
    ) { profUi, txUi, bens ->
        val profile = (profUi as? UiState.Success)?.data ?: UserProfile(userId = currentUserId)
        val txs = (txUi as? UiState.Success)?.data ?: emptyList()
        FraudAndLimitsEngine.evaluate(
            payeeVpa = "summary@upi",
            amountPaise = 100L,
            isVerifiedMerchant = true,
            profile = profile,
            recentTransactions = txs,
            knownBeneficiaries = bens,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        FraudAndLimitsEngine.evaluate(
            payeeVpa = "summary@upi",
            amountPaise = 100L,
            isVerifiedMerchant = true,
            profile = UserProfile(userId = currentUserId),
            recentTransactions = emptyList(),
            knownBeneficiaries = emptyList(),
        ),
    )

    private val _selectedQr = MutableStateFlow<UpiQrPayload?>(null)
    val selectedQr: StateFlow<UpiQrPayload?> = _selectedQr.asStateFlow()

    private val _activeFlow = MutableStateFlow<ActivePaymentFlowState>(ActivePaymentFlowState.Idle)
    val activeFlow: StateFlow<ActivePaymentFlowState> = _activeFlow.asStateFlow()

    private val _selectedReceiptTx = MutableStateFlow<UpiTransaction?>(null)
    val selectedReceiptTx: StateFlow<UpiTransaction?> = _selectedReceiptTx.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureProfileInitialized(
                displayName = userDisplayName,
                email = userEmail,
            )
        }
    }

    fun selectQrPayload(payload: UpiQrPayload) {
        _selectedQr.value = payload
        _activeFlow.value = ActivePaymentFlowState.Idle
    }

    fun clearActivePayment() {
        _activeFlow.value = ActivePaymentFlowState.Idle
        _selectedQr.value = null
    }

    fun openReceipt(tx: UpiTransaction) {
        _selectedReceiptTx.value = tx
    }

    fun closeReceipt() {
        _selectedReceiptTx.value = null
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    fun evaluateDraftAmount(qr: UpiQrPayload, amountPaise: Long): FraudEvaluation {
        val profile = (profileState.value as? UiState.Success)?.data ?: UserProfile(userId = currentUserId)
        val txs = (transactionsState.value as? UiState.Success)?.data ?: emptyList()
        val bens = beneficiariesState.value
        return FraudAndLimitsEngine.evaluate(
            payeeVpa = qr.payeeVpa,
            amountPaise = amountPaise,
            isVerifiedMerchant = qr.isVerifiedMerchant,
            profile = profile,
            recentTransactions = txs,
            knownBeneficiaries = bens,
        )
    }

    fun initiatePayment(qr: UpiQrPayload, amountPaise: Long, note: String) {
        val profile = (profileState.value as? UiState.Success)?.data ?: UserProfile(userId = currentUserId)
        val txs = (transactionsState.value as? UiState.Success)?.data ?: emptyList()
        val bens = beneficiariesState.value

        viewModelScope.launch {
            _activeFlow.value = ActivePaymentFlowState.Processing
            when (val outcome = repository.initiateAuthorizedPayment(
                qrPayload = qr,
                amountPaise = amountPaise,
                note = note,
                profile = profile,
                recentTransactions = txs,
                knownBeneficiaries = bens,
                packageName = packageName,
                customBackendUrl = customBackendUrl.value,
            )) {
                is PaymentDispatchOutcome.LaunchAuthorizedUpiApp -> {
                    _activeFlow.value = ActivePaymentFlowState.AwaitingUpiAppReturn(
                        transactionId = outcome.transactionId,
                        pspOrderId = outcome.pspOrderId,
                        txRef = outcome.txRef,
                        signedUpiIntentUri = outcome.signedUpiIntentUri,
                        providerName = outcome.providerName,
                    )
                }

                is PaymentDispatchOutcome.FraudBlocked -> {
                    val tx = repository.getTransaction(outcome.transactionId).getOrNull()
                        ?: UpiTransaction(
                            id = outcome.transactionId,
                            userId = currentUserId,
                            pspOrderId = "FRAUD_BLOCK",
                            txRef = qr.txRef,
                            payeeVpa = qr.payeeVpa,
                            payeeName = qr.payeeName,
                            amountPaise = amountPaise,
                            status = TransactionStatus.BLOCKED_FRAUD,
                            fraudRiskScore = outcome.evaluation.riskScore,
                            fraudFlags = outcome.evaluation.flags.map { it.name },
                        )
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = tx,
                        headline = "Blocked by Fraud & Limits Engine",
                        detailMessage = "Flags: ${outcome.evaluation.flags.joinToString(", ") { it.name }}",
                        isFraudBlocked = true,
                    )
                }

                is PaymentDispatchOutcome.PspApprovalRequired -> {
                    val tx = repository.getTransaction(outcome.transactionId).getOrNull()
                        ?: UpiTransaction(
                            id = outcome.transactionId,
                            userId = currentUserId,
                            pspOrderId = "PSP_PENDING_SETUP",
                            txRef = qr.txRef.ifBlank { "UNREGISTERED" },
                            payeeVpa = qr.payeeVpa,
                            payeeName = qr.payeeName,
                            merchantCode = qr.merchantCode,
                            amountPaise = amountPaise,
                            note = note,
                            status = TransactionStatus.PSP_UNAVAILABLE,
                            failureReason = outcome.reason,
                        )
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = tx,
                        headline = "PSP & Bank Approval Required",
                        detailMessage = outcome.reason,
                        isPspUnconfigured = true,
                    )
                }

                is PaymentDispatchOutcome.GatewayError -> {
                    val tx = repository.getTransaction(outcome.transactionId).getOrNull()
                        ?: UpiTransaction(
                            id = outcome.transactionId,
                            userId = currentUserId,
                            pspOrderId = "PSP_ERR",
                            txRef = qr.txRef,
                            payeeVpa = qr.payeeVpa,
                            payeeName = qr.payeeName,
                            amountPaise = amountPaise,
                            status = TransactionStatus.FAILED,
                            failureReason = "${outcome.code}: ${outcome.message}",
                        )
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = tx,
                        headline = "Payment Initiation Failed",
                        detailMessage = "${outcome.code}: ${outcome.message}",
                    )
                }
            }
        }
    }

    fun onUpiAppReturned(
        transactionId: String,
        pspAppPackage: String,
        noUpiAppInstalled: Boolean = false,
    ) {
        viewModelScope.launch {
            if (noUpiAppInstalled) {
                val reason = "No RBI-authorized UPI PSP app (BHIM, GPay, PhonePe, Bank UPI) found on this device to enter UPI PIN."
                repository.updateClientTransactionState(
                    txId = transactionId,
                    newStatus = TransactionStatus.FAILED,
                    failureReason = reason,
                )
                val tx = repository.getTransaction(transactionId).getOrNull()
                    ?: UpiTransaction(id = transactionId, status = TransactionStatus.FAILED, failureReason = reason)
                _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                    transaction = tx,
                    headline = "Authorized UPI App Not Found",
                    detailMessage = reason,
                )
                return@launch
            }

            // Never trust Android Intent extras for settlement; always verify with PSP backend
            repository.updateClientTransactionState(
                txId = transactionId,
                newStatus = TransactionStatus.PENDING_VERIFICATION,
                pspAppPackage = pspAppPackage,
            )
            val tx = repository.getTransaction(transactionId).getOrNull()
            if (tx != null) {
                verifyTransactionWithPsp(tx)
            }
        }
    }

    fun verifyTransactionWithPsp(tx: UpiTransaction) {
        viewModelScope.launch {
            _activeFlow.value = ActivePaymentFlowState.Processing
            when (val outcome = repository.verifyPaymentStatusWithPsp(
                tx = tx,
                packageName = packageName,
                customBackendUrl = customBackendUrl.value,
            )) {
                is VerificationOutcome.VerifiedSuccess -> {
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = outcome.transaction,
                        headline = "Payment Confirmed by PSP",
                        detailMessage = "Verified by payment provider. UTR: ${outcome.transaction.utrNumber}",
                    )
                }
                is VerificationOutcome.Pending -> {
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = outcome.transaction,
                        headline = "Awaiting Provider Confirmation",
                        detailMessage = outcome.message,
                    )
                }
                is VerificationOutcome.Failed -> {
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = outcome.transaction,
                        headline = "Payment Failed",
                        detailMessage = outcome.reason,
                    )
                }
                is VerificationOutcome.Unavailable -> {
                    _activeFlow.value = ActivePaymentFlowState.ResultScreen(
                        transaction = tx,
                        headline = "Status Verification Unavailable",
                        detailMessage = outcome.message,
                        isPspUnconfigured = !pspNetworkClient.isConfigured(customBackendUrl.value),
                    )
                }
            }
        }
    }

    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            preferencesStore.setLanguage(languageCode)
            repository.updateLanguage(languageCode)
        }
    }

    fun saveSecuritySettings(
        biometricLockEnabled: Boolean,
        dailyLimitPaise: Long,
        perTxnLimitPaise: Long,
        backendUrl: String,
    ) {
        viewModelScope.launch {
            val trimmedUrl = backendUrl.trim()
            if (trimmedUrl.isNotBlank() && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
                _statusBannerMessage.value = "Encrypted HTTPS URL required (must start with https://)"
                return@launch
            }
            preferencesStore.setCustomBackendUrl(trimmedUrl)
            val res = repository.updateSecurityAndLimits(
                biometricLockEnabled = biometricLockEnabled,
                dailyLimitPaise = dailyLimitPaise,
                perTxnLimitPaise = perTxnLimitPaise,
            )
            _statusBannerMessage.value = if (res.isSuccess) {
                "Security settings saved"
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to update settings"
            }
        }
    }

    private companion object {
        const val TAG = "PhonePayVM"
    }
}
