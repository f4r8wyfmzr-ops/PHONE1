import crypto from "crypto";

export interface PspConfig {
  provider: string;
  baseUrl: string;
  merchantId: string;
  apiKey: string;
  apiSecret: string;
  webhookSecret: string;
}

export function loadPspConfig(): PspConfig {
  return {
    provider: process.env.PSP_PROVIDER || "RAZORPAY",
    baseUrl: process.env.PSP_BASE_URL || "https://api.razorpay.com/v1",
    merchantId: (process.env.PSP_MERCHANT_ID || "").trim(),
    apiKey: (process.env.PSP_API_KEY || "").trim(),
    apiSecret: (process.env.PSP_API_SECRET || "").trim(),
    webhookSecret: (process.env.PSP_WEBHOOK_SECRET || "").trim(),
  };
}

export function isPspConfigured(cfg: PspConfig): boolean {
  return Boolean(cfg.merchantId && cfg.apiKey && cfg.apiSecret && cfg.webhookSecret);
}

export interface CreateUpiOrderInput {
  amountPaise: number;
  payeeVpa: string;
  payeeName: string;
  merchantCode: string;
  note: string;
  receiptRef: string;
}

export interface CreateUpiOrderOutput {
  pspOrderId: string;
  txRef: string;
  upiIntentUri: string;
  providerName: string;
  expiresAtEpochMs: number;
}

export interface PspStatusOutput {
  pspOrderId: string;
  txRef: string;
  status: "PENDING_VERIFICATION" | "VERIFIED_SUCCESS" | "FAILED";
  pspVerified: boolean;
  utrNumber: string;
  bankReference: string;
  verifiedAtIso: string;
  failureReason: string;
}

/**
 * Calls the RBI-regulated PSP server-to-server Order API.
 * Strictly requires live merchant credentials; NEVER fabricates orders or UTRs.
 */
export async function createAuthorizedPspOrder(
  cfg: PspConfig,
  input: CreateUpiOrderInput
): Promise<CreateUpiOrderOutput> {
  if (!isPspConfigured(cfg)) {
    throw new Error("PSP_CREDENTIALS_NOT_CONFIGURED");
  }

  const basicAuth = Buffer.from(`${cfg.apiKey}:${cfg.apiSecret}`).toString("base64");
  const resp = await fetch(`${cfg.baseUrl}/orders`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${basicAuth}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      amount: input.amountPaise,
      currency: "INR",
      receipt: input.receiptRef,
      notes: {
        payeeVpa: input.payeeVpa,
        payeeName: input.payeeName,
        merchantCode: input.merchantCode,
      },
    }),
  });

  if (!resp.ok) {
    const errText = await resp.text();
    throw new Error(`PSP_ORDER_API_FAILED (${resp.status}): ${errText.slice(0, 200)}`);
  }

  const orderJson = (await resp.json()) as { id: string; receipt?: string };
  const pspOrderId = orderJson.id;
  if (!pspOrderId) {
    throw new Error("PSP_ORDER_ID_MISSING");
  }

  const txRef = input.receiptRef;
  const amountRupees = (input.amountPaise / 100).toFixed(2);
  const params = new URLSearchParams({
    pa: input.payeeVpa,
    pn: input.payeeName,
    tr: txRef,
    tn: input.note || `Order ${pspOrderId}`,
    am: amountRupees,
    cu: "INR",
  });
  if (input.merchantCode) {
    params.set("mc", input.merchantCode);
  }

  return {
    pspOrderId,
    txRef,
    upiIntentUri: `upi://pay?${params.toString()}`,
    providerName: cfg.provider,
    expiresAtEpochMs: Date.now() + 10 * 60 * 1000,
  };
}

/**
 * Server-to-server status verification against the RBI-regulated PSP API.
 */
export async function fetchAuthorizedPspStatus(
  cfg: PspConfig,
  pspOrderId: string,
  txRef: string
): Promise<PspStatusOutput> {
  if (!isPspConfigured(cfg)) {
    throw new Error("PSP_CREDENTIALS_NOT_CONFIGURED");
  }

  const basicAuth = Buffer.from(`${cfg.apiKey}:${cfg.apiSecret}`).toString("base64");
  const resp = await fetch(`${cfg.baseUrl}/orders/${encodeURIComponent(pspOrderId)}/payments`, {
    method: "GET",
    headers: {
      Authorization: `Basic ${basicAuth}`,
    },
  });

  if (!resp.ok) {
    const errText = await resp.text();
    throw new Error(`PSP_STATUS_API_FAILED (${resp.status}): ${errText.slice(0, 200)}`);
  }

  const data = (await resp.json()) as {
    items?: Array<{
      id: string;
      status: string;
      acquirer_data?: { rrn?: string; upi_transaction_id?: string };
      error_description?: string;
    }>;
  };

  const payments = data.items || [];
  const captured = payments.find((p) => p.status === "captured");
  if (captured) {
    const utr = captured.acquirer_data?.rrn || captured.acquirer_data?.upi_transaction_id || "";
    if (!utr) {
      return {
        pspOrderId,
        txRef,
        status: "PENDING_VERIFICATION",
        pspVerified: false,
        utrNumber: "",
        bankReference: "",
        verifiedAtIso: "",
        failureReason: "Captured at PSP, awaiting NPCI RRN/UTR settlement reference.",
      };
    }
    return {
      pspOrderId,
      txRef,
      status: "VERIFIED_SUCCESS",
      pspVerified: true,
      utrNumber: utr,
      bankReference: captured.id,
      verifiedAtIso: new Date().toISOString(),
      failureReason: "",
    };
  }

  const failed = payments.find((p) => p.status === "failed");
  if (failed) {
    return {
      pspOrderId,
      txRef,
      status: "FAILED",
      pspVerified: false,
      utrNumber: "",
      bankReference: failed.id,
      verifiedAtIso: "",
      failureReason: failed.error_description || "Payment declined by issuer bank or NPCI.",
    };
  }

  return {
    pspOrderId,
    txRef,
    status: "PENDING_VERIFICATION",
    pspVerified: false,
    utrNumber: "",
    bankReference: "",
    verifiedAtIso: "",
    failureReason: "Payment not yet settled by PSP.",
  };
}

export function verifyPspWebhookSignature(
  rawBody: string,
  signatureHeader: string,
  webhookSecret: string
): boolean {
  if (!webhookSecret || !signatureHeader) return false;
  const expected = crypto.createHmac("sha256", webhookSecret).update(rawBody).digest("hex");
  const expectedBuf = Buffer.from(expected, "utf8");
  const providedBuf = Buffer.from(signatureHeader, "utf8");
  if (expectedBuf.length !== providedBuf.length) return false;
  return crypto.timingSafeEqual(expectedBuf, providedBuf);
}
