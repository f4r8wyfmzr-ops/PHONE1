import { Router, Request, Response } from "express";
import * as admin from "firebase-admin";
import crypto from "crypto";
import { AuthenticatedRequest, verifyFirebaseAndAntiReplay } from "../middleware/authMiddleware";
import { evaluateServerFraudAndLimits } from "../services/fraudEngine";
import {
  createAuthorizedPspOrder,
  fetchAuthorizedPspStatus,
  isPspConfigured,
  loadPspConfig,
  verifyPspWebhookSignature,
} from "../services/pspGateway";

const VPA_REGEX = /^[a-zA-Z0-9._\-]{2,256}@[a-zA-Z0-9]{2,64}$/;

export function createPaymentsRouter(db: admin.firestore.Firestore): Router {
  const router = Router();

  router.post("/initiate", verifyFirebaseAndAntiReplay, async (req: AuthenticatedRequest, res: Response) => {
    try {
      const uid = req.uid!;
      const { payeeVpa, payeeName, merchantCode = "", amountPaise, currency = "INR", note = "" } = req.body || {};

      if (!VPA_REGEX.test(String(payeeVpa || "").trim())) {
        res.status(400).json({ code: "INVALID_VPA", message: "Invalid payee VPA format." });
        return;
      }
      if (currency !== "INR") {
        res.status(400).json({ code: "UNSUPPORTED_CURRENCY", message: "Only INR UPI payments are supported." });
        return;
      }

      const numericAmount = Number(amountPaise);
      const isVerifiedMerchant = Boolean(merchantCode && merchantCode !== "0000");

      const fraudResult = await evaluateServerFraudAndLimits(
        db,
        uid,
        String(payeeVpa).trim(),
        numericAmount,
        isVerifiedMerchant
      );

      if (fraudResult.blocked) {
        res.status(403).json({
          code: "FRAUD_OR_LIMIT_BLOCKED",
          message: fraudResult.reason,
          fraudRiskScore: fraudResult.riskScore,
          fraudFlags: fraudResult.flags,
        });
        return;
      }

      const cfg = loadPspConfig();
      if (!isPspConfigured(cfg)) {
        res.status(503).json({
          code: "PSP_CREDENTIALS_NOT_CONFIGURED",
          message:
            "Authorized bank/PSP merchant credentials (PSP_MERCHANT_ID, PSP_API_KEY, PSP_API_SECRET, PSP_WEBHOOK_SECRET) are not configured on the backend.",
        });
        return;
      }

      const txRef = `PP${Date.now()}${crypto.randomBytes(4).toString("hex").toUpperCase()}`;
      const order = await createAuthorizedPspOrder(cfg, {
        amountPaise: numericAmount,
        payeeVpa: String(payeeVpa).trim(),
        payeeName: String(payeeName || "Merchant").trim().slice(0, 120),
        merchantCode: String(merchantCode).trim().slice(0, 16),
        note: String(note).trim().slice(0, 120),
        receiptRef: txRef,
      });

      res.status(201).json({
        pspOrderId: order.pspOrderId,
        txRef: order.txRef,
        upiIntentUri: order.upiIntentUri,
        providerName: order.providerName,
        status: "AWAITING_PSP_CONFIRMATION",
        expiresAtEpochMs: order.expiresAtEpochMs,
        fraudRiskScore: fraudResult.riskScore,
        fraudFlags: fraudResult.flags,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : "PSP initiation error";
      const code = msg === "PSP_CREDENTIALS_NOT_CONFIGURED" ? "PSP_CREDENTIALS_NOT_CONFIGURED" : "PSP_INITIATION_ERROR";
      res.status(code === "PSP_CREDENTIALS_NOT_CONFIGURED" ? 503 : 502).json({ code, message: msg });
    }
  });

  router.get("/:orderId/status", verifyFirebaseAndAntiReplay, async (req: AuthenticatedRequest, res: Response) => {
    try {
      const uid = req.uid!;
      const orderId = String(req.params.orderId || "").trim();
      if (!orderId) {
        res.status(400).json({ code: "INVALID_ORDER_ID", message: "Order ID required." });
        return;
      }

      const cfg = loadPspConfig();
      if (!isPspConfigured(cfg)) {
        res.status(503).json({
          code: "PSP_CREDENTIALS_NOT_CONFIGURED",
          message: "Cannot verify transaction status until authorized PSP credentials are configured.",
        });
        return;
      }

      const txSnap = await db
        .collection("users")
        .doc(uid)
        .collection("transactions")
        .where("pspOrderId", "==", orderId)
        .limit(1)
        .get();

      const txDoc = txSnap.docs[0];
      const existingData = txDoc?.data();
      const txRef = String(existingData?.txRef || "");

      const pspStatus = await fetchAuthorizedPspStatus(cfg, orderId, txRef);

      if (txDoc) {
        await txDoc.ref.update({
          status: pspStatus.status,
          pspVerified: pspStatus.pspVerified,
          utrNumber: pspStatus.utrNumber,
          failureReason: pspStatus.failureReason,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }

      res.json(pspStatus);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to verify status";
      res.status(502).json({ code: "PSP_STATUS_ERROR", message: msg });
    }
  });

  /**
   * Webhook endpoint called asynchronously by the RBI-regulated PSP.
   * Verifies HMAC-SHA256 signature before writing VERIFIED_SUCCESS + real UTR to Firestore.
   */
  router.post("/webhook/psp", async (req: Request, res: Response) => {
    const cfg = loadPspConfig();
    const sig = req.header("X-Razorpay-Signature") || req.header("X-Webhook-Signature") || "";
    const rawBody = (req as Request & { rawBody?: string }).rawBody || JSON.stringify(req.body || {});

    if (!verifyPspWebhookSignature(rawBody, sig, cfg.webhookSecret)) {
      res.status(401).json({ code: "INVALID_WEBHOOK_SIGNATURE", message: "HMAC verification failed." });
      return;
    }

    const event = req.body;
    const paymentEntity = event?.payload?.payment?.entity;
    const orderId = String(paymentEntity?.order_id || "");
    const status = String(paymentEntity?.status || "");
    const utr = String(paymentEntity?.acquirer_data?.rrn || paymentEntity?.acquirer_data?.upi_transaction_id || "");

    if (orderId) {
      const matches = await db.collectionGroup("transactions").where("pspOrderId", "==", orderId).limit(1).get();
      const doc = matches.docs[0];
      if (doc) {
        if (status === "captured" && utr) {
          await doc.ref.update({
            status: "VERIFIED_SUCCESS",
            pspVerified: true,
            utrNumber: utr,
            failureReason: "",
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        } else if (status === "failed") {
          await doc.ref.update({
            status: "FAILED",
            pspVerified: false,
            failureReason: String(paymentEntity?.error_description || "Bank/PSP declined transaction"),
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        }
      }
    }

    res.status(200).json({ received: true });
  });

  return router;
}
