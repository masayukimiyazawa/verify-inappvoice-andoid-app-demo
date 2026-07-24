# Vonage Voice Verify

Verify V2 authentication and InApp mobile application outbound call demo, backed by Vonage Cloud Runtime (APAC).

- **SMS OTP** login via Vonage Verify V2
- **Dial Pad** for entering phone numbers
- **InApp Voice** (real-time audio calls over data) via Vonage InApp Voice SDK
- Phone numbers and call state persisted in **MongoDB**

---

## Table of Contents

1. [Project Structure](#project-structure)
2. [Prerequisites](#prerequisites)
3. [Vonage Setup](#vonage-setup)
4. [MongoDB Setup](#mongodb-setup)
5. [Backend Deployment](#backend-deployment)
6. [Android App — Build & Release APK](#android-app--build--release-apk)
7. [Local Development](#local-development)
8. [Troubleshooting](#troubleshooting)

---

## Project Structure

```
verify-inapp-mobilevoice-app/
├── backend/                  # Vonage Cloud Runtime (VCR) API
│   ├── src/
│   │   ├── index.ts          # Route entry-point for VCR
│   │   ├── config.ts         # Environment config loader
│   │   ├── db.ts             # MongoDB connection
│   │   ├── models/PhoneUser.ts
│   │   ├── types/vcr.ts
│   │   ├── routes/
│   │   │   ├── auth.ts       # SMS OTP send / verify
│   │   │   ├── voice.ts      # NCCO, initiate call
│   │   │   └── voiceAuth.ts  # JWT token, register/unregister, push-token, reconnect
│   │   └── webhooks.ts       # Call event webhook handler
│   ├── vcr.json               # VCR deployment config (alternative)
│   ├── vcr.yml.example        # VCR deployment template → generates vcr.yml
│   ├── package.json
│   ├── tsconfig.json
│   └── .env.example
│
└── android-app/              # Kotlin Android app
    ├── app/
    │   ├── src/main/
    │   │   ├── java/com/example/voiceverify/
    │   │   │   ├── VoiceVerifyApplication.kt       # Vonage client, push-token, reconnect
    │   │   │   ├── activity_phone_input.xml
    │   │   │   ├── activity_verify_code.xml
    │   │   │   ├── activity_dialpad.xml
    │   │   │   ├── activity_call.xml
    │   │   │   ├── activity_incoming_call.xml
    │   │   │   ├── api/RetrofitClient.kt
    │   │   │   ├── service/VonageVoiceService.kt
    │   │   │   ├── service/FirebaseMessagingService.kt  # Push notification handler
    │   │   │   ├── viewmodel/AuthViewModel.kt
    │   │   │   ├── viewmodel/CallViewModel.kt
    │   │   │   ├── viewmodel/DialPadViewModel.kt
    │   │   │   └── utils/SharedPreferencesManager.kt
    │   │   └── AndroidManifest.xml
    │   ├── build.gradle
    │   └── proguard-rules.pro
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    ├── .env.sample            # Template — copy to .env and configure
    └── .env                   # Local config (gitignored)
```

---

## Prerequisites

| Requirement | Minimum Version | Notes |
|-------------|----------------|-------|
| Node.js | 20.x LTS | For backend |
| npm / pnpm | ≥ 9.x | For backend |
| JDK | 17 | Android build |
| Android Studio | ≥ 2023.2 (Flyfish) | With SDK 34 |
| Android Gradle Plugin | 8.7.0 | Managed by Gradle wrapper |
| Vonage Account | — | With Voice + Verify API enabled |
| MongoDB | 5.0+ | Atlas or self-hosted |

---

## Vonage Setup

The Vonage Application must be created **in advance** via the Vonage Dashboard. It is not created by the CLI.

### 1. Create a Vonage Application (Voice) — Via Dashboard

1. Go to **https://dashboard.nvonage.com** → **API Integrations**
2. Click **Create a new application**
3. Select **Voice** as the type
4. Provide a name (e.g. `voiceverify`) and click **Create**
5. Record the following values from the application settings page:

    | Value | Description | Used in |
    |-------|-------------|---------|
    | **Application ID** | Unique identifier for this application | `VONAGE_APPLICATION_ID` |
    | **API Key** | API credentials for calling Vonage APIs | `VONAGE_API_KEY` |
    | **API Secret** | API secret key | `VONAGE_API_SECRET` |

6. Download the **Private Key** (RSA keypair for JWT signing) by clicking **Download key** and save it to:
    ```
    backend/keys/vonage_private.key
    ```

> **Note:** The Application ID is **generated once** and **does not change**.
    > Reuse the same Application ID across all environments (development, staging, production)
    > to avoid breaking call routing, webhooks, and registered users.
    > The private key must never change — if lost, you must create a new Application (new ID).

### 2. Configure Webhook URLs (After Deployment)

After deploying to VCR, you must register the Answer URL and Event URL on your Vonage Application.

**Option A — Vonage CLI**

```bash
vonage apps:update <app-id> \
  --voice-answer-url https://<vcr-deployment-url>/api/voice/ncco \
  --voice-event-url https://<vcr-deployment-url>/webhooks/voice-events
```

**Option B — Dashboard**

1. Go to **https://dashboard.nvonage.com** → **API Integrations** → your application
2. Under **Voice Settings**, set:

    | Setting | URL |
    |---------|-----|
    | **Answer URL** | `https://<vcr-deployment-url>/api/voice/ncco` |
    | **Event Callback URL** | `https://<vcr-deployment-url>/webhooks/voice-events` |

The deployed routes are:

| Purpose | Endpoint |
|---------|----------|
| **NCCO Endpoint** (Answer URL) | `https://<vcr-deployment-url>/api/voice/ncco` |
| **Event Callback URL** | `https://<vcr-deployment-url>/webhooks/voice-events` |

> **Note:** Replace `<vcr-deployment-url>` with your actual VCR deployment domain (e.g., `https://voiceverify-xxxxx.apac.runtime.vonage.com`).

### 3. Enable Verify API

Verify V2 (SMS OTP) works automatically from the Vonage Dashboard. No extra setup is needed beyond having your `API_KEY` and `API_SECRET`.

### 4. Get a Vonage Phone Number

A Vonage phone number is required for **PSTN inbound/outbound calls** (Voice Proxy fallback).

#### 4-1. Purchase a Number

Purchase a phone number at:
https://dashboard.nvonage.com/number-directory

> **Note:** For Japan deployment, purchase a Japanese number (e.g. `+818012345678`).

#### 4-2. Link Number to Your Application

After purchasing, link the number to your Voice Application:

**Via Vonage CLI:**
```bash
vonage numbers:link <your-phone-number> --appid=<your-application-id>
```

**Via Dashboard:**
1. Go to **https://dashboard.nvonage.com** → **Your Numbers**
2. Click on the purchased number
3. Under **Voice**, select your application (`voiceverify`) and click **Save**

#### 4-3. Set in Environment Variables

Add the phone number to `backend/.env`:

```env
VONAGE_PHONE_NUMBER=+818012345678
```

> **Note:** The number must be in E.164 format (e.g. `+818012345678`).

This number is used as:
- **Caller ID** for outbound PSTN calls
- **Inbound webhook target** (linked to `/api/voice/ncco` via the Application settings)

---

## MongoDB Setup

1. Create a free cluster at https://mongodb.com/cloud/atlas
2. Create a database user (set `username` and `password`)
3. Whitelist IP `0.0.0.0/0` (or your VCR deployment's IP range)
4. Get the connection string:
    ```
    mongodb+srv://<username>:<password>@<cluster-url>/voiceverify?retryWrites=true
    ```
5. Copy the connection string to `backend/.env` as `MONGODB_URI`.

---

## Backend Deployment

### Step 1 — Install VCR CLI

```bash
npm install -g @vonage/vcr-cli
```

### Step 2 — Configure Environment Variables

```bash
cd backend

# Copy and fill in credentials
cp .env.example .env
```

Edit `.env` with your actual values:

| Variable | Description |
|----------|-------------|
| `VONAGE_API_KEY` | Vonage API Key |
| `VONAGE_API_SECRET` | Vonage API Secret |
| `VONAGE_APPLICATION_ID` | Vonage Application ID (Voice) — set via Dashboard |
| `VONAGE_PRIVATE_KEY_PATH` | Path to your RSA private key |
| `VONAGE_BRAND_NAME` | Brand name shown on caller ID |
| `VONAGE_PHONE_NUMBER` | Vonage phone number (E.164 format, e.g. `+818012345678`) for PSTN calls |
| `MONGODB_URI` | MongoDB Atlas connection string |
| `MONGODB_DB_NAME` | Database name (default: `voiceverify`) |

Example:

```env
VONAGE_API_KEY=abc123
VONAGE_API_SECRET=xyz789
VONAGE_APPLICATION_ID=abc123-def456-789ghi
VONAGE_PRIVATE_KEY_PATH=./keys/vonage_private.key
VONAGE_BRAND_NAME=VoiceVerify
VONAGE_PHONE_NUMBER=+818012345678
MONGODB_URI=mongodb+srv://user:pass@cluster0.xxxxx.mongodb.net/voiceverify?retryWrites=true
MONGODB_DB_NAME=voiceverify
# APP_DOMAIN and PORT are only needed for local development
```

### Step 3 — Create Keys & Generate vcr.yml

```bash
mkdir -p keys
# Place the RSA private key from Step 2 of "Vonage Setup"
cp ~/vonage_private.key keys/vonage_private.key
```

### Step 4 — Build & Deploy

```bash
npm install
npm run build
vcr deploy
```

> `vcr.yml` (the VCR deployment config) is **auto-generated** from `vcr.yml.example` by `setup.sh`
> using values from `.env`. It is **gitignored** and never committed to the repository.
> All secrets are stored in the `.env` file (gitignored) or as VCR secrets via `vcr secret create`.

### Step 5 — Configure Webhook URLs

After deployment, register the webhook URLs on your Vonage Application:

**Via Vonage CLI:**
```bash
vonage apps:update <app-id> \
  --voice-answer-url https://<your-deployment-url>/api/voice/ncco \
  --voice-event-url https://<your-deployment-url>/webhooks/voice-events
```

**Via Dashboard:** https://dashboard.nvonage.com → API Integrations → Your Application → Voice Settings

Set **Answer URL** to `https://<your-deployment-url>/api/voice/ncco` and **Event Callback URL** to `https://<your-deployment-url>/webhooks/voice-events`.

### Step 6 — Verify Deployment

After deployment, your service is live at:
```
https://<your-deployment-url>
```

### Step 7 — Set BACKEND_URL for Android Build

After `vcr deploy` completes, the deployment URL is displayed in the CLI output. The URL format for APAC is:

```
https://voiceverify-<random-id>.apac.runtime.vonage.com
```

#### 7-1. Get the Deployment URL

Run `vcr deploy` and copy the URL from the output:

```bash
cd backend
vcr deploy
# Output includes:
#   Deployed to https://voiceverify-abc123.apac.runtime.vonage.com
```

Alternatively, list existing deployments:

```bash
vcr list
```

#### 7-2. Verify the Backend is Running

```bash
curl -s https://voiceverify-<random-id>.apac.runtime.vonage.com/api/auth/users
# Should return [] or a JSON response (not a connection error)
```

#### 7-3. Create `android-app/.env`

Create `android-app/.env` with the deployment URL (use `.env.sample` as a template):

```bash
cd android-app
cp .env.sample .env
```

Edit `.env` and set `BACKEND_URL`:

```env
BACKEND_URL=https://voiceverify-abc123.apac.runtime.vonage.cloud/
```

> **Note:** `BACKEND_URL` must end with a trailing slash `/`.

#### 7-4. How `BACKEND_URL` is Resolved

The Android app reads `BACKEND_URL` at build time via `BuildConfig` from `android-app/.env`:

```env
BACKEND_URL=https://voiceverify-abc123.apac.runtime.vonage.cloud/
```

Priority order:
1. `android-app/.env` — **recommended** (ignored by git, keeps secrets safe)
2. `-PBACKEND_URL=...` (Gradle `-P` flag)
3. `BACKEND_URL` in `gradle.properties`
4. System environment variable `BACKEND_URL`
5. Default: `http://localhost:3000` (placeholder, only for local development)

### Deployed Routes

| Path | Method | Description |
|------|--------|-------------|
| `/api/auth/send-otp` | POST | Send SMS OTP |
| `/api/auth/verify-otp` | POST | Verify SMS OTP |
| `/api/auth/users` | POST | List verified users |
| `/api/voice/ncco` | GET | NCCO for inbound calls |
| `/api/voice/call` | POST | Initiate outgoing PSTN call |
| `/api/voice/auth/token` | POST | Generate InApp Voice JWT token |
| `/api/voice/register` | POST | Register/unregister InApp user |
| `/api/voice/reconnect` | POST | Generate fresh JWT for reconnection |
| `/webhooks/voice-events` | POST | Call event callback |

---

## Android App — Build & Release APK

### Preparing the Build

#### 1. Update Gradle Configuration

Edit `android-app/app/build.gradle` line with namespace to match your package:

```gradle
namespace 'com.example.voiceverify'
```

#### 2. Configure Signing

**Option A: Create a keystore** (macOS)

```bash
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore ../keystore/voiceverify.jks \
  -alias voiceverify-key \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass your-store-password \
  -keypass your-key-password \
  -dname "CN=VoiceVerify, OU=Mobile, O=YourCompany, L=City, ST=State, C=US"
```

**Option B: Use Android Studio**
- `Build` → `Generate Signed Bundle / APK`
- Follow the wizard to create a new keystore

---

### Build via Command Line

#### 1. Configure via `.env` file (recommended)

Create `android-app/.env` (use `.env.sample` as a template):

```env
VONAGE_APPLICATION_ID=your-app-id
BACKEND_URL=https://voiceverify-abc123.apac.runtime.vonage.cloud/
```

The `.env` file is automatically read by the build script and **ignored by git**.

#### 2. Alternative: Gradle `-P` Flags

Pass all required parameters directly:

```bash
cd android-app

./gradlew assembleRelease \
  -PVONAGE_API_KEY="your-api-key" \
  -PVONAGE_API_SECRET="your-api-secret" \
  -PVONAGE_APPLICATION_ID="your-app-id" \
  -PVONAGE_PRIVATE_KEY_PATH="keys/vonage_private.key" \
  -PBACKEND_URL="https://voiceverify-xyz.vonagedaas.com/" \
  -PSIGNING_STORE_FILE="../keystore/voiceverify.jks" \
  -PSIGNING_STORE_PASSWORD="your-store-password" \
  -PSIGNING_KEY_ALIAS="voiceverify-key" \
  -PSIGNING_KEY_PASSWORD="your-key-password"
```

#### 3. Alternative: `gradle.properties`

Append to `android-app/gradle.properties`:

```properties
VONAGE_API_KEY=your-api-key
VONAGE_API_SECRET=your-api-secret
VONAGE_APPLICATION_ID=your-app-id
VONAGE_PRIVATE_KEY_PATH=keys/vonage_private.key
BACKEND_URL=https://voiceverify-xyz.vonagedaas.com/

# Signing
SIGNING_STORE_FILE=../keystore/voiceverify.jks
SIGNING_STORE_PASSWORD=your-store-password
SIGNING_KEY_ALIAS=voiceverify-key
SIGNING_KEY_PASSWORD=your-key-password
```

Then build:

```bash
./gradlew assembleRelease
```

> **Warning:** `gradle.properties` is tracked by git — do not commit real secrets.

#### 4. Build Unsigned APK

Without signing (for testing only):

```bash
./gradlew assembleRelease
```

#### 5. Build Debug APK (local testing)

```bash
./gradlew assembleDebug
```

> Set `BACKEND_URL` to the VCR deployment URL or a local Cloudflare Tunnel URL.

---

### Build via Android Studio

1. Open the project: `File → Open → android-app/`
    - Select `Use embedded JDK (recommended)` when prompted.
2. Set `BACKEND_URL` in `BuildConfig`:
    - Click the **Android** dropdown → **Manage Variants**
    - Or edit `app/build.gradle` directly, or define in **gradle.properties** as shown above.
3. Create a keystore:
    - Go to `Build` → `Generate Signed Bundle / APK`
    - Select **APK** → **Create New** keystore
    - Fill in password and key details
    - Select **release** build variant
4. Sync Gradle and wait for dependencies to download.
5. Build the signed APK by going to `Build` → `Generate Signed Bundle / APK`.

---

## Local Development

### Backend (with Cloudflare Tunnel for webhook testing)

Prerequisites: Install Cloudflare Tunnel (https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)

```bash
# Terminal 1 — Start backend locally
cd backend
npm run dev

# Terminal 2 — Expose port 3000 to the internet
cloudflared tunnel --url http://localhost:3000
```

Copy the Cloudflare Tunnel URL and set it in `.env` and Gradle config:

- `APP_DOMAIN` (in `.env`, for local webhook verification)
- `BACKEND_URL` (in Gradle config)

### Android (Emulator)

Run the `debug` variant from Android Studio or CLI. No signing required.
Ensure the emulator has internet access and set `BACKEND_URL` to the Cloudflare Tunnel URL or use your machine's LAN IP (e.g., `http://192.168.1.100:3000/` via WiFi on the emulator).

---

## Troubleshooting

### Backend

| Problem | Solution |
|---------|----------|
| `vcr deploy` fails | Verify Node.js 20+ is installed. Run `node --version` and `npm --version`. |
| `API_KEY` / `API_SECRET` errors | Double-check credentials at dashboard.nvonage.com. |
| Vonage webhooks 404 | For local development, ensure `APP_DOMAIN` in `.env` matches your Cloudflare Tunnel URL. The webhook path must be exactly `/webhooks/voice-events` and `/api/voice/ncco`. After VCR deployment, webhooks are configured automatically. |
| MongoDB connection refused | Check `MONGODB_URI` is set to your MongoDB Atlas connection string. Ensure IP is whitelisted in Atlas and the connection string is correct. |
| JWT token generation fails | Verify `VONAGE_PRIVATE_KEY_PATH` points to a valid RSA private key matching `VONAGE_APPLICATION_ID`. |

### Android

| Problem | Solution |
|---------|----------|
| `Vonage credentials not configured` log | Ensure `VONAGE_API_KEY`, `VONAGE_API_SECRET`, and `VONAGE_APPLICATION_ID` are passed correctly via `-P` flags or `gradle.properties`. |
| Retrofit connection refused | Verify `BACKEND_URL` is correct and the backend is reachable from the device/emulator. For emulators, use `10.0.2.2` instead of `localhost` to refer to the host machine. |
| **NetworkSecurityException** on Android 9+ | Ensure `android:usesCleartextTraffic="true"` is set in **AndroidManifest.xml** for HTTP, or use HTTPS. |
| InApp Voice call drops on screen off | The app uses a **ForegroundService** (`VonageVoiceService`) with a notification — ensure the user does not kill the service from system settings. |
| Keystore password forgotten | Generate a new keystore. The old APK cannot be updated without the original keystore. |

### General

| Problem | Solution |
|---------|----------|
| SMS OTP fails | Check your Vonage account balance. Verify `MONGODB_URI` is set (used to store verification state). |
| InApp calls never connect | Verify the JWT token is being fetched from `/api/voice/auth/token` and used to register the app user via `registerAppUser()`. |
| APK install fails | Ensure the device has `Install from unknown sources` enabled. If upgrading, the APK must be signed with the **same keystore**. |

---

## Environment Variables Reference

### Backend (`.env`)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `VONAGE_API_KEY` | **Yes** | — | Vonage API Key |
| `VONAGE_API_SECRET` | **Yes** | — | Vonage API Secret |
| `VONAGE_APPLICATION_ID` | **Yes** | — | Vonage Application ID |
| `VONAGE_PRIVATE_KEY_PATH` | **Yes** | `./keys/vonage_private.key` | RSA private key path |
| `VONAGE_BRAND_NAME` | No | `VoiceVerify` | Caller ID display name |
| `VONAGE_PHONE_NUMBER` | **Yes** | — | Vonage phone number (E.164 format, e.g. `+818012345678`) for PSTN calls |
| `MONGODB_URI` | **Yes** | `mongodb+srv://user:pass@cluster0.xxxxx.mongodb.net/` | MongoDB Atlas connection string |
| `MONGODB_DB_NAME` | No | `voiceverify` | MongoDB database name |
| `APP_DOMAIN` | No (local dev only) | — | Public URL for local tunnel webhook verification |
| `PORT` | No | `3000` | Dev server port |

### Android (`.env` / Gradle `-P` flags / `gradle.properties`)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `VONAGE_API_KEY` | **Yes** | — | Same as backend |
| `VONAGE_API_SECRET` | **Yes** | — | Same as backend |
| `VONAGE_APPLICATION_ID` | **Yes** | — | Same as backend |
| `VONAGE_PRIVATE_KEY_PATH` | **Yes** | `vonage_private.key` | Key path (stored in BuildConfig) |
| `BACKEND_URL` | **Yes** | `http://localhost:3000` (placeholder) | Backend API base URL (set in `.env`) |
| `SIGNING_STORE_FILE` | Yes (release) | — | Keystore file path |
| `SIGNING_STORE_PASSWORD` | Yes (release) | — | Keystore password |
| `SIGNING_KEY_ALIAS` | Yes (release) | — | Key alias in keystore |
| `SIGNING_KEY_PASSWORD` | Yes (release) | — | Key password |

**Priority chain for `BACKEND_URL`:**
1. `android-app/.env` (local, gitignored) — **recommended**
2. `-PBACKEND_URL=...` (Gradle `-P` flag)
3. `BACKEND_URL` in `gradle.properties`
4. System environment variable `BACKEND_URL`
5. Default: `http://localhost:3000` (local development only)
