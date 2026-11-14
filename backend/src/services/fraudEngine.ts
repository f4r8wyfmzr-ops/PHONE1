import * as admin from "firebase-admin";

export const NPCI_HARD_CAP_PAISE = 10_000_000; // INR 1,00,000
export const NEW_PAYEE_COOLING_CAP_PAISE = 500_000; // INR 5,000 first 24h
export const HOURLY_VELOCITY_LIMIT = 10;

export interface ServerFraudResult {
  blocked: boolean;
  riskScore: number;
  flags: string[];
  reason: string;
}

const SUSPICIOUS_VPA_KEYWORDS = ["lottery", "prize", "kycupdate", "refunddesk", "claimnow", "freegift"];

export async function evaluateServerFraudAndLimits(
  db: admin.firestore.Firestore,
  uid: string,
  payeeVpa: string,
  amountPaise: number,
  isVerifiedMerchant: boolean
): Promise<ServerFraudResult> {
  const flags: string[] = [];
  let riskScore = 0;

  if (!Number.isInteger(amountPaise) || amountPaise < 100 || amountPaise > NPCI_HARD_CAP_PAISE) {
    flags.push("EXCEEDS_NPCI_SINGLE_CAP");
    riskScore += 60;
  }

  const userSnap = await db.collection("users").doc(uid).get();
  const profile = userSnap.data() || {};
  const userDailyLimit = Math.min(Number(profile.dailyLimitPaise ?? NPCI_HARD_CAP_PAISE), NPCI_HARD_CAP_PAISE);
  const userPerTxnLimit = Math.min(Number(profile.perTxnLimitPaise ?? NPCI_HARD_CAP_PAISE), userDailyLimit);

  if (amountPaise > userPerTxnLimit) {
    flags.push("EXCEEDS_USER_PER_TXN_LIMIT");
    riskScore += 40;
  }

  const oneDayAgo = admin.firestore.Timestamp.fromMillis(Date.now() - 24 * 60 * 60 * 1000);
  const oneHourAgo = Date.now() - 60 * 60 * 1000;
  const twoMinAgo = Date.now() - 2 * 60 * 1000;

  const recentSnap = await db
    .collection("users")
    .doc(uid)
    .collection("transactions")
    .where("createdAt", ">=", oneDayAgo)
    .get();

  let dailySpentPaise = 0;
  let hourlyCount = 0;
  let duplicateFound = false;

  for (const doc of recentSnap.docs) {
    const data = doc.data();
    const createdMs = data.createdAt?.toMillis?.() ?? 0;
    const status = String(data.status || "");

    if (["VERIFIED_SUCCESS", "AWAITING_PSP_CONFIRMATION", "PENDING_VERIFICATION"].includes(status)) {
      dailySpentPaise += Number(data.amountPaise || 0);
    }
    if (createdMs >= oneHourAgo && status !== "PSP_UNAVAILABLE") {
      hourlyCount += 1;
    }
    if (
      createdMs >= twoMinAgo &&
      String(data.payeeVpa || "").toLowerCase() === payeeVpa.toLowerCase() &&
      Number(data.amountPaise || 0) === amountPaise &&
      !["FAILED", "USER_CANCELLED", "PSP_UNAVAILABLE"].includes(status)
    ) {
      duplicateFound = true;
    }
  }

  if (dailySpentPaise + amountPaise > userDailyLimit) {
    flags.push("EXCEEDS_DAILY_CAP");
    riskScore += 50;
  }
  if (hourlyCount >= HOURLY_VELOCITY_LIMIT) {
    flags.push("HIGH_VELOCITY_HOURLY");
    riskScore += 50;
  }
  if (duplicateFound) {
    flags.push("DUPLICATE_TXN_WINDOW");
    riskScore += 45;
  }

  if (!isVerifiedMerchant) {
    const benSnap = await db
      .collection("users")
      .doc(uid)
      .collection("beneficiaries")
      .where("vpa", "==", payeeVpa)
      .limit(1)
      .get();
    const firstPaidMs = benSnap.docs[0]?.data()?.firstPaidAt?.toMillis?.() ?? null;
    const isCooling = firstPaidMs === null || Date.now() - firstPaidMs < 24 * 60 * 60 * 1000;
    if (isCooling && amountPaise > NEW_PAYEE_COOLING_CAP_PAISE) {
      flags.push("NEW_PAYEE_COOLING_LIMIT");
      riskScore += 45;
    }
  }

  const lowerVpa = payeeVpa.toLowerCase();
  if (SUSPICIOUS_VPA_KEYWORDS.some((kw) => lowerVpa.includes(kw))) {
    flags.push("SUSPICIOUS_VPA_PATTERN");
    riskScore += 65;
  }

  const clampedScore = Math.min(100, riskScore);
  const blocked = flags.length > 0 || clampedScore >= 70;
  return {
    blocked,
    riskScore: clampedScore,
    flags,
    reason: blocked ? `Blocked by server fraud policy: ${flags.join(", ")}` : "",
  };
}
