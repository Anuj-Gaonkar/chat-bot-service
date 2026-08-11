# chat-ui — current state (was: build brief)

This started as a self-contained build brief, handed to Claude Code in a fresh, empty Next.js
project directory with no access to the `chat-bot-service` backend repo — just this file's
description of its API. **That build is done.** The app has grown past the original brief (a
second page, a phone-number-based start flow, a typed client for a second backend API) — this
file now documents what's actually there, so it stays useful as a reference instead of going
stale as a historical build order. The original brief's requirements are still accurate for the
one-page chat flow; the additions below are what came after.

## What's actually built

- **Next.js (App Router), TypeScript, Tailwind.** Plain `fetch`, no React Query/Axios — the app
  is still small enough not to need them. State lives in per-page `useState`, no global store.
- **`app/page.tsx`** — the chat flow. Prompts for a **phone number** (not a random generated test
  ID, as the original brief had it) before starting, so it ties into the journey viewer below by a
  value a real tester actually types in. Otherwise matches the original brief: bot bubbles,
  tappable option buttons rendered inline under the message that asked the question (not pinned to
  a bottom bar — see "UI requirements" below, this was revised after the first pass), free-text
  input when `options` is empty, disabled/ended state, "start a new conversation."
- **`app/journey/page.tsx`** — new, not in the original brief. Enter a phone number, see that
  customer's whole conversation replayed: a session summary card (status, timestamps, whatever
  landed in `context`), then a timeline of every step — bot bubbles, option chips with the chosen
  one highlighted, a red call-out for any wrong-answer attempt, and a muted system line for
  backend `ACTION` steps (e.g. `LOG_CALLBACK_REQUEST — SUCCESS`) so it's clear *why* a value
  appeared without ever showing internal-only text as if the customer saw it.
- **`lib/api.ts`** — typed client for both APIs: the original conversation endpoints
  (`startConversation`/`replyToConversation`/`getConversationStatus`) plus `getCustomerFrame`
  (`GET /api/customers/{id}/frame`) and its response types (`SessionFrame`, `FrameStep`,
  `FrameOption`).
- **`components/`** — `ChatInput`, `MessageBubble`, `MessageList`, `OptionButtons` (the original
  chat UI split), plus `JourneyStep` (one step in the `/journey` timeline).
- **`next.config.ts`** — the CORS-avoidance proxy from the original brief (`/api/:path*` →
  `NEXT_PUBLIC_API_BASE_URL`), still the approach in use, plus `allowedDevOrigins` for testing
  from another device on the LAN.

## Backend API contract

Base URL configurable via `NEXT_PUBLIC_API_BASE_URL` (default `http://localhost:8080`). Proxied
through same-origin `/api/...` paths per the CORS note below — always call the relative path, not
the absolute backend URL, from client code.

### Conversation endpoints (original brief, unchanged)

```
POST /api/conversations
{ "entryCode": "AMB_SHORTFALL_Q2", "customerId": "<phone number>", "context": {} }
→ 201, ConversationResponse

POST /api/conversations/{sessionId}/messages
{ "rawInput": "<typed text, or the option's 1-based index as a string, or YES/NO>" }
→ 200, ConversationResponse

GET /api/conversations/{sessionId}
→ 200, ConversationResponse (point-in-time snapshot, doesn't advance)
```

```ts
type SessionStatus = "ACTIVE" | "COMPLETED" | "ABANDONED" | "EXPIRED" | "ERROR";

interface OptionResponse {
  eventCode: string;        // "OPTION" | "YES" | "NO" | ...
  optionIndex: number | null; // null for YES/NO - only OPTION transitions carry an index
  optionLabel: string;
}

interface ConversationResponse {
  sessionId: string;
  status: SessionStatus;
  message: string;           // newline-joined; render each \n as a new paragraph in one bubble
  currentNodeId: number | null;
  options: OptionResponse[]; // empty = expects free text; non-empty = show as buttons
}
```

**One change from the original brief**: there is no fixed `OPTION_1`/`OPTION_2`/`OPTION_3` enum
anymore — a `QUESTION` node can have any number of options. When the user taps an option, send
its `optionIndex` (as a string, e.g. `"3"`) for an `OPTION`-typed choice, or the literal word for
`YES`/`NO`. `eventCode` alone is no longer enough to identify which option was picked.

### Session frame endpoint (new, not in the original brief)

```
GET /api/customers/{customerId}/frame
→ 200, SessionFrame   (customerId's most recent session, replayed end to end)

GET /api/sessions/{sessionId}/frame
→ 200, SessionFrame   (a specific session by ID)
```

```ts
type NodeType = "START" | "MESSAGE" | "QUESTION" | "INPUT" | "ACTION" | "END";
type EventCode = "AUTO" | "OPTION" | "YES" | "NO" | "SUCCESS" | "FAILURE" | "TIMEOUT" | "INVALID_INPUT";

interface FrameOption {
  optionIndex: number | null;
  optionLabel: string;
  chosen: boolean;
}

interface FrameStep {
  sequenceNo: number;
  timestamp: string;
  nodeCode: string;
  nodeType: NodeType;
  title: string;
  message: string | null;       // null for START/ACTION - never customer-facing
  optionsShown: FrameOption[];  // non-empty only for a QUESTION node's reply step
  rawInput: string | null;
  eventCode: EventCode;
  matched: boolean;             // false only for an INVALID_INPUT attempt
}

interface SessionFrame {
  sessionId: string;
  customerId: string;
  workflowVersionId: number;
  status: SessionStatus;
  startedAt: string;
  lastInteractionAt: string;
  endedAt: string | null;
  context: Record<string, unknown>;
  steps: FrameStep[];
  pathSummary: string;   // e.g. "I no longer actively use this account > Service concern > Request a callback"
}
```

`404 NOT_FOUND` if the customer/session has no matching session yet — surface as "no conversation
found," same inline-banner pattern as the conversation endpoints' errors.

### Error shape (unchanged)

```ts
interface ApiErrorBody { error: string; message: string; } // "NOT_FOUND" | "CONFLICT" | "VALIDATION_FAILED"
```
`404` unknown session/entry code · `409` replied to a session no longer waiting for input ·
`400` blank required field. Surface `message` inline, don't crash the chat on error.

## UI requirements (as-built, with one revision from the original brief)

- Chat page: scrolling message list, bot bubbles left / user bubbles right, auto-scroll on new
  message; phone-number entry before the first message; a text input shown only when `options` is
  empty; a loading indicator while a request is in flight; the inline error banner.
- **Option buttons render inline, directly under the message that asked the question** — in the
  scrollable flow, one per line, in the order the backend sent them. The original brief called for
  them "below the last bot message" without specifying placement precisely; the first pass put
  them in a separate fixed bar pinned to the bottom of the screen, which read wrong once real
  conversations got longer than one screen — revised to render as part of the message flow
  instead.
- Journey page: phone-number entry, a session-summary card, then the timeline described above.
- No design system — clean, readable, mobile-friendly single column is still the bar.

## CORS note (still accurate)

The backend has no CORS configuration. The Next.js rewrite proxy in `next.config.ts` (`/api/:path*`
→ the backend) is the approach in use — same-origin requests from the browser, no backend change
needed. If you're adding a new backend endpoint, it's already covered by the wildcard proxy; no
`next.config.ts` change required.

## Still out of scope

Auth/login, multiple concurrent conversations in one tab, conversation history persisted across
browser sessions (the journey viewer reads it from the *backend*, not local storage), i18n,
animations beyond basic transitions.
