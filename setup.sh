#!/bin/bash
# Vonage VoiceVerify Project Setup Script (VCR APAC)
# Usage: ./setup.sh

set -e

echo "============================================"
echo "  VoiceVerify Setup"
echo "  VCR APAC Region Deployment"
echo "============================================"
echo ""

# ──────────────────────────────────────────────
# Pre-flight checks
# ──────────────────────────────────────────────

# Check Node.js version (20+)
echo "--- Pre-flight Checks ---"
if ! command -v node &> /dev/null; then
    echo "[ERROR] Node.js not found. Install Node.js 20+ from https://nodejs.org/"
    exit 1
fi

NODE_VERSION=$(node -v | sed 's/v//' | cut -d. -f1)
if [ "$NODE_VERSION" -lt 20 ]; then
    echo "[ERROR] Node.js 20+ required (current: $(node -v))"
    exit 1
fi
echo "[OK] Node.js $(node -v)"

# Check npm
if ! command -v npm &> /dev/null; then
    echo "[ERROR] npm not found."
    exit 1
fi
echo "[OK] npm $(npm -v)"

# Check Java (JDK 17+)
if ! command -v java &> /dev/null; then
    echo "[WARN] Java not found. JDK 17+ required for Android build."
else
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | sed 's/.*"\([0-9]*\).*/\1/')
    echo "[OK] Java $JAVA_VERSION"
fi

# Check VCR CLI
if ! command -v vcr &> /dev/null; then
    echo "[WARN] VCR CLI not found. Installing..."
    npm install -g @vonage/vcr-cli
    echo "[OK] VCR CLI installed"
else
    echo "[OK] VCR CLI $(vcr --version 2>/dev/null || echo 'installed')"
fi

# Check Vonage CLI (optional, for webhook configuration)
if ! command -v vonage &> /dev/null; then
    echo "[INFO] Vonage CLI not found (optional). Install with: npm install -g vonage-cli"
    echo "       Required for: vonage apps:update (webhook URL configuration)"
else
    echo "[OK] Vonage CLI installed"
fi

echo ""

# ──────────────────────────────────────────────
# 1. Vonage Application Setup (Manual)
# ──────────────────────────────────────────────
echo "--- 1. Vonage Application Setup ---"
echo ""
echo "Create a Vonage Application manually via the Dashboard:"
echo "  1. Go to https://dashboard.nvonage.com → API Integrations"
echo "  2. Click 'Create a new application' → Select 'Voice'"
echo "  3. Record the following values (set in backend/.env):"
echo "     - Application ID → VONAGE_APPLICATION_ID"
echo "     - API Key       → VONAGE_API_KEY"
echo "     - API Secret    → VONAGE_API_SECRET"
echo "  4. Download the Private Key (RSA keypair)"
echo "     → Save as backend/keys/vonage_private.key"
echo ""
echo "  IMPORTANT: The private key must match the Application ID."
echo "             Never lose the private key — if lost, create a new Application."
echo ""

# ──────────────────────────────────────────────
# 2. Backend Setup
# ──────────────────────────────────────────────
echo "--- 2. Backend Setup ---"

# Create keys directory
mkdir -p backend/keys

# Check if private key exists
if [ -f backend/keys/vonage_private.key ]; then
    echo "[OK] Private key found: backend/keys/vonage_private.key"
else
    echo "[WARN] Private key not found at backend/keys/vonage_private.key"
    echo "       Download from Vonage Dashboard and save there."
fi

# Copy .env.example to .env
if [ ! -f backend/.env ]; then
    cp backend/.env.example backend/.env
    echo "[OK] Created backend/.env from .env.example"
else
    echo "[OK] backend/.env already exists"
fi

# Check if required variables are set in .env
MISSING_VARS=""
for VAR in VONAGE_API_KEY VONAGE_API_SECRET VONAGE_APPLICATION_ID VONAGE_PRIVATE_KEY_PATH VONAGE_PHONE_NUMBER MONGODB_URI; do
    # Check if the variable is set and not empty (ignoring comments)
    VALUE=$(grep "^${VAR}=" backend/.env 2>/dev/null | head -1 | cut -d= -f2-)
    if [ -z "$VALUE" ]; then
        MISSING_VARS="$MISSING_VARS  - $VAR\n"
    fi
done

if [ -n "$MISSING_VARS" ]; then
    echo "[WARN] The following variables are not set in backend/.env:"
    printf "$MISSING_VARS"
    echo "     Edit backend/.env and fill in all required values before deployment."
else
    echo "[OK] All required variables are set in backend/.env"
fi

# Verify private key file exists at the configured path
KEY_PATH=$(grep "^VONAGE_PRIVATE_KEY_PATH=" backend/.env 2>/dev/null | head -1 | cut -d= -f2-)
if [ -n "$KEY_PATH" ]; then
    # Resolve relative path from backend/
    if [[ "$KEY_PATH" = /* ]]; then
        FULL_KEY_PATH="$KEY_PATH"
    else
        FULL_KEY_PATH="backend/$KEY_PATH"
    fi
    if [ -f "$FULL_KEY_PATH" ]; then
        echo "[OK] Private key file verified: $FULL_KEY_PATH"
    else
        echo "[WARN] Private key file not found: $FULL_KEY_PATH"
        echo "       VONAGE_PRIVATE_KEY_PATH is set to '$KEY_PATH' but the file does not exist."
    fi
fi

# Check Firebase service account key
if [ -f backend/keys/firebase-service-account.json ]; then
    echo "[OK] Firebase service account key found"
else
    echo "[INFO] Firebase service account key not found (optional for server-initiated push)"
    echo "       Place at backend/keys/firebase-service-account.json"
fi

echo ""

# ──────────────────────────────────────────────
# 3. Backend Install & Build
# ──────────────────────────────────────────────
echo "--- 3. Backend Install ---"
cd backend

# Generate vcr.yml from .env (applies application-id and other config)
if [ -f .env ]; then
  echo "[INFO] Generating vcr.yml from .env..."
  APP_ID=$(grep ^VONAGE_APPLICATION_ID= .env | head -1 | cut -d= -f2- | sed 's/^"//' | sed 's/"$//')
  if [ -n "$APP_ID" ]; then
    sed "s/\${VONAGE_APPLICATION_ID}/$APP_ID/g" vcr.yml.example > vcr.yml
    echo "[OK] vcr.yml generated with application-id: $APP_ID"
  else
    echo "[WARN] VONAGE_APPLICATION_ID not set in .env — vcr.yml not generated"
    echo "       Run: cp vcr.yml.example vcr.yml and edit application-id manually"
  fi
else
  echo "[WARN] .env not found — skipping vcr.yml generation"
  echo "       Run: cp .env.example .env and fill in values"
fi

npm install
npm run build
cd ..
echo "[OK] Backend dependencies installed and built"
echo ""

# ──────────────────────────────────────────────
# 4. Android App Setup
# ──────────────────────────────────────────────
echo "--- 4. Android App Setup ---"

# Check google-services.json
if [ -f android-app/app/google-services.json ]; then
    echo "[OK] google-services.json found"
else
    echo "[WARN] google-services.json not found at android-app/app/google-services.json"
    echo "       Download from Firebase Console → Project Settings → Your apps → Android"
    echo "       Firebase Push Notifications will not work without it."
fi

# Check gradle.properties
if grep -q "BACKEND_URL" android-app/gradle.properties 2>/dev/null; then
    echo "[OK] BACKEND_URL found in gradle.properties"
else
    echo "[INFO] BACKEND_URL not set in gradle.properties"
    echo "       Set after VCR deployment:"
    echo "         export BACKEND_URL=https://<your-vcr-domain>/"
    echo "       Or add to android-app/gradle.properties:"
    echo "         BACKEND_URL=https://<your-vcr-domain>/"
fi

echo ""

# ──────────────────────────────────────────────
# 5. Next Steps
# ──────────────────────────────────────────────
echo "--- 5. Next Steps ---"
echo ""
echo "Step 1 — Deploy backend to VCR (APAC region):"
echo "  cd backend && vcr deploy"
echo "  → Note the deployment URL (e.g. https://voiceverify-xxxxx.apac.runtime.vonage.com)"
echo ""
echo "Step 2 — Configure Vonage Voice webhooks:"
echo "  vonage apps:update <app-id> \\"
echo "    --voice-answer-url https://<your-vcr-domain>/api/voice/ncco \\"
echo "    --voice-event-url https://<your-vcr-domain>/webhooks/voice-events"
echo "  Or configure via Dashboard: https://dashboard.nvonage.com → API Integrations"
echo ""
echo "Step 3 — Set BACKEND_URL and build Android app:"
echo "  cd android-app"
echo "  export BACKEND_URL=https://<your-vcr-domain>/"
echo "  ./gradlew assembleRelease"
echo "  → APK: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "Step 4 — Or build in Android Studio:"
echo "  Open android-app/ in Android Studio"
echo "  Build → Build Bundle(s) / APK(s) → Build APK(s)"
echo "  Or Build → Generate Signed Bundle / APK"
echo ""
echo "============================================"
echo "  Setup Complete!"
echo "============================================"
