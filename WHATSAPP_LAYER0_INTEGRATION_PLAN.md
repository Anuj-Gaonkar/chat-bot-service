# WhatsApp Layer 0 — real WhatsApp channel adapter

## Context

`WHATSAPP_FLOWS_INTEGRATION.md` sketched a two-layer integration: Layer 0 (a plain WhatsApp
channel adapter, no Flows) as a prerequisite, and Layer 1 (WhatsApp Flows for specific nodes) on
top of it. This doc is the concrete implementation plan for **Layer 0 only** — WhatsApp Flows
remain paused, not needed for this. The question that prompted narrowing scope: *"Do we even need
WhatsApp Flows, or can we achieve our goal via plain WhatsApp chat?"* — answer: plain chat is
enough. Everything the current workflow graph needs (menus, yes/no, free text) maps directly onto
WhatsApp's own message types, so this is the whole integration, not a stepping stone.

Today `chat-bot-service` has no WhatsApp channel adapter — `ConversationController` is explicitly
documented as channel-agnostic ("A WhatsApp/web/IVR adapter would be a client of these same
endpoints, not a fork of them"), and the only client that exists is `chat-ui`'s plain-JSON test
harness. This plan adds the first real messaging channel.

## Which WhatsApp Cloud API surfaces we need

Only two — no Flows API, no Business Management API, no Templates API required for the ongoing
conversation itself:

| API | Direction | Purpose |
|---|---|---|
| **Messages API** — `POST /{phone-number-id}/messages` | Outbound (bot → customer) | Every bot reply: plain text, or interactive (buttons/list) |
| **Webhooks** — Meta calls `GET`/`POST` on our server | Inbound (customer → bot) | Every customer message / button-tap / list-selection |

**24-hour messaging window**: freeform messages (text/interactive) are only allowed within 24h of
the customer's last inbound message; messaging someone who hasn't texted first requires a
pre-approved template (like the `hello_world` template used to smoke-test credentials below). Not
a concern for this demo — the customer always initiates by texting the bot number, which opens the
window before the bot ever needs to reply.

## Setup already completed (Meta side)

- Meta Developer account + a Business-type App with the WhatsApp product added.
- Test WhatsApp Business Account + Meta-provided test phone number.
- Personal phone number OTP-verified as a test recipient (up to 5 allowed in Development mode).
- Credentials in hand: **Phone Number ID**, a temporary **access token**, and the **App Secret**.
- Confirmed working end-to-end via a manual `hello_world` template send (Cloud API `POST
  /{phone-number-id}/messages`, `type: template`) — arrived on the verified phone.

## Setup still needed (local/tooling side)

1. `ngrok http 8080` (or equivalent tunnel) — Meta needs a public HTTPS URL to reach the local
   backend's webhook endpoint.
2. Four env vars before starting the backend: `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN`,
   `WHATSAPP_APP_SECRET` (all three from the Meta setup above), and `WHATSAPP_VERIFY_TOKEN` (any
   string we choose, must match what's entered in the Meta dashboard's webhook config).
3. In the Meta App Dashboard → WhatsApp → Configuration → Webhook: paste the ngrok URL
   (`https://<id>.ngrok-free.app/webhooks/whatsapp`) as the Callback URL, the same string as the
   Verify Token, then subscribe to the `messages` field. This triggers the one-time `GET
   /webhooks/whatsapp` handshake.

## Code plan — new `whatsapp` package in `chat-bot-service`

```
                     ┌─────────────────────────────────────────────┐
                     │        Meta WhatsApp Cloud API (Graph)        │
                     └───────────────┬───────────────┬─────────────┘
                    inbound webhook   │               │  outbound send
                                      ▼               │  POST /{phone-number-id}/messages
                     https://<ngrok>.../webhooks/whatsapp
                                      │
┌─────────────────────────────────── │ ─────────────────────────────────┐
│  chat-bot-service  (new package: whatsapp)                            │
│                                     ▼                                 │
│   WhatsAppWebhookController                              WhatsAppClient
│   ├─ GET  → hub.challenge handshake                       (RestClient, │
│   ├─ POST → verify X-Hub-Signature-256                     Bearer token)
│   │         (HMAC-SHA256 of raw body, keyed                    ▲       │
│   │          on Meta App Secret)                               │       │
│   │         parse messages[] / interactive.*_reply.id          │       │
│   │                                                             │       │
│   │  lookup: findFirstByCustomerIdOrderByStartedAtDesc(from)    │       │
│   │    ACTIVE session exists → workflowEngine.reply(id, rawInput)      │
│   │    else                   → workflowEngine.start(ENTRY_CODE, from)│
│   │                                     │                              │
│   │                                     ▼                              │
│   │                        ConversationResponse.from(result)  ────────┤
│   │                          (existing, channel-agnostic DTO)          │
│   │                                     │                              │
│   │                                     ▼                              │
│   │                        WhatsAppMessageMapper.toPayload(to, resp)  │
│   │                          - 0 options        → text message         │
│   │                          - ≤3 opts, short   → interactive buttons  │
│   │                          - else / long text → interactive list     │
│   └─────────────────────────────────────────────────────────►─────────┘
│                                                                         │
│   WorkflowEngine / WorkflowSessionRepository  ← UNCHANGED, same beans  │
│   used by ConversationController today                                 │
└─────────────────────────────────────────────────────────────────────┘
```

### New files

- **`whatsapp/WhatsAppProperties.java`** — `@ConfigurationProperties(prefix = "whatsapp")` record:
  `apiVersion`, `phoneNumberId`, `accessToken`, `verifyToken`, `appSecret`. All env-var-backed via
  `application.yaml` (`${WHATSAPP_ACCESS_TOKEN:}` etc.) — nothing hardcoded, nothing committed.
- **`whatsapp/WhatsAppClient.java`** — thin wrapper over `POST /{phone-number-id}/messages` using
  Spring's `RestClient`, Bearer-authenticated.
- **`whatsapp/WhatsAppMessageMapper.java`** — turns the existing `ConversationResponse` (the same
  DTO `ConversationController` and `chat-ui` already use) into a Graph API JSON payload:
  - no options → plain text message.
  - options present, ≤3 of them, all short enough → interactive **buttons** (WhatsApp caps button
    titles at 20 chars — mainly hits YES/NO turns).
  - otherwise → interactive **list** (row title ≤24 chars, description ≤72 — fits the longer
    OPTION labels the churn journey actually uses, e.g. "I no longer actively use this account").
  - Button/list-row `id` is set to exactly the string `WorkflowEngine.matchReply` already expects
    back as `rawInput` (`"YES"`/`"NO"` or a plain option index like `"3"`) — zero translation
    needed on the inbound side.
- **`whatsapp/WhatsAppWebhookController.java`** — `GET /webhooks/whatsapp` (verify handshake),
  `POST /webhooks/whatsapp` (inbound). Verifies `X-Hub-Signature-256` before parsing anything,
  extracts `wa_id` (→ `customerId`) and the reply id/free text (→ `rawInput`), looks up the most
  recent session via `WorkflowSessionRepository.findFirstByCustomerIdOrderByStartedAtDesc` — if
  `ACTIVE`, calls `workflowEngine.reply(...)`, otherwise `workflowEngine.start(ENTRY_CODE, ...)` —
  then maps and sends the response via `WhatsAppClient`. Calls `WorkflowEngine` directly as an
  in-process bean, the same one `ConversationController` uses — not an HTTP call to its own API.
- **`whatsapp/SignatureVerifier.java`** — HMAC-SHA256 of the raw webhook body, keyed on the Meta
  App Secret, constant-time compared against `X-Hub-Signature-256`.

### What does NOT change

`WorkflowEngine`, the DB-defined conversation graph, `ConversationController`, and every existing
endpoint stay exactly as they are. This is a pure addition — the adapter is just one more client of
the same engine, per the architecture note already on `ConversationController`.

## Testing plan

1. Backend unit tests: `WhatsAppMessageMapper` (one case per payload shape — text / buttons /
   list, including the long-label → list fallback), `SignatureVerifier` (valid/invalid/missing
   signature).
2. Local end-to-end: start backend with the 4 env vars set, `ngrok http 8080`, wire the webhook in
   the Meta dashboard, then text the bot number from the verified phone and walk the churn/callback
   journey — confirm real WhatsApp buttons/lists render and the conversation completes exactly like
   it does in `chat-ui` today.
3. Confirm `GET /api/customers/{customerId}/frame` (and the `/journey` page) correctly shows the
   WhatsApp-driven session — `customerId` is the `wa_id`, so this should work with zero changes on
   that side.
