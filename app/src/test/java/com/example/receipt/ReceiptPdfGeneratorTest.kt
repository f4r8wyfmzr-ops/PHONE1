package com.example.receipt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiptPdfGeneratorTest {

    @Test
    fun generatePdfReceipt_writesNonEmptyPdfFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tx = UpiTransaction(
            id = "tx_pdf_001",
            userId = "u_123",
            pspOrderId = "ORD_123456",
            txRef = "TR_998877",
            payeeVpa = "bharat@okaxis",
            payeeName = "Bharat Kirana",
            merchantCode = "5411",
            amountPaise = 49900L,
            status = TransactionStatus.VERIFIED_SUCCESS,
            pspVerified = true,
            utrNumber = "431900112233",
        )
        val uri = ReceiptPdfGenerator.generatePdfReceipt(context, tx)
        assertTrue(uri.toString().contains("PHONEPAY_Receipt_tx_pdf_001.pdf"))
        val outFile = File(File(context.cacheDir, "receipts"), "PHONEPAY_Receipt_tx_pdf_001.pdf")
        assertTrue(outFile.exists())
        assertTrue(outFile.length() > 100L)
    }
}
