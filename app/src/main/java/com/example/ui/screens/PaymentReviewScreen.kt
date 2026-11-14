package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.R
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.data.model.UserProfile
import com.example.domain.upi.UpiIntentLauncher
import com.example.domain.upi.UpiQrParser
import com.example.domain.upi.UpiQrPayload
import com.example.ui.components.TransactionStatusPill
import com.example.ui.components.formatInr
import com.example.ui.theme.FailureCrimson
import com.example.ui.theme.FailureCrimsonBg
import com.example.ui.theme.IndigoRoyal
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PendingAmber
import com.example.ui.theme.PendingAmberBg
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.theme.VerifiedEmeraldBg
import com.example.ui.viewmodel.ActivePaymentFlowState
import com.example.ui.viewmodel.PhonePayViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaymentReviewScreen(
    qrPayload: UpiQrPayload,
    profile: UserProfile,
    activeFlow: ActivePaymentFlowState,
    viewModel: PhonePayViewModel,
    onSharePdfReceipt: (UpiTransaction) -> Unit,
    onDone: () -> Unit,
) {
    BackHandler { onDone() }

    val context = LocalContext.current
    var amountText by remember(qrPayload) {
        mutableStateOf(
            qrPayload.amountPaise?.let { paise ->
                if (paise % 100L == 0L) (paise / 100L).toString() else String.format("%.2f", paise / 100.0)
            } ?: ""
        )
    }
    var noteText by remember(qrPayload) { mutableStateOf(qrPayload.note) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    val upiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        val awaiting = activeFlow as? ActivePaymentFlowState.AwaitingUpiAppReturn
        if (awaiting != null) {
            val installed = UpiIntentLauncher.discoverInstalledUpiApps(context)
            val pkg = installed.firstOrNull()?.packageName.orEmpty()
            viewModel.onUpiAppReturned(
                transactionId = awaiting.transactionId,
                pspAppPackage = pkg,
                noUpiAppInstalled = false,
            )
        }
    }

    LaunchedEffect(activeFlow) {
        if (activeFlow is ActivePaymentFlowState.AwaitingUpiAppReturn) {
            val installedApps = UpiIntentLauncher.discoverInstalledUpiApps(context)
            if (installedApps.isEmpty()) {
                viewModel.onUpiAppReturned(
                    transactionId = activeFlow.transactionId,
                    pspAppPackage = "",
                    noUpiAppInstalled = true,
                )
            } else {
                val baseIntent = UpiIntentLauncher.buildUpiPaymentIntent(activeFlow.signedUpiIntentUri)
                val chooser = Intent.createChooser(baseIntent, "Complete UPI PIN in Authorized PSP")
                upiLauncher.launch(chooser)
            }
        }
    }

    if (activeFlow is ActivePaymentFlowState.ResultScreen) {
        PaymentResultScreen(
            state = activeFlow,
            onVerifyClick = { viewModel.verifyTransactionWithPsp(activeFlow.transaction) },
            onSharePdfClick = { onSharePdfReceipt(activeFlow.transaction) },
            onDoneClick = onDone,
        )
        return
    }

    val parsedPaise = UpiQrParser.parseRupeesToPaise(amountText)
    val evaluation = parsedPaise?.let { viewModel.evaluateDraftAmount(qrPayload, it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pay_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onDone,
                        modifier = Modifier.testTag("payment_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 580.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Merchant / Payee Identity Card
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = qrPayload.payeeName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (qrPayload.isVerifiedMerchant) {
                                Surface(
                                    color = VerifiedEmeraldBg,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Verified,
                                            contentDescription = null,
                                            tint = VerifiedEmerald,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.pay_verified_merchant, qrPayload.merchantCode),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = VerifiedEmerald,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    color = PendingAmberBg,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.pay_personal_vpa),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PendingAmber,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = qrPayload.payeeVpa,
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (qrPayload.isSignedQr) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.GppGood,
                                    contentDescription = null,
                                    tint = VerifiedEmerald,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.pay_signed_qr),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VerifiedEmerald,
                                )
                            }
                        }
                    }
                }

                // Amount & Note Input Card
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { if (!qrPayload.isFixedAmount) amountText = it },
                            readOnly = qrPayload.isFixedAmount,
                            label = { Text(stringResource(R.string.pay_amount_label)) },
                            prefix = { Text("₹ ", fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("payment_amount_input"),
                        )

                        if (!qrPayload.isFixedAmount) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf("100", "500", "2000", "5000").forEach { preset ->
                                    AssistChip(
                                        onClick = { amountText = preset },
                                        label = { Text("₹$preset", fontFamily = JetBrainsMonoFamily) },
                                        modifier = Modifier.testTag("quick_amount_$preset"),
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it.take(120) },
                            label = { Text(stringResource(R.string.pay_note_label)) },
                            placeholder = { Text(stringResource(R.string.pay_note_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("payment_note_input"),
                        )
                    }
                }

                // Live Fraud & Limits Evaluation Card
                if (evaluation != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (evaluation.isBlocked) FailureCrimsonBg else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (evaluation.isBlocked) Icons.Filled.Warning else Icons.Filled.Shield,
                                    contentDescription = null,
                                    tint = if (evaluation.isBlocked) FailureCrimson else IndigoRoyal,
                                )
                                Text(
                                    text = if (evaluation.isBlocked) {
                                        stringResource(R.string.pay_fraud_blocked)
                                    } else {
                                        stringResource(R.string.pay_fraud_score, evaluation.riskScore)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (evaluation.isBlocked) FailureCrimson else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (evaluation.isNewPayeeCoolingActive) {
                                Text(
                                    text = stringResource(R.string.pay_cooling_notice),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PendingAmber,
                                )
                            }
                            if (evaluation.flags.isNotEmpty()) {
                                Text(
                                    text = evaluation.flags.joinToString(" • ") { it.name },
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = FailureCrimson,
                                )
                            }
                            Text(
                                text = "Max allowed right now: ${formatInr(evaluation.effectiveMaxSinglePaise)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // UPI PIN Security Notice
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = IndigoRoyal)
                        Text(
                            text = stringResource(R.string.pay_pin_disclaimer),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                if (biometricError != null) {
                    Surface(
                        color = FailureCrimsonBg,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = biometricError.orEmpty(),
                            color = FailureCrimson,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                val isProcessing = activeFlow is ActivePaymentFlowState.Processing ||
                    activeFlow is ActivePaymentFlowState.AwaitingUpiAppReturn

                Button(
                    onClick = {
                        biometricError = null
                        val validPaise = parsedPaise ?: return@Button
                        val activity = context as? FragmentActivity
                        if (profile.biometricLockEnabled && activity != null) {
                            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
                            if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                                val prompt = BiometricPrompt(
                                    activity,
                                    ContextCompat.getMainExecutor(activity),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            viewModel.initiatePayment(qrPayload, validPaise, noteText.trim())
                                        }

                                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                            biometricError = errString.toString()
                                        }
                                    },
                                )
                                val info = BiometricPrompt.PromptInfo.Builder()
                                    .setTitle("Authorize UPI Payment")
                                    .setSubtitle("${qrPayload.payeeName} • ${formatInr(validPaise)}")
                                    .setAllowedAuthenticators(authenticators)
                                    .build()
                                prompt.authenticate(info)
                                return@Button
                            }
                        }
                        viewModel.initiatePayment(qrPayload, validPaise, noteText.trim())
                    },
                    enabled = parsedPaise != null && !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoRoyal),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("pay_proceed_button"),
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.pay_proceed_cta),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

private data class ResultBadgeMeta(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: androidx.compose.ui.graphics.Color,
    val bg: androidx.compose.ui.graphics.Color,
    val title: String,
)

@Composable
private fun PaymentResultScreen(
    state: ActivePaymentFlowState.ResultScreen,
    onVerifyClick: () -> Unit,
    onSharePdfClick: () -> Unit,
    onDoneClick: () -> Unit,
) {
    val tx = state.transaction
    val meta = when {
        tx.status == TransactionStatus.VERIFIED_SUCCESS && tx.pspVerified -> ResultBadgeMeta(
            Icons.Filled.CheckCircle,
            VerifiedEmerald,
            VerifiedEmeraldBg,
            stringResource(R.string.result_success_title),
        )
        state.isPspUnconfigured || tx.status == TransactionStatus.PSP_UNAVAILABLE -> ResultBadgeMeta(
            Icons.Filled.Shield,
            PendingAmber,
            PendingAmberBg,
            stringResource(R.string.result_psp_required_title),
        )
        tx.status == TransactionStatus.PENDING_VERIFICATION || tx.status == TransactionStatus.AWAITING_PSP_CONFIRMATION -> ResultBadgeMeta(
            Icons.Filled.HourglassTop,
            PendingAmber,
            PendingAmberBg,
            stringResource(R.string.result_pending_title),
        )
        state.isFraudBlocked || tx.status == TransactionStatus.BLOCKED_FRAUD -> ResultBadgeMeta(
            Icons.Filled.Warning,
            FailureCrimson,
            FailureCrimsonBg,
            stringResource(R.string.result_fraud_title),
        )
        else -> ResultBadgeMeta(
            Icons.Filled.Error,
            FailureCrimson,
            FailureCrimsonBg,
            stringResource(R.string.result_failed_title),
        )
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(meta.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = meta.title,
                        tint = meta.tint,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Text(
                    text = meta.title,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = formatInr(tx.amountPaise),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )

                TransactionStatusPill(tx.status)

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = state.detailMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        HorizontalDivider()
                        ResultRow("Payee", "${tx.payeeName} (${tx.payeeVpa})")
                        ResultRow(stringResource(R.string.result_order_label), tx.pspOrderId)
                        ResultRow(stringResource(R.string.result_txref_label), tx.txRef)
                        ResultRow(
                            stringResource(R.string.result_utr_label),
                            tx.utrNumber.ifBlank { "Not issued (unconfirmed)" },
                        )
                    }
                }

                if (tx.status == TransactionStatus.PENDING_VERIFICATION || tx.status == TransactionStatus.AWAITING_PSP_CONFIRMATION) {
                    OutlinedButton(
                        onClick = onVerifyClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("result_verify_status_button"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.result_verify_cta))
                    }
                }

                OutlinedButton(
                    onClick = onSharePdfClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("result_share_pdf_button"),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.result_share_receipt_cta))
                }

                Button(
                    onClick = onDoneClick,
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoRoyal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("result_done_button"),
                ) {
                    Text(stringResource(R.string.result_done_cta))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
