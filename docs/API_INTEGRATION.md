# PHONE PAY — Official UPI & PSP API Integration Guide

## 1. Regulatory & Architectural Principles
- **Zero UPI PIN Handling**: PHONE PAY never collects, transmits, or stores a user's UPI PIN. PIN entry occurs exclusively inside an RBI-authorized UPI PSP app (via `upi://pay` Intent + NPCI Common Library) or an authorized Sponsor Bank SDK.
- **No Unverified Settlement**: Client Android `ActivityResult` data from UPI Intent launches is never treated as proof of payment. Final settlement (`VERIFIED_SUCCESS`) and Bank UTR/RRN (`utrNumber`) are written to Firestore **only** by the backend Admin SDK after server-to-server PSP verification or HMAC-SHA256 webhook confirmation.
- **Zero-Trust Firestore Rules**: `firestore.rules` explicitly blocks client SDK writes from setting `status = "VERIFIED_SUCCESS"` or `pspVerified = true`.

## 2. RBI-Regulated PSP Onboarding Prerequisites
Before enabling real money transfers in production, obtain:
1. Merchant / Aggregator onboarding with an RBI-licensed Payment Aggregator / Sponsor Bank PSP (e.g., NPCI Sponsor Bank, Razorpay, Cashfree, PayU).
2. Whitelisted Android package name (`com.aistudio.phonepay.kxqzmw`) and release SHA-256 signing certificate fingerprint.
3. Live PSP credentials configured strictly on the backend (`backend/.env`), never inside the Android APK:
   - `PSP_MERCHANT_ID`
   - `PSP_API_KEY`
   - `PSP_API_SECRET`
   - `PSP_WEBHOOK_SECRET`

## 3. Transport & Payload Security
- **TLS 1.3 / Restricted TLS**: `OkHttpClient` enforces `ConnectionSpec.RESTRICTED_TLS` and `network_security_config.xml` sets `cleartextTrafficPermitted="false"`.
- **Certificate Pinning**: Set `PSP_CERT_SHA256_PIN="sha256/..."` at build time to enable `CertificatePinner`.
- **Authentication & Anti-Replay Headers**:
  - `Authorization: Bearer <Firebase ID Token>` (verified via `admin.auth().verifyIdToken(token, true)`)
  - `X-Idempotency-Key: <UUIDv4>`
  - `X-Request-Timestamp: <epochMs>` (±5 minute window enforced server-side)
  - `X-Device-Fingerprint: <SHA-256>`
- **Android Keystore AES-256-GCM**: `CryptoSecurityManager` uses `AndroidKeyStore` for hardware-backed local encryption keys.

## 4. Backend API Endpoints

### `POST /api/v1/payments/initiate`
Runs server-side NPCI fraud/limit checks and creates a real PSP order returning a signed `upi://pay` URI.
- **Request Body**:
  ```json
  {
    "payeeVpa": "merchant@okaxis",
    "payeeName": "Bharat Kirana Store",
    "merchantCode": "5411",
    "amountPaise": 24550,
    "currency": "INR",
    "note": "Groceries",
    "qrSignature": "",
    "clientRiskScore": 5,
    "clientFraudFlags": []
  }
  ```
- **Responses**:
  - `201 Created`: `{ pspOrderId, txRef, upiIntentUri, providerName, status, expiresAtEpochMs, fraudRiskScore, fraudFlags }`
  - `403 Forbidden`: `{ code: "FRAUD_OR_LIMIT_BLOCKED", ... }`
  - `503 Service Unavailable`: `{ code: "PSP_CREDENTIALS_NOT_CONFIGURED", ... }`

### `GET /api/v1/payments/:orderId/status`
Queries the PSP Order/Payment Status API server-to-server, updates Firestore via Admin SDK, and returns verified settlement status and real `utrNumber`.

### `POST /api/v1/payments/webhook/psp`
Receives asynchronous PSP settlement notifications, validates `X-Razorpay-Signature` / `X-Webhook-Signature` using `crypto.timingSafeEqual` HMAC-SHA256, and updates the Firestore transaction document atomically.

## 5. Fraud Detection & NPCI Limits
- **Single Transaction Hard Cap**: ₹1,00,000 (`10,000,000` paise)
- **24-Hour Rolling Daily Cap**: ₹1,00,000 (or lower user-configured limit)
- **New Payee Cooling Limit**: ₹5,000 (`500,000` paise) for the first 24 hours on unverified personal VPAs
- **Velocity & Duplicate Guards**: Max 10 attempts/hour; duplicate VPA+amount blocked within 2 minutes
