# WhatsApp Flows — explainer + integration plan

## Context

Today `chat-bot-service` has **no WhatsApp channel adapter at all** — `ConversationController` is
explicitly documented as channel-agnostic ("A WhatsApp/web/IVR adapter would be a client of these
same endpoints, not a fork of them"), and the only client that exists is `chat-ui`'s plain-JSON
test harness. This doc explains WhatsApp Flows in detail, then lays out how to wire the existing
engine up to real WhatsApp, with Flows as a specific enhancement layered on top of a basic
text/button/list adapter (which has to exist first regardless of Flows).

## Part 1 — What WhatsApp Flows is

WhatsApp Flows is a Meta feature (WhatsApp Business Platform / Cloud API) for building
**structured, multi-screen native forms** inside a WhatsApp chat — richer than a plain text
message or a row of quick-reply buttons. Real widgets render inside the chat thread itself:
`TextInput`, `TextArea`, `Dropdown`, `RadioButtonsGroup`, `CheckboxGroup`, `DatePicker`, `OptIn`,
`Image`, footer buttons, etc. Used for things like appointment booking, lead-gen intake, KYC-style
data collection, surveys — anywhere a chain of button taps would be clunkier than one form.

**Flow JSON** — a declarative spec (Meta's own schema, currently `"version": "5.0"` or later)
describing:
- an array of `screens`, each with an `id`, `title`, a `terminal` flag, and a `layout` of
  components bound to named fields in a per-flow data model,
- navigation: a footer button's `on-click-action` is either
  `{ "name": "navigate", "next": { "type": "screen", "name": "NEXT_SCREEN" } }` or
  `{ "name": "complete" }` to finish and submit.

**Two data-exchange modes** (this is the key architectural fork):

| Mode | How it works | When to use |
|---|---|---|
| **Static** | All screens and branching logic live in the Flow JSON, evaluated entirely client-side by the WhatsApp app. Your server is not called until the flow completes. | Simple/linear or lightly-branching forms with fixed choices — the common case. |
| **Dynamic (endpoint-driven)** | Every screen transition sends an encrypted POST to a `data_endpoint_uri` you host; your server decides the next screen's content and can branch on live data (real-time availability, account state). | A screen's options genuinely depend on live server state — e.g. real callback slot availability. |

**Flow lifecycle**: authored/edited in WhatsApp Manager's Flow Builder, or programmatically via
the Graph API (create draft → validate → publish; flows are versioned).

**Sending a Flow**: a normal outbound Cloud API message, `POST /{phone-number-id}/messages`,
`interactive.type: "flow"`, with `flow_id`, `flow_cta` (button label), and
`flow_action: "navigate"` (+ initial screen) or `"data_exchange"`.

**Receiving the result**: when the user submits the terminal screen, WhatsApp delivers it as a
**normal inbound webhook message** — `interactive.type: "nfm_reply"` with a `response_json` string
containing every field the user filled in. This arrives on the same webhook as any other inbound
message; no separate callback channel.

**Encryption (dynamic mode only)**: the business generates an RSA keypair and uploads the public
key via Graph API (`POST /{phone-number-id}/whatsapp_business_encryption`). Every data-exchange
request Meta sends is AES-256-GCM-encrypted with a per-request key that is itself RSA-OAEP(SHA-256)
-wrapped with your public key; your endpoint decrypts, computes the next screen, re-encrypts the
response with the same AES key (IV bit-flipped per Meta's spec), and returns it base64-encoded.
Meta also pings the endpoint periodically (`action: "ping"`) — an unanswered ping can get the flow
auto-paused. Meta publishes reference implementations in Node/PHP/Python; no official Java one, but
the primitives (RSA-OAEP unwrap + AES-256-GCM) are standard `javax.crypto`.

## Part 2 — Integration architecture for this codebase

One structural fact makes this easier: `customerId` in `ConversationService` is already a phone
number string (see `NEXTJS_CHAT_UI_BRIEF.md`), which is exactly WhatsApp's `wa_id` — session
keying already lines up with zero changes needed there.

### Layer 0 — WhatsApp channel adapter (prerequisite, ships before any Flows work)

New package `in.bank.hdfc.chat_bot_service.whatsapp`:

- **`WhatsAppWebhookController`**
  - `GET /webhooks/whatsapp` — verification handshake (`hub.mode`/`hub.verify_token`/`hub.challenge`).
  - `POST /webhooks/whatsapp` — inbound messages; verifies `X-Hub-Signature-256` against the app
    secret before parsing anything.
- **`WhatsAppClient`** — wraps `POST /{phone-number-id}/messages` (Graph API) for plain text,
  interactive-button (≤3 options), and interactive-list (>3 options) message types.
- **`WhatsAppMessageMapper`** — translates the existing `ConversationResponse` DTO
  (already channel-agnostic, unchanged) into a Graph API payload:
  - `MESSAGE` / `END` / `ACTION` steps → plain text.
  - `QUESTION` with options → interactive button (≤3) or list (>3) — the generic
    `OPTION`+`option_index` model already in place (no more `OPTION_1/2/3` ceiling) maps directly
    onto WhatsApp's own row-based list items.
  - `INPUT` → text prompt; the next inbound free-text message becomes `rawInput`, exactly like
    today's `POST /api/conversations/{id}/messages` flow.
- Inbound handling extracts `wa_id` → `customerId` and the button-reply id / free text →
  `rawInput`, then calls the **same** `ConversationService.reply(...)` that `ConversationController`
  already calls today — 100% reuse of `WorkflowEngine`/session logic, no engine changes.

This alone is a complete, working WhatsApp bot with zero Flows involved — Flows are just one more
interactive message type layered on top of it, so this has to exist first either way.

### Layer 1 — WhatsApp Flows for specific nodes

- No new node **type** — reuse the existing JSON-config pattern already used for `ACTION` nodes:
  add `workflow_node.render_as` (`TEXT` default, or `FLOW`) and a `workflow_node.flow_config`
  JSONB (`{flow_id, cta, mode: STATIC|DYNAMIC, entry_screen}`).
- **Pilot candidate**: the "Request a callback" branch — today 2 button taps + a free-text
  reason — collapses into one Flow screen collecting preferred date/time, contact number, and
  reason in a single native form.
- `WhatsAppMessageMapper` gets one more case: `render_as == FLOW` → build the `interactive.type:
  flow` payload instead of text/buttons.
- Inbound `nfm_reply` → parse `response_json` → merge fields into `session.context` (the same
  `context` map already used for e.g. `entry_reason_code`) → auto-advance past the node with a
  synthetic transition, mirroring how `ACTION` nodes already auto-advance today with no customer
  input (see `advanceAndFinalize`'s `ACTION` case).
- **Recommendation: build static mode first.** All branching lives in the Flow JSON authored in
  WhatsApp Manager; `chat-bot-service` only sends the trigger and reads the final `nfm_reply` — no
  new encryption endpoint, minimal new surface area. Defer the dynamic/endpoint mode (RSA/AES data-
  exchange service, key generation + upload, ping handling) until a specific screen genuinely needs
  live server-side branching that static can't express — it's a meaningful chunk of new crypto
  plumbing that isn't justified without a concrete use case pulling on it.

### Verification (once you decide to build this)

No live WhatsApp Business account/number is provisioned in this environment, so end-to-end testing
needs a Meta test number + ngrok tunnel outside this session. What can be verified without that:
1. Unit tests for `WhatsAppMessageMapper` — one case per node type, asserting the produced Graph
   API payload shape (text vs button vs list vs flow-trigger).
2. A fixture-based test for `WhatsAppWebhookController` — signature verification (valid/invalid),
   and `nfm_reply` → context-merge parsing.
3. Once a Meta test number exists: send real messages via the Cloud API test dashboard against a
   ngrok-tunneled local `POST /webhooks/whatsapp`, confirm replies render correctly in the WhatsApp
   app, and confirm a completed Flow's `response_json` correctly lands in `workflow_session.context`.
