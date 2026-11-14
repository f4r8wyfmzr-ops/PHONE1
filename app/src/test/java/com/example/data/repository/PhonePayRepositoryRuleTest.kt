package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.TransactionStatus
import com.example.data.model.UpiTransaction
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePayRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun ensureProfileAndRecordTransaction_validOwner_succeedsAndEmitsRealtime() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = PhonePayRepository(db = firestore, auth = auth)

        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureProfileInitialized("Aarav Sharma", ALICE_EMAIL)
        }
        assertTrue(profileRes.isSuccess)

        val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val tx = UpiTransaction(
            id = txId,
            userId = uid,
            pspOrderId = "ORD_${txId.takeLast(8)}",
            txRef = "TR_${txId.takeLast(8)}",
            payeeVpa = "chaiwala@okaxis",
            payeeName = "Chai Point",
            merchantCode = "5814",
            amountPaise = 15000L,
            status = TransactionStatus.INITIATED,
            pspVerified = false,
            fraudRiskScore = 5,
        )

        val createRes = withTimeout(DEFAULT_TIMEOUT_MS) { repository.recordInitiatedTransaction(tx) }
        assertTrue(createRes.isSuccess)

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeTransactions(uid).first { list -> list.any { it.id == txId } }
        }
        assertTrue(emitted.any { it.id == txId && it.payeeVpa == "chaiwala@okaxis" })
    }

    @Test
    fun getTransaction_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = PhonePayRepository(db = firestore, auth = auth)
        val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val tx = UpiTransaction(
            id = txId,
            userId = aliceUid,
            pspOrderId = "ORD_${txId.takeLast(8)}",
            txRef = "TR_${txId.takeLast(8)}",
            payeeVpa = "merchant@okicici",
            payeeName = "Metro Store",
            amountPaise = 25000L,
            status = TransactionStatus.INITIATED,
            pspVerified = false,
            fraudRiskScore = 0,
        )
        withTimeout(DEFAULT_TIMEOUT_MS) { aliceRepo.recordInitiatedTransaction(tx).getOrThrow() }

        signInTestUser(BOB_EMAIL)
        var caught: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("transactions").document(txId)
                    .get()
                    .await()
            }
        } catch (e: Exception) {
            caught = e.unwrapFirestoreException()
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, caught?.code)
    }

    @Test
    fun observeTransactions_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = PhonePayRepository(db = firestore, auth = auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeTransactions("some_user_123").first()
            }
        } catch (e: Throwable) {
            caught = e
        }
        val fsErr = caught?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_upi@test.com"
        const val BOB_EMAIL = "bob_upi@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
