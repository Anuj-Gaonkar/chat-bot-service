# WhatsApp demo runbook

Step-by-step instructions for running the Layer 0 WhatsApp adapter (branch
`whatsapp-integration`) end-to-end against real WhatsApp. See
`WHATSAPP_LAYER0_INTEGRATION_PLAN.md` for the design; this doc is just the "how to actually run
it" checklist.

## 1. Prerequisites

- [ ] **Docker Desktop running** — the backend needs its dev Postgres up.
- [ ] **`ngrok` installed** (or an equivalent HTTPS tunnel tool).
- [ ] **4 Meta credential values in hand**, from the App Dashboard's WhatsApp → API Setup page:
  - Phone Number ID (numeric, e.g. `1192323157307824`)
  - Access token (temporary 24h token is fine for a demo)
  - App Secret (App Dashboard → Settings → Basic)
  - A verify token you make up yourself (any string, e.g. `demo-verify-token`) — just needs to
    match what you type into the Meta webhook config in step 5
- [ ] **Your OTP-verified test phone**, with WhatsApp installed — this is the device you'll
      actually chat from.

## 2. Start dev Postgres

```powershell
cd C:\Study\HDFC\Chat-Bot-V2\chat-bot-service
docker compose up -d
```

> Gotcha: if a backend JVM from an earlier session is still running, kill it first
> (`spring-boot:run` doesn't hot-reload code changes into an already-running process).

## 3. Set the 4 env vars, then start the backend

In the **same shell** you'll run the backend from:

```powershell
$env:WHATSAPP_PHONE_NUMBER_ID = "<your phone number id>"
$env:WHATSAPP_ACCESS_TOKEN    = "<your access token>"
$env:WHATSAPP_APP_SECRET      = "<your app secret>"
$env:WHATSAPP_VERIFY_TOKEN    = "demo-verify-token"

./mvnw spring-boot:run
```

Wait for `Started ChatBotServiceApplication` before continuing.

## 4. Start the tunnel

In a separate terminal:

```powershell
ngrok http 8080
```

Copy the `https://<random-id>.ngrok-free.app` URL from ngrok's output — you'll need it in the
next step. (Free-tier ngrok URLs change every time you restart it — if you restart ngrok mid-demo,
you'll need to repeat step 5 with the new URL.)

## 5. Wire the webhook in the Meta App Dashboard

App Dashboard → WhatsApp → Configuration → Webhook:

- **Callback URL**: `https://<your-ngrok-id>.ngrok-free.app/webhooks/whatsapp`
- **Verify Token**: the exact same string as `WHATSAPP_VERIFY_TOKEN` above
- Click **Verify and Save** — this immediately fires `GET /webhooks/whatsapp` against your
  tunnel, so the backend and ngrok must already be running (steps 3–4).
- Once saved, subscribe to the **`messages`** webhook field.

## 6. Run the actual test

From your verified phone, message the bot's test WhatsApp number (any text starts a new session,
e.g. "Hi"). Walk the churn/callback journey:

1. Bot sends the 5-option menu as a WhatsApp **list** message.
2. Pick **"I no longer actively use this account."**
3. Pick **"Service concern."**
4. Pick **"Request a callback."**
5. Reply with free text for the callback reason.
6. Confirm the bot sends a closing confirmation message.

Expect real WhatsApp buttons for short YES/NO-style questions, and a real WhatsApp list (tap to
expand, row per option) for the longer menu options — not plain numbered text.

### Optional follow-up check

Confirm the session was recorded exactly like any other channel's:

- `GET /api/customers/{waId}/frame` (where `waId` is your phone number in international format,
  no `+`, e.g. `919998887770`), or
- The `chat-ui` `/journey` page, phone number lookup.

Both should show the full path summary, conclusion code, and entry reason code, same as a
`chat-ui`-driven session.

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `401` from Graph API (in backend logs) | Temporary access token expired (24h) | Regenerate from API Setup, restart backend with the new `WHATSAPP_ACCESS_TOKEN` |
| `400`, error code `131030` | Recipient phone not in the verified test-number list | Add/verify the number in API Setup → "To" field |
| Nothing arrives on your phone at all | ngrok tunnel dropped or URL changed | Check ngrok is still running; if it restarted, re-paste the new URL into the Meta webhook config (step 5) |
| `403` on the `GET` verify handshake | Verify Token mismatch | Confirm `WHATSAPP_VERIFY_TOKEN` exactly matches what's typed into the Meta dashboard |
| `403` on inbound `POST` (webhook rejected) | Signature mismatch | Confirm `WHATSAPP_APP_SECRET` is set correctly and matches the App Dashboard's App Secret |
| Backend logs `whatsapp.app-secret is not set - skipping...` | `WHATSAPP_APP_SECRET` env var wasn't set before startup | Fine for a quick local test, but set it before anything internet-facing relies on this |

## 8. Where to look

All send/receive activity is logged from the backend console:

- `WhatsAppClient` — `log.info` on every successful send, `log.error` (with the Graph API's error
  body) on a failed send.
- `WhatsAppWebhookController` / `SignatureVerifier` — `log.warn` on a rejected webhook call
  (bad signature or verify token), `log.info` for an inbound message of a type the adapter doesn't
  handle (e.g. images, stickers — text and interactive button/list replies are the only inbound
  types currently supported).
