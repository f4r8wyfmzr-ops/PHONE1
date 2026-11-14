package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.example.ui.theme.FailureCrimson
import com.example.ui.theme.FailureCrimsonBg
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.PendingAmber
import com.example.ui.theme.PendingAmberBg
import com.example.ui.theme.VerifiedEmerald
import com.example.ui.theme.VerifiedEmeraldBg
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatInr(paise: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
    return formatter.format(paise / 100.0)
}

@Composable
fun TransactionStatusPill(status: String) {
    val (bg, fg, label) = when (status) {
        TransactionStatus.VERIFIED_SUCCESS -> Triple(VerifiedEmeraldBg, VerifiedEmerald, "VERIFIED")
        TransactionStatus.PENDING_VERIFICATION,
        TransactionStatus.AWAITING_PSP_CONFIRMATION,
        TransactionStatus.INITIATED,
        -> Triple(PendingAmberBg, PendingAmber, "PENDING")
        TransactionStatus.BLOCKED_FRAUD -> Triple(FailureCrimsonBg, FailureCrimson, "BLOCKED")
        TransactionStatus.PSP_UNAVAILABLE -> Triple(PendingAmberBg, PendingAmber, "PSP SETUP")
        else -> Triple(FailureCrimsonBg, FailureCrimson, status)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun TransactionRowCard(
    tx: UpiTransaction,
    onClick: () -> Unit,
) {
    val dateLabel = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        .format(tx.createdAt?.toDate() ?: Date())

    val (icon, tint, iconBg) = when (tx.status) {
        TransactionStatus.VERIFIED_SUCCESS -> Triple(Icons.Filled.CheckCircle, VerifiedEmerald, VerifiedEmeraldBg)
        TransactionStatus.PENDING_VERIFICATION,
        TransactionStatus.AWAITING_PSP_CONFIRMATION,
        TransactionStatus.INITIATED,
        -> Triple(Icons.Filled.HourglassTop, PendingAmber, PendingAmberBg)
        TransactionStatus.BLOCKED_FRAUD -> Triple(Icons.Filled.Security, FailureCrimson, FailureCrimsonBg)
        else -> Triple(Icons.Filled.Error, FailureCrimson, FailureCrimsonBg)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("transaction_card_${tx.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tx.status,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.payeeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tx.payeeVpa,
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatInr(tx.amountPaise),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TransactionStatusPill(tx.status)
            }
        }
    }
}

@Composable
fun ReceiptDetailDialog(
    tx: UpiTransaction,
    onSharePdf: () -> Unit,
    onVerifyStatus: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateLabel = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
        .format(tx.createdAt?.toDate() ?: Date())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = tx.payeeName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatInr(tx.amountPaise),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    TransactionStatusPill(tx.status)
                }
                HorizontalDivider()
                ReceiptFieldRow("UPI ID (VPA)", tx.payeeVpa, mono = true)
                ReceiptFieldRow(stringResource(R.string.result_order_label), tx.pspOrderId, mono = true)
                ReceiptFieldRow(stringResource(R.string.result_txref_label), tx.txRef, mono = true)
                ReceiptFieldRow(
                    stringResource(R.string.result_utr_label),
                    tx.utrNumber.ifBlank { "Unconfirmed" },
                    mono = true,
                )
                ReceiptFieldRow("PSP Verified", if (tx.pspVerified) "Yes" else "No", mono = false)
                ReceiptFieldRow("Timestamp", dateLabel, mono = false)
                if (tx.note.isNotBlank()) {
                    ReceiptFieldRow("Note", tx.note, mono = false)
                }
                if (tx.failureReason.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = tx.failureReason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSharePdf,
                modifier = Modifier.testTag("receipt_share_pdf_button"),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.result_share_receipt_cta))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!tx.pspVerified && (tx.status == TransactionStatus.PENDING_VERIFICATION || tx.status == TransactionStatus.AWAITING_PSP_CONFIRMATION)) {
                    OutlinedButton(
                        onClick = onVerifyStatus,
                        modifier = Modifier.testTag("receipt_verify_button"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.result_verify_cta))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        },
    )
}

@Composable
private fun ReceiptFieldRow(label: String, value: String, mono: Boolean) {
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
            fontFamily = if (mono) JetBrainsMonoFamily else null,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
