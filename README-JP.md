# Vonage Voice Verify

Verify V2 認証と InApp モバイルアプリケーション発信デモ、Vonage Cloud Runtime (APAC) バックエンド。

- **SMS OTP** 認証によるログイン（Vonage Verify V2）
- 電話番号入力用の**ダイアルパッド**
- Vonage InApp Voice SDK による**InApp Voice**（データ通信経由のリアルタイム音声通話）
- 電話番号と通話状態を **MongoDB** に永続化

---

## 目次

1. [プロジェクト構造](#プロジェクト構造)
2. [前提条件](#前提条件)
3. [Vonage のセットアップ](#vonage-のセットアップ)
4. [MongoDB のセットアップ](#mongodb-のセットアップ)
5. [Backend のデプロイ](#backend-のデプロイ)
6. [Android アプリ — APK のビルドとリリース](#android-アプリ--apk-のビルドとリリース)
7. [ローカル開発](#ローカル開発)
8. [トラブルシューティング](#トラブルシューティング)

---

## プロジェクト構造

```
verify-inapp-mobilevoice-app/
├── backend/                  # Vonage Cloud Runtime (VCR) API
│   ├── src/
│   │   ├── index.ts          # VCR 用ルートエントリポイント
│   │   ├── config.ts         # 環境設定ローダー
│   │   ├── db.ts             # MongoDB コネクション
│   │   ├── models/PhoneUser.ts
│   │   ├── types/vcr.ts
│   │   ├── routes/
│   │   │   ├── auth.ts       # SMS OTP 送信 / 認証
│   │   │   ├── voice.ts      # NCCO, 通話開始
│   │   │   └── voiceAuth.ts  # JWT token, register/unregister, push-token, reconnect
│   │   └── webhooks.ts       # 通話イベント webhook ハンドラー
│   ├── vcr.json              # VCR デプロイ設定 (APAC, nodejs20)
│   ├── package.json
│   ├── tsconfig.json
│   └── .env.example
│
└── android-app/              # Kotlin Android アプリ
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
    ├── .env.sample            # テンプレート — .env にコピーして設定
    └── .env                   # ローカル設定（git で無視）
```

---

## 前提条件

| 必須項目 | 最小バージョン | 備考 |
|-------------|----------------|-------|
| Node.js | 20.x LTS | Backend 用 |
| npm / pnpm | ≥ 9.x | Backend 用 |
| JDK | 17 | Android ビルド用 |
| Android Studio | ≥ 2023.2 (Flyfish) | SDK 34 付き |
| Android Gradle Plugin | 8.7.0 | Gradle wrapper で管理 |
| Vonage アカウント | — | Voice + Verify API 有効 |
| MongoDB | 5.0+ | Atlas または自前ホスト |

---

## Vonage のセットアップ

Vonage Application は、Vonage ダッシュボード経由で**事前に作成**する必要があります。CLI からは作成できません。

### 1. Vonage Application (Voice) をダッシュボードで作成

1. **https://dashboard.vonage.com** → **API Integrations** にアクセス
2. **Create a new application** をクリック
3. タイプとして **Voice** を選択
4. 名前（例：`voiceverify`）を入力して **Create** をクリック
5. アプリケーション設定ページから以下の値を記録：

    | 値 | 説明 | 使用箇所 |
    |-------|-------------|---------|
    | **Application ID** | アプリケーションの一意の識別子 | `VONAGE_APPLICATION_ID` |
    | **API Key** | Vonage API 呼び出し用の認証情報 | `VONAGE_API_KEY` |
    | **API Secret** | API シークレットキー | `VONAGE_API_SECRET` |

6. **Download key** をクリックして**プライベートキー**（JWT 署名用の RSA キーペア）をダウンロードし、以下に保存：
    ```
    backend/keys/vonage_private.key
    ```

> **注意：** Application ID は**1 回だけ生成**され、**変更されません**。
    > 開発、ステージング、本番など全ての環境で同じ Application ID を再利用してください。
    > そうしないと、通話ルーティング、webhook、登録されたユーザーが壊れる可能性があります。
    > プライベートキーは決して変更しないでください。紛失した場合は、新しい Application（新しい ID）を作成する必要があります。

### 2. Webhook URL の設定（デプロイ後）

VCR へのデプロイ後、Vonage Application で Answer URL と Event URL を登録する必要があります。

**方法 A — Vonage CLI**

```bash
vonage apps:update <app-id> \
  --voice-answer-url https://<vcr-deployment-url>/api/voice/ncco \
  --voice-event-url https://<vcr-deployment-url>/webhooks/voice-events
```

**方法 B — ダッシュボード**

1. **https://dashboard.vonage.com** → **API Integrations** → 自身のアプリケーションにアクセス
2. **Voice Settings** で以下を設定：

    | 設定 | URL |
    |---------|-----|
    | **Answer URL** | `https://<vcr-deployment-url>/api/voice/ncco` |
    | **Event Callback URL** | `https://<vcr-deployment-url>/webhooks/voice-events` |

デプロイされたルート：

| 目的 | エンドポイント |
|---------|----------|
| **NCCO エンドポイント** (Answer URL) | `https://<vcr-deployment-url>/api/voice/ncco` |
| **Event Callback URL** | `https://<vcr-deployment-url>/webhooks/voice-events` |

> **注意：** `<vcr-deployment-url>` を実際の VCR デプロイドメインに置き換えてください（例：`https://voiceverify-xxxxx.apac.runtime.vonage.com`）。

### 3. Verify API の有効化

Verify V2（SMS OTP）は、Vonage ダッシュボードから自動的に動作します。`API_KEY` と `API_SECRET` があること以外、追加セットアップは不要です。

### 4. Vonage 電話番号の取得

PSTN の着信・発信（Voice Proxy フォールバック）には Vonage 電話番号が必要です。

#### 4-1. 番号の購入

以下のサイトで電話番号を購入してください：
https://dashboard.vonage.com/number-directory

> **注意:** 日本展開の場合は、日本の電話番号（例: `+818012345678`）を購入してください。

#### 4-2. アプリケーションに番号をリンク

購入後、Voice Application に番号をリンクします：

**Vonage CLI を使用:**
```bash
vonage numbers:link <your-phone-number> --appid=<your-application-id>
```

**ダッシュボードを使用:**
1. **https://dashboard.vonage.com** → **Your Numbers** にアクセス
2. 購入した番号をクリック
3. **Voice** でアプリケーション（`voiceverify`）を選択し **Save** をクリック

#### 4-3. 環境変数に設定

`backend/.env` に電話番号を追加：

```env
VONAGE_PHONE_NUMBER=+818012345678
```

> **注意:** E.164 形式（例: `+818012345678`）で指定してください。

この番号は以下の用途で使用されます：
- PSTN 発信時の **Caller ID**
- 着信 webhook の **ターゲット**（Application 設定で `/api/voice/ncco` にリンク）

---

## MongoDB のセットアップ

1. https://mongodb.com/cloud/atlas で無料クラスターを作成
2. データベースユーザーを作成（`username` と `password` を設定）
3. IP `0.0.0.0/0` を Whitelist（または VCR デプロイの IP 範囲）
4. 接続文字列を取得：
    ```
    mongodb+srv://<username>:<password>@<cluster-url>/voiceverify?retryWrites=true
    ```
5. 接続文字列を `backend/.env` の `MONGODB_URI` としてコピー。

---

## Backend のデプロイ

### ステップ 1 — VCR CLI をインストール

```bash
npm install -g @vonage/vcr-cli
```

### ステップ 2 — 環境変数の設定

```bash
cd backend

# 認証情報をコピーして入力
cp .env.example .env
```

実際の値で `.env` を編集：

| 変数 | 説明 |
|----------|-------------|
| `VONAGE_API_KEY` | Vonage API Key |
| `VONAGE_API_SECRET` | Vonage API Secret |
| `VONAGE_APPLICATION_ID` | Vonage Application ID (Voice) — ダッシュボードで設定 |
| `VONAGE_PRIVATE_KEY_PATH` | RSA プライベートキーへのパス |
| `VONAGE_BRAND_NAME` | caller ID に表示されるブランド名 |
| `VONAGE_PHONE_NUMBER` | Vonage 電話番号（E.164 形式、例: `+818012345678`）PSTN 通話用 |
| `MONGODB_URI` | MongoDB Atlas 接続文字列 |
| `MONGODB_DB_NAME` | データベース名（デフォルト：`voiceverify`） |

例：

```env
VONAGE_API_KEY=abc123
VONAGE_API_SECRET=xyz789
VONAGE_APPLICATION_ID=abc123-def456-789ghi
VONAGE_PRIVATE_KEY_PATH=./keys/vonage_private.key
VONAGE_BRAND_NAME=VoiceVerify
VONAGE_PHONE_NUMBER=+818012345678
MONGODB_URI=mongodb+srv://user:pass@cluster0.xxxxx.mongodb.net/voiceverify?retryWrites=true
MONGODB_DB_NAME=voiceverify
# APP_DOMAIN と PORT はローカル開発時のみ必要
```

### ステップ 3 — Keys ディレクトリの作成

```bash
mkdir -p keys
# 「Vonage のセットアップ」ステップ 2 から RSA プライベートキーを配置
cp ~/vonage_private.key keys/vonage_private.key
```

### ステップ 4 — ビルド & デプロイ

```bash
npm install
npm run build
vcr deploy
```

### ステップ 5 — Webhook URL の設定

デプロイ後、Vonage Application で webhook URL を登録：

**Vonage CLI 経由：**
```bash
vonage apps:update <app-id> \
  --voice-answer-url https://<your-deployment-url>/api/voice/ncco \
  --voice-event-url https://<your-deployment-url>/webhooks/voice-events
```

**ダッシュボード経由：** https://dashboard.vonage.com → API Integrations → 自身のアプリケーション → Voice Settings

**Answer URL** を `https://<your-deployment-url>/api/voice/ncco` に、**Event Callback URL** を `https://<your-deployment-url>/webhooks/voice-events` に設定。

### ステップ 6 — デプロイの確認

デプロイ後、サービスは以下で稼働します：
```
https://<your-deployment-url>
```

### ステップ 7 — Android アプリ用の BACKEND_URL を設定

`vcr deploy` 完了後、CLI 出力にデプロイ URL が表示されます。APAC の URL フォーマット：

```
https://<your-deployment-url>.apse1.runtime.vonage.cloud
```

#### 7-1. デプロイ URL の取得

`vcr deploy` を実行し、出力から URL をコピー：

```bash
cd backend
vcr deploy
# 出力例：
#   Deployed to https://<your-deployment-url>.apse1.runtime.vonage.cloud
```

既存のデプロイを一覧表示：

```bash
vcr list
```

#### 7-2. Backend の稼働確認

```bash
curl -s https://<your-deployment-url>.apse1.runtime.vonage.cloud/api/auth/users
# [] または JSON レスポンスが返る（接続エラーではないこと）
```

#### 7-3. Android ビルド用の BACKEND_URL を設定

`android-app/.env` を作成（`.env.sample` をテンプレートとして使用）：

```bash
cd android-app
cp .env.sample .env
```

`.env` を編集し `BACKEND_URL` を設定：

```env
BACKEND_URL=https://<your-deployment-url>.apse1.runtime.vonage.cloud/
```

> **注意:** `BACKEND_URL` は末尾にスラッシュ `/` を付ける必要があります。

#### 7-4. `BACKEND_URL` の解決順序

Android アプリはビルド時に `android-app/.env` から `BACKEND_URL` を読み込みます：

```env
BACKEND_URL=https://<your-deployment-url>.apse1.runtime.vonage.cloud/
```

優先度順：
1. `android-app/.env` — **推奨**（git で無視され、シークレットを安全に保つ）
2. `-PBACKEND_URL=...`（Gradle `-P` フラグ）
3. `gradle.properties` の `BACKEND_URL`
4. システム環境変数 `BACKEND_URL`
5. デフォルト: `http://localhost:3000`（プレースホルダー、ローカル開発のみ）

### デプロイ済みルート

| パス | メソッド | 説明 |
|------|--------|-------------|
| `/api/auth/send-otp` | POST | SMS OTP 送信 |
| `/api/auth/verify-otp` | POST | SMS OTP 認証 |
| `/api/auth/users` | POST | 認証済みユーザー一覧 |
| `/api/voice/ncco` | GET | 着信通話用 NCCO |
| `/api/voice/call` | POST | 発信 PSTN 通話の開始 |
| `/api/voice/auth/token` | POST | InApp Voice JWT token の生成 |
| `/api/voice/register` | POST | InApp ユーザーの登録/解除 |
| `/api/voice/reconnect` | POST | 再接続用の新鮮な JWT を生成 |
| `/webhooks/voice-events` | POST | 通話イベントコールバック |

---

## Android アプリ — APK のビルドとリリース

### ビルドの準備

#### 1. Gradle 設定の更新

パッケージに合わせた名前空間の `android-app/app/build.gradle` ラインを編集：

```gradle
namespace 'com.example.voiceverify'
```

#### 2. 署名の設定

**方法 A：キーストアの作成**（macOS）

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

**方法 B：Android Studio を使用**
- `Build` → `Generate Signed Bundle / APK`
- ウィザードに従って新しいキーストアを作成

---

### コマンドライン経由でビルド

#### 1. `.env` ファイルで設定（推奨）

`android-app/.env` を作成（`.env.sample` をテンプレートとして使用）：

```env
VONAGE_APPLICATION_ID=your-app-id
BACKEND_URL=https://voiceverify-abc123.apac.runtime.vonage.cloud/
```

`.env` ファイルはビルドスクリプトが自動的に読み取り、**git で無視されます**。

#### 2. 代替手段：Gradle `-P` フラグ

必要なパラメータを直接渡します：

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

#### 3. 代替手段：`gradle.properties` を使用

`android-app/gradle.properties` に追記：

```properties
VONAGE_API_KEY=your-api-key
VONAGE_API_SECRET=your-api-secret
VONAGE_APPLICATION_ID=your-app-id
VONAGE_PRIVATE_KEY_PATH=keys/vonage_private.key
BACKEND_URL=https://voiceverify-xyz.vonagedaas.com/

# 署名
SIGNING_STORE_FILE=../keystore/voiceverify.jks
SIGNING_STORE_PASSWORD=your-store-password
SIGNING_KEY_ALIAS=voiceverify-key
SIGNING_KEY_PASSWORD=your-key-password
```

その後ビルド：

```bash
./gradlew assembleRelease
```

> **警告:** `gradle.properties` は git で追跡されます — 実際のシークレットをコミットしないでください。

#### 4. 未署名 APK のビルド

署名なし（テスト用）：

```bash
./gradlew assembleRelease
```

#### 5. Debug APK のビルド（ローカルテスト用）

```bash
./gradlew assembleDebug
```

> `BACKEND_URL` に VCR デプロイ URL またはローカル Cloudflare Tunnel URL を設定。

---

### Android Studio 経由でビルド

1. プロジェクトを開く：`File → Open → android-app/`
    - プロンプトが表示されたら `Use embedded JDK (recommended)` を選択。
2. `BuildConfig` に `BACKEND_URL` を設定：
    - **Android** ドロップダウン → **Manage Variants** をクリック
    - または `app/build.gradle` を直接編集するか、前述のように**gradle.properties**で定義。
3. キーストアを作成：
    - `Build` → `Generate Signed Bundle / APK` に移動
    - **APK** を選択 → **Create New** キーストア
    - パスワードとキーの詳細を入力
    - **release** ビルドバリアントを選択
4. Gradle を同期し、依存関係のダウンロードを待つ。
5. `Build` → `Generate Signed Bundle / APK` にて署名済み APK をビルド。

---

## ローカル開発

### Backend（Webhook テスト用に Cloudflare Tunnel を使用）

事前に Cloudflare Tunnel をインストール：https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/

```bash
# Terminal 1 — backend をローカルで開始
cd backend
npm run dev

# Terminal 2 — ポート 3000 をインターネットに公開
cloudflared tunnel --url http://localhost:3000
```

Cloudflare Tunnel URL をコピーして、`.env` と Gradle 設定に設定：

- `APP_DOMAIN`（`.env` 内、ローカル webhook 検証用）
- `BACKEND_URL`（Gradle 設定内）

### Android（エミュレーター）

Android Studio または CLI から `debug` バリアントを実行。署名は不要。
エミュレーターがインターネットアクセスできることを確認し、`BACKEND_URL` に Cloudflare Tunnel URL またはマシンの LAN IP（例：エミュレーターの WiFi 経由 `http://192.168.1.100:3000/`）を設定。

---

## トラブルシューティング

### Backend

| 問題 | 解決策 |
|---------|----------|
| `vcr deploy` が失敗 | Node.js 20+ がインストールされていることを確認。`node --version` と `npm --version` を実行。 |
| `API_KEY` / `API_SECRET` エラー | dashboard.vonage.com で認証情報を再確認。 |
| Vonage webhook 404 | ローカル開発では、`.env` の `APP_DOMAIN` が Cloudflare Tunnel URL と一致していることを確認。webhook パスは正確に `/webhooks/voice-events` と `/api/voice/ncco` でなければなりません。VCR デプロイ後、webhook は自動的に設定されます。 |
| MongoDB 接続拒否 | `MONGODB_URI` が MongoDB Atlas 接続文字列に設定されているか確認。Atlas で IP が Whitelist されていること、接続文字列が正しいことを確認。 |
| JWT token 生成失敗 | `VONAGE_PRIVATE_KEY_PATH` が `VONAGE_APPLICATION_ID` に一致する有効な RSA プライベートキーを指していることを確認。 |

### Android

| 問題 | 解決策 |
|---------|----------|
| `Vonage credentials not configured` ログ | `-P` フラグまたは `gradle.properties` 経由で `VONAGE_API_KEY`、`VONAGE_API_SECRET`、`VONAGE_APPLICATION_ID` が正しく渡されていることを確認。 |
| Retrofit 接続拒否 | `BACKEND_URL` が正しいこと、デバイス/エミュレーターから backend に到達可能であることを確認。エミュレーターのには `localhost` の代わりに `10.0.2.2` を使用してホストマシンを参照。 |
| Android 9+ で**NetworkSecurityException** | HTTP 用**AndroidManifest.xml** で `android:usesCleartextTraffic="true"` が設定されていることを確認、または HTTPS を使用。 |
| InApp Voice 通話が画面オフで切断 | アプリが通知付きの**ForegroundService**（`VonageVoiceService`）を使用 — ユーザーがシステム設定からサービスをKill しないことを確認。 |
| キーストアパスワードを忘れた | 新しいキーストアを生成。元のキーストアなしでは、古い APK は更新できません。 |

### 全般

| 問題 | 解決策 |
|---------|----------|
| SMS OTP が失敗 | Vonage アカウントの残高を確認。`MONGODB_URI` が設定されていることを確認（状態保存に使用）。 |
| InApp 通話が絶対に接続しない | JWT token が `/api/voice/auth/token` からフェッチされ、`registerAppUser()` を介してアプリユーザーの登録に使用されていることを確認。 |
| APK インストール失敗 | デバイスで `unknown sources からのインストール` が有効になっていることを確認。アップグレードする場合は、APK は**同じキーストア**で署名されている必要があります。 |

---

## 環境変数リファレンス

### Backend (`.env`)

| 変数 | 必須 | デフォルト | 説明 |
|----------|----------|---------|-------------|
| `VONAGE_API_KEY` | **必須** | — | Vonage API Key |
| `VONAGE_API_SECRET` | **必須** | — | Vonage API Secret |
| `VONAGE_APPLICATION_ID` | **必須** | — | Vonage Application ID |
| `VONAGE_PRIVATE_KEY_PATH` | **必須** | `./keys/vonage_private.key` | RSA プライベートキーへのパス |
| `VONAGE_BRAND_NAME` | いいえ | `VoiceVerify` | caller ID 表示名 |
| `VONAGE_PHONE_NUMBER` | **必須** | — | Vonage 電話番号（E.164 形式、例: `+818012345678`）PSTN 通話用 |
| `MONGODB_URI` | **必須** | `mongodb+srv://user:pass@cluster0.xxxxx.mongodb.net/` | MongoDB Atlas 接続文字列 |
| `MONGODB_DB_NAME` | いいえ | `voiceverify` | MongoDB データベース名 |
| `APP_DOMAIN` | いいえ（ローカル開発のみ） | — | ローカル tunnel webhook 検証用の公開 URL |
| `PORT` | いいえ | `3000` | 開発サーバーポート |

### Android（`.env` / Gradle `-P` フラグ / `gradle.properties`）

| 変数 | 必須 | デフォルト | 説明 |
|----------|----------|---------|-------------|
| `VONAGE_API_KEY` | **必須** | — | backend と同様 |
| `VONAGE_API_SECRET` | **必須** | — | backend と同様 |
| `VONAGE_APPLICATION_ID` | **必須** | — | backend と同様 |
| `VONAGE_PRIVATE_KEY_PATH` | **必須** | `vonage_private.key` | キーパス（BuildConfig に保存） |
| `BACKEND_URL` | **必須** | `http://localhost:3000`（プレースホルダー） | Backend API ベース URL（`.env` で設定） |
| `SIGNING_STORE_FILE` | 必須（release） | — | キーストアファイルパス |
| `SIGNING_STORE_PASSWORD` | 必須（release） | — | キーストアパスワード |
| `SIGNING_KEY_ALIAS` | 必須（release） | — | キーストア内のキーエイリアス |
| `SIGNING_KEY_PASSWORD` | 必須（release） | — | キーパスワード |

**`BACKEND_URL` の優先順位：**
1. `android-app/.env`（ローカル、git で無視）— **推奨**
2. `-PBACKEND_URL=...`（Gradle `-P` フラグ）
3. `gradle.properties` の `BACKEND_URL`
4. システム環境変数 `BACKEND_URL`
5. デフォルト: `http://localhost:3000`（ローカル開発のみ）
