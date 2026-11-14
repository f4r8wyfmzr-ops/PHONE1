import { Request, Response, NextFunction } from "express";
import * as admin from "firebase-admin";

export interface AuthenticatedRequest extends Request {
  uid?: string;
  userEmail?: string;
  deviceFingerprint?: string;
}

const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000; // 5-minute anti-replay window

export async function verifyFirebaseAndAntiReplay(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const authHeader = req.header("Authorization") || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ code: "UNAUTHORIZED", message: "Missing Bearer Firebase ID token." });
      return;
    }

    const timestampRaw = req.header("X-Request-Timestamp");
    const timestampMs = timestampRaw ? Number(timestampRaw) : NaN;
    if (!Number.isFinite(timestampMs) || Math.abs(Date.now() - timestampMs) > MAX_CLOCK_SKEW_MS) {
      res.status(401).json({ code: "REPLAY_WINDOW_EXPIRED", message: "Invalid or expired request timestamp." });
      return;
    }

    const fingerprint = req.header("X-Device-Fingerprint") || "";
    if (fingerprint.length < 32) {
      res.status(400).json({ code: "MISSING_DEVICE_FINGERPRINT", message: "Valid X-Device-Fingerprint header required." });
      return;
    }

    const idToken = authHeader.slice("Bearer ".length).trim();
    const decoded = await admin.auth().verifyIdToken(idToken, true);
    req.uid = decoded.uid;
    req.userEmail = decoded.email || "";
    req.deviceFingerprint = fingerprint;
    next();
  } catch (err) {
    res.status(401).json({
      code: "INVALID_AUTH_TOKEN",
      message: "Firebase ID token verification failed or token was revoked.",
    });
  }
}
