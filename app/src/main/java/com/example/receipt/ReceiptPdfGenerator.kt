package com.example.receipt

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptPdfGenerator {
    fun generatePdfReceipt(context: Context, tx: UpiTransaction): Uri {
        val receiptsDir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val safeId = tx.id.ifBlank { tx.pspOrderId }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(receiptsDir, "PHONEPAY_Receipt_$safeId.pdf")

        val inrFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
        val amountFormatted = inrFormat.format(tx.amountPaise / 100.0)
        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a z", Locale.ENGLISH)
            .format(tx.createdAt?.toDate() ?: Date())

        val rows = listOf(
            "Payee / Merchant Name" to tx.payeeName,
            "Payee UPI ID (VPA)" to tx.payeeVpa,
            "PSP Order ID" to tx.pspOrderId,
            "NPCI Transaction Ref (tr)" to tx.txRef,
            "Provider Verified" to if (tx.pspVerified) "YES (Server-to-Server Confirmed)" else "NO (Not Confirmed by PSP)",
            "Bank UTR / RRN" to tx.utrNumber.ifBlank { "Unavailable (Unconfirmed)" },
            "Merchant Category (MCC)" to tx.merchantCode.ifBlank { "P2P / Unspecified" },
            "Note / Remarks" to tx.note.ifBlank { "-" },
            "Timestamp" to dateStr,
            "Fraud Risk Score" to "${tx.fraudRiskScore}/100",
        )

        val renderedNatively = runCatching {
            val pdf = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas

                val headerBg = Paint().apply { color = Color.parseColor("#1E1048") }
                canvas.drawRect(0f, 0f, 595f, 130f, headerBg)

                val titlePaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 26f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }
                canvas.drawText("PHONE PAY", 40f, 58f, titlePaint)

                val subPaint = Paint().apply {
                    color = Color.parseColor("#C7C1E8")
                    textSize = 13f
                    isAntiAlias = true
                }
                canvas.drawText("Official UPI Payment Receipt / Transaction Record", 40f, 86f, subPaint)

                val badgeColor = when (tx.status) {
                    TransactionStatus.VERIFIED_SUCCESS -> Color.parseColor("#0B875B")
                    TransactionStatus.PENDING_VERIFICATION, TransactionStatus.AWAITING_PSP_CONFIRMATION -> Color.parseColor("#C77700")
                    else -> Color.parseColor("#C62828")
                }
                val statusPaint = Paint().apply {
                    color = badgeColor
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }
                canvas.drawText("STATUS: ${tx.status}", 40f, 175f, statusPaint)

                val amountPaint = Paint().apply {
                    color = Color.parseColor("#1A103C")
                    textSize = 32f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }
                canvas.drawText(amountFormatted, 40f, 225f, amountPaint)

                val labelPaint = Paint().apply {
                    color = Color.parseColor("#6B6E82")
                    textSize = 12f
                    isAntiAlias = true
                }
                val valuePaint = Paint().apply {
                    color = Color.parseColor("#1B1D29")
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    isAntiAlias = true
                }
                val linePaint = Paint().apply {
                    color = Color.parseColor("#E3E5F0")
                    strokeWidth = 1f
                }

                var y = 270f
                for ((label, value) in rows) {
                    canvas.drawLine(40f, y - 18f, 555f, y - 18f, linePaint)
                    canvas.drawText(label, 40f, y, labelPaint)
                    canvas.drawText(value.take(62), 230f, y, valuePaint)
                    y += 38f
                }

                if (tx.failureReason.isNotBlank()) {
                    canvas.drawLine(40f, y - 18f, 555f, y - 18f, linePaint)
                    canvas.drawText("Provider / Audit Message", 40f, y, labelPaint)
                    canvas.drawText(tx.failureReason.take(65), 230f, y, valuePaint)
                }

                val footerPaint = Paint().apply {
                    color = Color.parseColor("#6B6E82")
                    textSize = 10f
                    isAntiAlias = true
                }
                canvas.drawText(
                    "Note: Genuine settlements are confirmed exclusively via RBI-regulated PSP webhook/status API.",
                    40f,
                    780f,
                    footerPaint,
                )

                pdf.finishPage(page)
                FileOutputStream(outFile).use { stream ->
                    pdf.writeTo(stream)
                }
            } finally {
                runCatching { pdf.close() }
            }
        }.isSuccess

        if (!renderedNatively) {
            writeFallbackPdf14(outFile, tx.status, amountFormatted, rows, tx.failureReason)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    private fun writeFallbackPdf14(
        outFile: File,
        status: String,
        amountFormatted: String,
        rows: List<Pair<String, String>>,
        failureReason: String,
    ) {
        fun escPdf(s: String): String =
            s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

        val lines = buildList {
            add("PHONE PAY - Official UPI Payment Receipt")
            add("STATUS: $status")
            add("AMOUNT: $amountFormatted")
            rows.forEach { (k, v) -> add("$k: $v") }
            if (failureReason.isNotBlank()) add("Provider / Audit Message: $failureReason")
        }
        val contentStream = buildString {
            append("BT /F1 12 Tf 40 780 Td 18 TL ")
            lines.forEach { line ->
                append("(${escPdf(line)}) Tj T* ")
            }
            append("ET")
        }
        val obj1 = "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
        val obj2 = "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
        val obj3 = "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n"
        val obj4 = "4 0 obj << /Length ${contentStream.toByteArray(Charsets.US_ASCII).size} >> stream\n$contentStream\nendstream endobj\n"
        val obj5 = "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n"
        val header = "%PDF-1.4\n"
        val body = header + obj1 + obj2 + obj3 + obj4 + obj5 + "trailer << /Root 1 0 R >>\n%%EOF\n"
        outFile.writeBytes(body.toByteArray(Charsets.US_ASCII))
    }
}
