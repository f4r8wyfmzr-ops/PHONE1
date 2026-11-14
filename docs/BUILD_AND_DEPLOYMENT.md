# PHONE PAY — Android Studio Setup, Backend Deployment & APK Build Guide

## 1. Android Studio Project Setup
1. Open the project root directory in **Android Studio** (AGP 9.1+ / JDK 17+).
2. Confirm `app/google-services.json` and `app/src/main/res/values/firebase_applet_config.xml` are present.
3. Configure optional public backend endpoint for release/staging builds via environment variables:
   ```bash
   export PSP_BACKEND_BASE_URL="https://psp-gateway.yourdomain.in"
   export PSP_CERT_SHA256_PIN="sha256/YOUR_SPKI_PIN_BASE64="
   ```
   *(You can also configure an HTTPS PSP Backend URL directly inside the app's **Security** tab during staging tests.)*

## 2. Building Debug & Release APKs / AABs

### Debug APK (for device/emulator testing)
```bash
gradle :app:assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK & Play Store App Bundle (.aab)
1. Set upload keystore environment variables (never commit keystores or passwords):
   ```bash
   export KEYSTORE_PATH="/path/to/my-upload-key.jks"
   export STORE_PASSWORD="your-store-password"
   export KEY_PASSWORD="your-key-password"
   ```
2. Build signed Release APK or AAB:
   ```bash
   gradle :app:assembleRelease
   gradle :app:bundleRelease
   ```
   Outputs:
   - `app/build/outputs/apk/release/app-release.apk`
   - `app/build/outputs/bundle/release/app-release.aab`

## 3. Deploying the Secure PSP Backend (`/backend`)
1. Copy `backend/.env.example` to `backend/.env` and populate your RBI-authorized PSP credentials (`PSP_MERCHANT_ID`, `PSP_API_KEY`, `PSP_API_SECRET`, `PSP_WEBHOOK_SECRET`).
2. Install dependencies and compile TypeScript:
   ```bash
   cd backend
   npm install
   npm run build
   ```
3. Deploy behind HTTPS (TLS 1.3) on **Google Cloud Run**, **AWS ECS**, or an Nginx TLS reverse proxy:
   ```bash
   npm start
   ```
4. Register `https://your-domain/api/v1/payments/webhook/psp` in your PSP merchant dashboard using `PSP_WEBHOOK_SECRET`.

## 4. Running Automated Tests
- **Firestore Security Rules (JS)**:
  ```bash
  FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
  ```
- **Android Robolectric & Unit Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
