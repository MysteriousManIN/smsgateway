# smsgateway — Complete Documentation & API Reference

Lightweight Android SMS Gateway for automated SMS dispatch via mobile SIM. Phone polls backend every 15s, sends SMS via device SIM using `SmsManager`, and reports delivery status back to backend. Supports multi-backend setup — one phone can serve multiple ERPs or applications simultaneously.

---

## 1. System Architecture & Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Backend / ERP System                          │
│                                                                        │
│   ERP Service ───► POST /api/v1/sms/send ───► [ sms_queue (pending) ]  │
│                                                          ▲             │
│   Audit UI    ◄─── GET /api/v1/sms/logs  ────────────────┤             │
└──────────────────────────────────────────────────────────┼─────────────┘
                                                           │ HTTPS
                                                           │ (Poll every 15s)
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

### Flow Summary:
1. **Enqueue:** ERP / Backend calls `POST /api/v1/sms/send` to insert message with status `pending` into `sms_queue`.
2. **Poll:** Android phone foreground service polls `GET /api/v1/sms/pending?limit=10` every 15s for each enabled gateway.
3. **Dispatch:** Phone sends message via SIM card using `SmsManager` (auto-handles multipart if message > 160 GSM chars).
4. **Status Update:** Phone posts delivery result (`sent`, `failed`, or `delivered`) back to backend via `POST /api/v1/sms/status`.
5. **Audit:** ERP queries `GET /api/v1/sms/logs` to view delivery logs and history.

---

## 2. Android App Setup & Usage Guide (Kaise use kare)

### Gateway Add / Edit / Test Flow

Har ERP / Backend ke liye Gateways tab me ek gateway card configure kar sakte ho.

| Field | Example | Description |
|---|---|---|
| **Name** | `School-ERP` / `Production-Gateway` | Friendly label for backend |
| **Base URL** | `https://api.yourdomain.com/api/v1/sms/` | Backend endpoint base URL (`https://` required, trailing `/` optional) |
| **Token** | `SMS_GATEWAY_TOKEN_SECRET` | Backend shared secret Bearer token |

#### Step-by-Step Setup:

1. **Add Gateway:** Gateways tab pe jao → `+` button tap karo → Name, Base URL (`https://...`), aur Token enter karo → **Save**.
2. **Toggle ON:** Gateway card pe toggle switch **ON** karo → polling enable ho jayegi us backend ke liye.
3. **Test Gateway:** Tap **Test** button → App `GET /api/v1/sms/pending?limit=10` call karegi with `Authorization: Bearer <TOKEN>` header.
   - Success: `✓ pending: N` toast show hoga.
   - Error: `401 token invalid`, `429 rate limit`, ya network error display hoga.
4. **Grant Permissions:**
   - **SEND_SMS**: Required for sending SMS via SIM.
   - **POST_NOTIFICATIONS** (Android 13+): Required for foreground service persistent notification.
5. **Start Service:**
   - Home tab pe jao → **Start Service** tap karo.
   - Notification `smsgateway active` show hogi → Foreground service starts polling every 15s per enabled gateway.
6. **Disable Battery Optimization (Crucial):**
   - Prompt aane pe Battery Optimization disable karo (Settings → Battery → Unrestricted / No restrictions).
   - Xiaomi / Oppo / Vivo / Realme devices me **Auto-start** allow karo aur background restrictions hatao taaki Android OS background service kill na kare.

#### Gateway Management Actions:
- **Add:** Floating `+` button se naya gateway create karein.
- **Edit:** Pencil icon tap karke URL ya token update karein.
- **Delete:** Trash icon tap karke confirmation ke baad gateway remove karein.
- **Toggle (ON/OFF):** Har backend independent hai — agar ek backend down ho to dusre backends ki polling affect nahi hoti.

---

## 3. Authentication & Security Requirements

All requests between Android App and Backend must adhere to the following standards:

### 1. Transport Security
- **Strict HTTPS Required:** All endpoints must be served over `https://`. Insecure `http://` URLs are rejected by the Android client for security.

### 2. Request Headers Format
Every request must include the Bearer authentication token and JSON content type:

```http
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

### 3. Rate Limiting & Recommendations
- Recommended backend rate limit: `60 req/min` per token.
- On rate limit breach, return HTTP `429 Too Many Requests` with `{ "error": "Too Many Requests", "retryAfter": 60 }`.
- Phone number validation: E.164 digits without `+` sign (`10-15` digits, regex: `^[0-9]{10,15}$`, e.g., `919876543210`).

---

## 4. Backend API Documentation

Backend ko 4 REST endpoints implement karne hote hain:

1. `GET /api/v1/sms/pending` — Phone polls pending messages
2. `POST /api/v1/sms/status` — Phone reports delivery status
3. `POST /api/v1/sms/send` — ERP / Server enqueues message
4. `GET /api/v1/sms/logs` — ERP queries SMS logs & audit history

---

### 4.1 GET /api/v1/sms/pending

Phone har 15 second me ye endpoint call karta hai to fetch pending SMS messages to send.

- **Method:** `GET`
- **Path:** `/api/v1/sms/pending`
- **Caller:** Android Phone (Foreground Service)

#### Headers:
```http
Authorization: Bearer <SMS_GATEWAY_TOKEN>
```

#### Query Parameters:
| Parameter | Type | Default | Description |
|---|---|---|---|
| `limit` | integer | `10` | Maximum number of pending messages to return (max `50`). |

#### Response (200 OK):
```json
{
  "messages": [
    {
      "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
      "to": "919876543210",
      "message": "Your OTP verification code is 482913. Valid for 10 mins.",
      "templateId": "DLT-OTP-01",
      "created_at": "2026-09-19T10:00:00.000Z"
    },
    {
      "id": "d9b21e93-849b-5e35-92fc-098925c8f231",
      "to": "919876543211",
      "message": "Dear Parent, school fees of Rs. 4,500 is due on 25th Sept.",
      "templateId": null,
      "created_at": "2026-09-19T10:00:05.000Z"
    }
  ]
}
```

*When no messages are pending:*
```json
{
  "messages": []
}
```

#### Response Fields:
| Field | Type | Description |
|---|---|---|
| `messages` | array | List of pending SMS objects |
| `messages[].id` | string | Unique message ID (UUID or String) |
| `messages[].to` | string | Destination phone number (10–15 digits) |
| `messages[].message` | string | SMS body text |
| `messages[].templateId` | string \| null | Optional DLT template ID |
| `messages[].created_at` | string \| null | ISO timestamp of creation |

#### cURL Example:
```bash
curl -X GET "https://api.yourdomain.com/api/v1/sms/pending?limit=10" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN"
```

---

### 4.2 POST /api/v1/sms/status

Jab phone SMS send kar leta hai ya delivery fail hoti hai, tab phone status report karne ke liye ye endpoint call karta hai.

- **Method:** `POST`
- **Path:** `/api/v1/sms/status`
- **Caller:** Android Phone (SmsSender callback / receivers)

#### Headers:
```http
Authorization: Bearer <SMS_GATEWAY_TOKEN>
Content-Type: application/json
```

#### Request Body Schema:
```json
{
  "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
  "status": "sent",
  "error": null
}
```

#### Request Body Fields:
| Field | Type | Required | Description / Values |
|---|---|---|---|
| `id` | string | Yes | Message ID reported |
| `status` | string | Yes | One of: `sent`, `failed`, `delivered` |
| `error` | string \| null | No | Error details if status is `failed` (e.g. `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`, `SecurityException: Permission denied`) |

#### Status Definitions:
- `sent`: SMS handover to cellular carrier successful (`SmsManager` RESULT_OK).
- `failed`: SMS send failed (carrier error, SIM out of balance, no network, or timeout).
- `delivered`: Delivery confirmation received from telecom provider (best-effort carrier receipt).

#### Response (200 OK):
```json
{
  "ok": true
}
```

#### Error Responses:
- `400 Bad Request`: Invalid status value or missing ID (`{ "error": "Invalid status or ID" }`).
- `401 Unauthorized`: Token missing or invalid.
- `404 Not Found`: Message ID not found in database.

#### cURL Examples:

*Mark as Sent:*
```bash
curl -X POST "https://api.yourdomain.com/api/v1/sms/status" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
    "status": "sent"
  }'
```

*Mark as Failed:*
```bash
curl -X POST "https://api.yourdomain.com/api/v1/sms/status" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
    "status": "failed",
    "error": "RESULT_ERROR_NO_SERVICE"
  }'
```

---

### 4.3 POST /api/v1/sms/send

ERP ya application server naya SMS queue karne ke liye is endpoint ko call karta hai. *(Note: Phone is endpoint ko call nahi karta, ye aapka server call karta hai).*

- **Method:** `POST`
- **Path:** `/api/v1/sms/send`
- **Caller:** ERP Server / Application Backend

#### Headers:
```http
Authorization: Bearer <SMS_GATEWAY_TOKEN>
Content-Type: application/json
```

#### Request Body Schema:
```json
{
  "to": "919876543210",
  "message": "Dear Parent, your child was marked present today at 08:30 AM.",
  "templateId": "DLT_ATTENDANCE_01",
  "priority": "normal"
}
```

#### Request Body Fields:
| Field | Type | Required | Default | Validation & Notes |
|---|---|---|---|---|
| `to` | string | Yes | — | Recipient mobile number without `+` (10–15 numeric digits: `^[0-9]{10,15}$`). |
| `message` | string | Yes | — | SMS text body (1–1000 characters). GSM-7 and Unicode / UCS-2 auto-handled. |
| `templateId` | string | No | `null` | Optional DLT Template ID (for Indian telecom compliance). |
| `priority` | string | No | `"normal"` | Priority queue level: `"normal"` or `"high"`. |

#### Response (201 Created / 200 OK):
```json
{
  "id": "c7a10f82-938a-4d24-81eb-987814b7e120",
  "status": "pending",
  "to": "919876543210",
  "created_at": "2026-09-19T10:00:00.000Z"
}
```

#### cURL Example:
```bash
curl -X POST "https://api.yourdomain.com/api/v1/sms/send" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "to": "919876543210",
    "message": "Dear Student, your exam schedule is published on portal.",
    "templateId": "DLT_NOTIF_02",
    "priority": "normal"
  }'
```

---

### 4.4 GET /api/v1/sms/logs

ERP admin panel ya audit system ke liye delivery logs aur history fetch karne ka endpoint.

- **Method:** `GET`
- **Path:** `/api/v1/sms/logs`
- **Caller:** ERP Admin Panel / Dashboard

#### Headers:
```http
Authorization: Bearer <SMS_GATEWAY_TOKEN>
```

#### Query Parameters:
| Parameter | Type | Default | Description |
|---|---|---|---|
| `status` | string | (all) | Filter by status: `pending`, `sent`, `failed`, `delivered` |
| `page` | integer | `1` | Page number (1-based index) |
| `limit` | integer | `20` | Items per page (default 20, max 100) |

#### Response (200 OK):
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
    },
    {
      "id": "e8f32a04-123c-4d56-87ef-789012abcdef",
      "to": "919876543212",
      "message": "Fee payment reminder.",
      "status": "failed",
      "error": "RESULT_ERROR_NO_SERVICE",
      "created_at": "2026-09-19T09:45:00.000Z",
      "updated_at": "2026-09-19T09:45:20.000Z"
    }
  ],
  "total": 142,
  "page": 1,
  "limit": 20
}
```

#### cURL Example:
```bash
curl -X GET "https://api.yourdomain.com/api/v1/sms/logs?status=sent&page=1&limit=20" \
  -H "Authorization: Bearer YOUR_SMS_GATEWAY_TOKEN"
```

---

## 5. Database Schema — `sms_queue` Table

Production PostgreSQL / Supabase table definition for queuing and logging SMS:

```sql
-- Enable UUID extension if not present
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Create sms_queue Table
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

-- 2. Indexes for fast polling & querying
-- Partial index: GET /pending executes in <1ms even with millions of past rows
CREATE INDEX IF NOT EXISTS idx_sms_queue_pending_poll
  ON sms_queue (created_at ASC)
  WHERE status = 'pending';

-- Index for logs sorting & pagination
CREATE INDEX IF NOT EXISTS idx_sms_queue_created_at
  ON sms_queue (created_at DESC);

-- Index for searching logs by recipient number
CREATE INDEX IF NOT EXISTS idx_sms_queue_recipient
  ON sms_queue (recipient);

-- 3. Trigger to auto-update updated_at timestamp
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

### Table Column Details:

| Column | Type | Description |
|---|---|---|
| `id` | `UUID` | Unique identifier generated for each SMS |
| `recipient` | `VARCHAR(15)` | Mobile number (10–15 digits without `+`) |
| `message` | `TEXT` | Message text content (1–1000 characters) |
| `template_id` | `VARCHAR(64)` | Optional DLT registration template ID |
| `status` | `VARCHAR(16)` | `pending` → `sent` / `failed` → `delivered` |
| `error` | `TEXT` | Failure error message if send fails |
| `priority` | `VARCHAR(8)` | `normal` (standard) or `high` (for instant OTPs) |
| `meta` | `JSONB` | Additional JSON metadata (e.g. `{ "studentId": "...", "type": "otp" }`) |
| `retry_count` | `SMALLINT` | Number of times retried |
| `created_at` | `TIMESTAMPTZ` | Timestamp when SMS was enqueued |
| `updated_at` | `TIMESTAMPTZ` | Timestamp of latest state change |
| `sent_at` | `TIMESTAMPTZ` | Timestamp when marked as `sent` |
| `delivered_at` | `TIMESTAMPTZ` | Timestamp when marked as `delivered` |

---

## 6. Environment Configuration

Backend `.env` variables configuration:

```env
# Shared secret for Android Gateway & ERP
SMS_GATEWAY_TOKEN=openssl_generated_random_token_32_bytes_hex

# Polling Interval & Limits
SMS_POLL_INTERVAL_MS=15000
SMS_GATEWAY_RATE_LIMIT_MAX=60
```

---

## 7. Production Tips & Troubleshooting

1. **Keep Phone Charged & Connected:** Use a dedicated Android phone connected to continuous power and Wi-Fi / 4G.
2. **SIM Card Plan:** Use an unlimited SMS mobile plan pack (~100 SMS/day on standard plans, or bulk enterprise SIM packs).
3. **Dual-SIM:** Ensure the active SIM with the SMS plan is set as the default SMS SIM under Android Settings → Network & Internet → SIMs → SMS Preference.
4. **Idempotency:** When handling `POST /status`, ignore duplicate updates for already finalized records.
5. **No Public IP needed on phone:** Since the phone acts as an HTTP client polling the server over HTTPS, it works seamlessly behind CGNAT, mobile data, or home Wi-Fi without port forwarding or VPNs.
