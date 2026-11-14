import express from "express";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import * as admin from "firebase-admin";
import { getFirestore } from "firebase-admin/firestore";
import { createPaymentsRouter } from "./routes/payments";

const projectId = process.env.FIREBASE_PROJECT_ID || "gen-lang-client-0949712684";
const databaseId =
  process.env.FIRESTORE_DATABASE_ID || "ai-studio-android-phonepay-adaa38f7-e9a9-4ae0-89ee-b5835c3e4e5f";

if (!admin.apps.length) {
  admin.initializeApp({ projectId });
}

const db = getFirestore(admin.app(), databaseId);
const app = express();

app.use(helmet());
app.use(
  express.json({
    limit: "64kb",
    verify: (req, _res, buf) => {
      (req as express.Request & { rawBody?: string }).rawBody = buf.toString("utf8");
    },
  })
);

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
});

app.use("/api/v1/payments", apiLimiter, createPaymentsRouter(db));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", service: "phonepay-psp-gateway" });
});

const port = Number(process.env.PORT || 8443);
app.listen(port, () => {
  console.log(`PHONE PAY PSP Gateway listening on port ${port}`);
});
