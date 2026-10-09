# Sandesh — Android SMS Gateway

Lightweight Android SMS gateway that sends SMS automatically through the phone's SIM card. The phone polls your backend every 15 seconds, dispatches pending messages via `SmsManager`, and reports delivery status back. One phone can serve multiple backends (ERPs / applications) at the same time.

- **App name:** Sandesh
- **Application ID:** `com.sandesh.app`
- **minSdk 24 · targetSdk 34 · Kotlin · Material 3**

---

## 1. System Architecture & Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                      Backend / Application Server                      │
│                                                                        │
│ Backend Service ─► POST /api/v1/sms/send ───► [ sms_queue (pending) ]  │
│                                                          ▲             │
│   Audit UI    ◄─── GET /api/v1/sms/logs  ────────────────┤             │
└──────────────────────────────────────────────────────────┼─────────────┘
                                                           │ HTTPS
                                                           │ (poll every 15s)
┌──────────────────────────────────────────────────────────┼─────────────┐
│                      Android Phone                       │             │
│                                                          ▼             │
│   SmsForegroundService ───────────────► GET /api/v1/sms/pending?limit=10
│         │                                                │             │
│         ▼                                                │             │
│   SmsManager.sendTextMessage() (via SIM)                 │             │
│         │                                                │             │
│         ▼                                                │             │
│   Report result ──────────────────────► POST /api/v1/sms/status        │
└────────────────────────────────────────────────────────────────────────┘
```

### Flow summary

1. **Enqueue:** Your backend server calls `POST /api/v1/sms/send` to insert a message with status `pending` into `sms_queue`.
2. **Poll:** The phone's foreground service polls `GET /api/v1/sms/pending?limit=10` every 15 seconds for each enabled gateway.
3. **Dispatch:** The phone sends the message via SIM using `SmsManager` (multipart is handled automatically for messages over 160 GSM characters).
4. **Status update:** The phone posts the result (`sent`, `failed`, or `delivered`) to `POST /api/v1/sms/status`.
5. **Audit:** Query `GET /api/v1/sms/logs` to review delivery history.

---

## 2. Android App Setup & Usage

### Gateway configuration (Gateways tab)

Configure one gateway card per backend / application:

| Field | Example | Description |
|---|---|---|
| **Name** | `Primary-Backend` | Friendly label for the backend |
| **Base URL** | `https://api.yourdomain.com/api/v1/sms/` | Backend base URL (`https://` required, trailing `/` optional) |
| **Token** | `SMS_GATEWAY_TOKEN_SECRET` | Shared secret Bearer token |

### Step-by-step setup

1. **Add a gateway:** Open the Gateways tab → tap `Add` → enter Name, Base URL (`https://…`), and Token → **Save**.
2. **Enable it:** Turn the toggle **ON** on the gateway card to enable polling for that backend.
3. **Test it:** Tap **Test** — the app calls `GET /pending?limit=10` with `Authorization: Bearer <TOKEN>`.
   - Success: `✓ pending: N`
   - Error: `401 token invalid`, `429 rate limit`, or a network error.
4. **Grant permissions** (asked automatically on first launch):
   - **SMS** — required to send messages via SIM. If permanently denied, the app routes you to App Settings.
   - **Notifications** (Android 13+) — required for the foreground-service status notification.
   - **Phone** — required only to list SIM cards for manual SIM selection (asked in Settings).
5. **Select SIM (optional):** Settings → **SIM FOR SENDING** → tap a SIM (or leave **System default**).
6. **Start the service:** Home tab → **Start Service**. A persistent `Sandesh active` notification appears and polling starts every 15 seconds per enabled gateway.
7. **Disable battery optimization (important):**
   - When prompted, allow ignoring battery optimizations (Settings → Battery → Unrestricted).
   - On Xiaomi / Oppo / Vivo / Realme, also allow **Auto-start** and remove background restrictions so the OS does not kill the service.

### Service ON/OFF behavior

The service switch state is persisted:

- **OFF stays OFF** — after a reboot or app update, the service does **not** auto-start.
- **ON restores ON** — after a reboot, `BootReceiver` restarts polling automatically (WorkManager runs a 15-minute fallback in case the foreground start is blocked).

### Gateway management

- **Add:** floating `Add` button creates a new gateway.
- **Edit:** pencil icon updates URL or token.
- **Delete:** trash icon removes the gateway after confirmation.
- **Toggle (ON/OFF):** each backend is independent — if one backend is down, polling for the others is unaffected.

---

## 3. Authentication & Security

- **Strict HTTPS required.** `http://` URLs are rejected by the client.
- **Headers** on every request:
  ```http
  Authorization: Bearer <TOKEN>
  Content-Type: application/json
  ```
- **Rate limiting (recommended backend default):** `60 req/min` per token. On breach return `429 Too Many Requests`.
- **Phone numbers:** E.164 digits without `+` (10–15 digits, `^[0-9]{10,15}$`, e.g. `919876543210`).
- Tokens are stored in `EncryptedSharedPreferences`. If the encrypted store is ever corrupted (e.g. after a backup restore), the app wipes it once and recreates it instead of crash-looping. Encrypted prefs are excluded from cloud backup and device transfer for this reason.

---

## 4. Backend API Reference

Your backend must implement 4 REST endpoints:

1. `GET /api/v1/sms/pending` — polled by the phone
2. `POST /api/v1/sms/status` — delivery reports from the phone
3. `POST /api/v1/sms/send` — enqueue (called by your backend server)
4. `GET /api/v1/sms/logs` — audit history (called by dashboards)

### 4.1 GET /api/v1/sms/pending

Called by the phone every 15 seconds per enabled gateway.

- **Headers:** `Authorization: Bearer <SMS_GATEWAY_TOKEN>`
- **Query:** `limit` (integer, default `10`, max `50`)

Response `200 OK`:

```json
{
  "messages": [
    {
      "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
      "to": "919876543210",
      "message": "Your OTP verification code is 482913. Valid for 10 mins.",
      "templateId": "DLT-OTP-01",
      "created_at": "2026-09-19T10:00:00.000Z"
    }
  ]
}
```

Empty queue: `{ "messages": [] }`

```bash
curl -X GET "https://api.yourdomain.com/api/v1/sms/pending?limit=10" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN"
```

### 4.2 POST /api/v1/sms/status

Called by the phone after each send attempt.

- **Headers:** `Authorization: Bearer <SMS_GATEWAY_TOKEN>`, `Content-Type: application/json`
- **Body:**

```json
{ "id": "c7a10f82-938a-4d24-81eb-987814b7e120", "status": "sent", "error": null }
```

| Field | Required | Values |
|---|---|---|
| `id` | Yes | Message ID |
| `status` | Yes | `sent` / `failed` / `delivered` |
| `error` | No | e.g. `RESULT_ERROR_NO_SERVICE`, `SecurityException: Permission denied` |

- `sent` — handed over to the carrier (`SmsManager` RESULT_OK).
- `failed` — carrier error, no network, empty balance, or timeout.
- `delivered` — carrier delivery receipt (best effort).

Response `200 OK`: `{ "ok": true }`. Errors: `400` invalid body, `401` bad token, `404` unknown ID.

```bash
curl -X POST "https://api.yourdomain.com/api/v1/sms/status" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "id": "c7a10f82-938a-4d24-81eb-987814b7e120", "status": "sent" }'
```

### 4.3 POST /api/v1/sms/send

Called by your backend server to queue a message. (The phone never calls this.)

```json
{
  "to": "919876543210",
  "message": "Dear Parent, your child was marked present today at 08:30 AM.",
  "templateId": "DLT_ATTENDANCE_01",
  "priority": "normal"
}
```

| Field | Required | Default | Validation |
|---|---|---|---|
| `to` | Yes | — | `^[0-9]{10,15}$` |
| `message` | Yes | — | 1–1000 chars (GSM-7 / Unicode auto-handled) |
| `templateId` | No | `null` | DLT template ID (India telecom compliance) |
| `priority` | No | `"normal"` | `"normal"` or `"high"` |

Response `201 Created`:

```json
{
  "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
  "status": "pending",
  "to": "919876543210",
  "created_at": "2026-09-19T10:00:00.000Z"
}
```

### 4.4 GET /api/v1/sms/logs

For admin panels / audit dashboards.

- **Query:** `status` (`pending|sent|failed|delivered`), `page` (default `1`), `limit` (default `20`, max `100`)

Response `200 OK`:

```json
{
  "data": [
    {
      "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
      "to": "919876543210",
      "message": "Your OTP verification code is 482913. Valid for 10 mins.",
      "status": "sent",
      "error": null,
      "created_at": "2026-09-19T10:00:00.000Z",
      "updated_at": "2026-09-19T10:00:15.000Z"
    }
  ],
  "total": 142,
  "page": 1,
  "limit": 20
}
```

---

## 5. Database Schema — `sms_queue`

Production PostgreSQL / Supabase definition:

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS sms_queue (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient     VARCHAR(15) NOT NULL CHECK (recipient ~ '^[0-9]{10,15}$'),
  message       TEXT NOT NULL CHECK (char_length(message) BETWEEN 1 AND 1000),
  template_id   VARCHAR(64),
  status        VARCHAR(16) NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'sent', 'failed', 'delivered')),
  error         TEXT,
  priority      VARCHAR(8) NOT NULL DEFAULT 'normal'
                CHECK (priority IN ('normal', 'high')),
  meta          JSONB,
  retry_count   SMALLINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  sent_at       TIMESTAMPTZ,
  delivered_at  TIMESTAMPTZ
);

-- Fast polling: GET /pending stays <1ms even with millions of history rows
CREATE INDEX IF NOT EXISTS idx_sms_queue_pending_poll
  ON sms_queue (created_at ASC)
  WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_sms_queue_created_at
  ON sms_queue (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_sms_queue_recipient
  ON sms_queue (recipient);

CREATE OR REPLACE FUNCTION update_sms_queue_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  IF NEW.status = 'sent' AND OLD.status != 'sent' THEN
    NEW.sent_at = NOW();
  ELSIF NEW.status = 'delivered' AND OLD.status != 'delivered' THEN
    NEW.delivered_at = NOW();
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sms_queue_updated ON sms_queue;
CREATE TRIGGER trg_sms_queue_updated
  BEFORE UPDATE ON sms_queue
  FOR EACH ROW
  EXECUTE FUNCTION update_sms_queue_timestamp();
```

---

## 6. Build

Requirements: JDK 17, Android SDK (API 35), Gradle wrapper included.

```bash
./build-debug-apk.sh    # debug APK  → Sandesh-debug.apk (fast, no minify)
./build-apk.sh          # release APK → Sandesh-release.apk (minified)
```

The release build is currently signed with the debug keystore (see `signingConfigs` in `app/build.gradle.kts`) — fine for sideloading and testing, **not for Play Store**. Use a real keystore before any store release.

---

## 7. Permissions

| Permission | Why |
|---|---|
| `SEND_SMS` | Send SMS via SIM (core function) |
| `POST_NOTIFICATIONS` | Foreground-service status notification (Android 13+) |
| `READ_PHONE_STATE` | List SIM cards for manual SIM selection |
| `RECEIVE_BOOT_COMPLETED` | Restore polling after reboot (only if service was ON) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prompt to exempt the app from Doze |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `WAKE_LOCK` / `FOREGROUND_SERVICE*` | Networking + foreground service |

---

## 8. Production Tips & Troubleshooting

1. **Dedicated phone:** keep it on continuous power with Wi-Fi / 4G.
2. **SIM plan:** standard plans allow ~100 SMS/day; use bulk/enterprise packs for volume.
3. **Dual SIM:** set the SMS-plan SIM as the Android default SMS SIM, or pick it explicitly in Settings → SIM FOR SENDING.
4. **App closes instantly on launch:** update to the latest build — older encrypted storage is auto-recovered on first start (gateways must be re-added once).
5. **`Activity ... does not exist` in Android Studio:** Run **File → Sync Project with Gradle Files**, then **Build → Clean Project**, then Run again.
6. **`Device is already activating`:** stop the previous Run (red ■ button) and Run again.
7. **No public IP needed on the phone:** it only makes outbound HTTPS calls, so CGNAT / mobile data / home Wi-Fi all work without port forwarding or VPN.

---

## 9. Project Structure

```
android-sms-gateway/
├── app/src/main/java/com/smsgateway/
│   ├── MainActivity.kt          # drawer + tabs, permissions, network banner
│   ├── HomeFragment.kt          # stats + service start/stop
│   ├── GatewaysFragment.kt      # gateway CRUD + connection test
│   ├── LogsFragment.kt          # in-memory log viewer (last 50)
│   ├── SettingsFragment.kt      # service switch, SIM picker, battery, theme
│   ├── SmsForegroundService.kt  # 15s polling loop, persistent notification
│   ├── SmsPollWorker.kt         # WorkManager 15-min fallback
│   ├── SmsSender.kt             # SmsManager + sent/delivered receivers
│   ├── ApiClient.kt             # per-gateway Retrofit (Moshi, Bearer auth)
│   ├── BackendConfig.kt         # gateway model + URL normalization
│   ├── Prefs.kt                 # encrypted prefs (gateways, SIM, flags)
│   └── BootReceiver.kt          # reboot restore (respects ON/OFF state)
├── app/src/main/res/            # monochrome theme, Italiana + DM Sans fonts
├── build-apk.sh                 # release APK → Sandesh-release.apk
├── build-debug-apk.sh           # debug APK   → Sandesh-debug.apk
└── smsgateway-flow.drawio       # working-flow diagram (draw.io)
```
