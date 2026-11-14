package com.example.domain.upi

import android.net.Uri
import java.math.BigDecimal

data class UpiQrPayload(
    val payeeVpa: String,
    val payeeName: String,
    val amountPaise: Long?,
    val currency: String,
    val merchantCode: String,
    val txRef: String,
    val note: String,
    val mode: String,
    val orgId: String,
    val signature: String,
    val rawUri: String,
) {
    val isVerifiedMerchant: Boolean
        get() = merchantCode.isNotBlank() && merchantCode != "0000"

    val isSignedQr: Boolean
        get() = signature.isNotBlank()

    val isFixedAmount: Boolean
        get() = amountPaise != null && amountPaise > 0L
}

object UpiQrParser {
    private val VPA_REGEX = Regex("^[a-zA-Z0-9._\\-]{2,256}@[a-zA-Z0-9]{2,64}$")

    fun isValidVpa(vpa: String): Boolean = VPA_REGEX.matches(vpa.trim())

    fun parse(rawContent: String): Result<UpiQrPayload> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty QR payload"))
        }

        // Standard NPCI UPI QR URI: upi://pay?pa=...&pn=...
        if (trimmed.startsWith("upi://", ignoreCase = true)) {
            return parseUpiUri(trimmed)
        }

        // BharatQR / EMVCo Merchant Presented Mode TLV starts with "000201"
        if (trimmed.startsWith("000201")) {
            return parseEmvCoBharatQr(trimmed)
        }

        return Result.failure(IllegalArgumentException("Not a valid NPCI UPI or BharatQR code"))
    }

    private fun parseUpiUri(raw: String): Result<UpiQrPayload> {
        val uri = try {
            Uri.parse(raw)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Malformed UPI URI", e))
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme != "upi" || (host != "pay" && host != "mandate")) {
            return Result.failure(IllegalArgumentException("Unsupported UPI scheme or action"))
        }

        val vpa = uri.getQueryParameter("pa")?.trim().orEmpty()
        if (!isValidVpa(vpa)) {
            return Result.failure(IllegalArgumentException("Invalid or missing payee UPI ID (pa)"))
        }

        val rawName = uri.getQueryParameter("pn")?.trim().orEmpty()
        val payeeName = if (rawName.isNotBlank()) rawName.take(120) else vpa.substringBefore("@")
        val amountStr = uri.getQueryParameter("am")?.trim()
        val amountPaise = if (!amountStr.isNullOrBlank()) {
            parseRupeesToPaise(amountStr)
                ?: return Result.failure(IllegalArgumentException("Invalid amount in UPI QR"))
        } else {
            null
        }

        val currency = uri.getQueryParameter("cu")?.trim()?.uppercase().orEmpty().ifBlank { "INR" }
        if (currency != "INR") {
            return Result.failure(IllegalArgumentException("Only INR currency is supported for UPI"))
        }

        val mcc = uri.getQueryParameter("mc")?.trim().orEmpty().take(16)
        val tr = uri.getQueryParameter("tr")?.trim().orEmpty().take(128)
        val tn = uri.getQueryParameter("tn")?.trim().orEmpty().take(120)
        val mode = uri.getQueryParameter("mode")?.trim().orEmpty()
        val orgId = uri.getQueryParameter("orgid")?.trim().orEmpty()
        val sign = uri.getQueryParameter("sign")?.trim().orEmpty()

        return Result.success(
            UpiQrPayload(
                payeeVpa = vpa,
                payeeName = payeeName,
                amountPaise = amountPaise,
                currency = currency,
                merchantCode = mcc,
                txRef = tr,
                note = tn,
                mode = mode,
                orgId = orgId,
                signature = sign,
                rawUri = raw,
            )
        )
    }

    /**
     * Parses EMVCo Merchant Presented Mode TLV QR (BharatQR / UPI Tag 26).
     */
    private fun parseEmvCoBharatQr(payload: String): Result<UpiQrPayload> {
        val tags = parseTlvMap(payload)
        if (tags.isEmpty()) {
            return Result.failure(IllegalArgumentException("Invalid BharatQR TLV payload"))
        }

        // Tag 26 or 27 typically holds Merchant Account Info with AID A000000524 (NPCI UPI)
        var vpa = ""
        for (tagKey in listOf("26", "27", "28")) {
            val subPayload = tags[tagKey] ?: continue
            val subTags = parseTlvMap(subPayload)
            val candidate = subTags["01"]?.trim().orEmpty()
            if (isValidVpa(candidate)) {
                vpa = candidate
                break
            }
        }
        if (!isValidVpa(vpa)) {
            return Result.failure(IllegalArgumentException("No valid UPI VPA found in BharatQR"))
        }

        val merchantName = tags["59"]?.trim().orEmpty().ifBlank { vpa.substringBefore("@") }.take(120)
        val mcc = tags["52"]?.trim().orEmpty().take(16)
        val amountPaise = tags["54"]?.trim()?.let { parseRupeesToPaise(it) }
        val additionalTags = tags["62"]?.let { parseTlvMap(it) }.orEmpty()
        val tr = additionalTags["05"]?.trim().orEmpty().take(128)

        return Result.success(
            UpiQrPayload(
                payeeVpa = vpa,
                payeeName = merchantName,
                amountPaise = amountPaise,
                currency = "INR",
                merchantCode = mcc,
                txRef = tr,
                note = "",
                mode = "01",
                orgId = "",
                signature = "",
                rawUri = payload,
            )
        )
    }

    private fun parseTlvMap(data: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var idx = 0
        while (idx + 4 <= data.length) {
            val tag = data.substring(idx, idx + 2)
            val len = data.substring(idx + 2, idx + 4).toIntOrNull() ?: break
            idx += 4
            if (len < 0 || idx + len > data.length) break
            map[tag] = data.substring(idx, idx + len)
            idx += len
        }
        return map
    }

    fun parseRupeesToPaise(amountText: String): Long? {
        val cleaned = amountText.trim().replace(",", "")
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd <= BigDecimal.ZERO) return null
            val paise = bd.movePointRight(2)
            if (paise.stripTrailingZeros().scale() > 0) return null
            val longVal = paise.longValueExact()
            if (longVal in 100L..10_000_000L) longVal else null
        } catch (e: Exception) {
            null
        }
    }
}
