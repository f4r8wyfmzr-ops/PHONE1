import crypto from "crypto";

export interface EncryptedEnvelope {
  ivBase64: string;
  cipherTextBase64: string;
  authTagBase64: string;
  algorithm: "AES-256-GCM";
}

/**
 * Encrypts sensitive audit payloads at rest using AES-256-GCM.
 * Never used to store UPI PINs (UPI PINs must never be collected or stored).
 */
export function encryptAesGcm(plaintext: string, keyHex: string): EncryptedEnvelope {
  const key = Buffer.from(keyHex, "hex");
  if (key.length !== 32) {
    throw new Error("PAYLOAD_AES_KEY_HEX must be 32 bytes (64 hex characters)");
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return {
    ivBase64: iv.toString("base64"),
    cipherTextBase64: encrypted.toString("base64"),
    authTagBase64: authTag.toString("base64"),
    algorithm: "AES-256-GCM",
  };
}

export function decryptAesGcm(envelope: EncryptedEnvelope, keyHex: string): string {
  const key = Buffer.from(keyHex, "hex");
  if (key.length !== 32) {
    throw new Error("PAYLOAD_AES_KEY_HEX must be 32 bytes (64 hex characters)");
  }
  const iv = Buffer.from(envelope.ivBase64, "base64");
  const authTag = Buffer.from(envelope.authTagBase64, "base64");
  const cipherBytes = Buffer.from(envelope.cipherTextBase64, "base64");
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(authTag);
  return Buffer.concat([decipher.update(cipherBytes), decipher.final()]).toString("utf8");
}
