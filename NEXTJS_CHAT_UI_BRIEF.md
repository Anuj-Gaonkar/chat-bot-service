# Build brief: Next.js chat UI for the HDFC chatbot API

Feed this whole file to Claude Code in a **new, empty Next.js project directory** (or ask it to
scaffold one first). It is self-contained — it does not assume Claude has access to the
`chat-bot-service` backend repo, only to this description of its API.

## Goal

A minimal, single-page chat UI that drives a conversation against the backend below: shows the
bot's messages, lets the user answer either by tapping a suggested option or typing free text, and
keeps going until the flow ends. No auth, no multi-conversation history list, no persistence beyond
the current browser session — this is a test harness for the workflow engine, not a production
banking UI.

## Stack

- **Next.js, latest LTS, App Router**, TypeScript.
- Plain `fetch` for API calls — no React Query/SWR/Axios, this app is too small to need them.
- Tailwind CSS for styling (or plain CSS Modules if Claude prefers — either is fine, just keep it
  simple, no component library).
- All state lives in a single client component (`useState`/`useReducer`); no global state manager.

## Backend API contract

Base URL is configurable via `NEXT_PUBLIC_API_BASE_URL` (default `http://localhost:8080`, no
context path). The backend is a Spring Boot service with **no CORS configuration currently
enabled** — see "CORS" note at the end before wiring up real requests.

### 1. Start a conversation

```
POST /api/conversations
Content-Type: application/json

{
  "entryCode": "AMB_SHORTFALL_Q2",
  "customerId": "<any non-blank string, e.g. a made-up customer id for testing>",
  "context": {}
}
```

- `entryCode` and `customerId` are required (non-blank). `context` is an optional free-form
  `Record<string, any>` — send `{}` if you have nothing to seed.
- `entryCode` is currently hardcoded to the single seeded flow: `"AMB_SHORTFALL_Q2"`. Put it behind
  a constant, not user input.
- Response `201 Created`, body is a `ConversationResponse` (shape below).

### 2. Reply to the current question

```
POST /api/conversations/{sessionId}/messages
Content-Type: application/json

{ "rawInput": "<user's typed text, or the eventCode of the option they tapped>" }
```

- `rawInput` is required (non-blank). If the user taps a suggested option, send that option's
  `eventCode` value (e.g. `"YES"`) as the string; if they type free text, send exactly what they
  typed.
- Response `200 OK`, body is a `ConversationResponse`.

### 3. (Optional) Poll current status without advancing

```
GET /api/conversations/{sessionId}
```

Returns a point-in-time snapshot — useful only if you want to support "resume this session after a
page reload" via a `sessionId` stored in `localStorage`. Not required for a first pass; skip unless
time permits.

### Response shape — `ConversationResponse` (returned by both start and reply)

```ts
type SessionStatus = "ACTIVE" | "COMPLETED" | "ABANDONED" | "EXPIRED" | "ERROR";

interface OptionResponse {
  eventCode: string;   // one of a fixed enum, e.g. "YES" | "NO" | "OPTION_1" | "OPTION_2" | "OPTION_3" | "AUTO" | ...
  optionLabel: string; // human-readable text to show on the button
}

interface ConversationResponse {
  sessionId: string;
  status: SessionStatus;
  message: string;              // already newline-joined; render each \n as a new line/paragraph inside one bubble
  currentNodeId: number | null;
  options: OptionResponse[];    // empty array = expects free-text reply; non-empty = show these as buttons
}
```

Turn logic for the UI:
- Append `message` as a new bot bubble each time you get a response (from start or reply).
- If `options` is non-empty, render them as tappable buttons instead of (or above) a text input;
  tapping one sends its `eventCode` as `rawInput` to the reply endpoint.
- If `options` is empty and `status === "ACTIVE"`, show a free-text input instead.
- If `status !== "ACTIVE"` (`COMPLETED`, `ABANDONED`, `EXPIRED`, `ERROR`), the conversation is over:
  disable input, show a "conversation ended" state, optionally offer a "start a new conversation"
  button that re-calls `POST /api/conversations`.

### Error shape

Non-2xx responses return:

```ts
interface ApiError {
  error: string;   // "NOT_FOUND" | "CONFLICT" | "VALIDATION_FAILED"
  message: string;
}
```

- `404 NOT_FOUND` — unknown `sessionId` or unknown `entryCode`.
- `409 CONFLICT` — replied to a session that isn't waiting for input anymore (e.g. already ended).
- `400 VALIDATION_FAILED` — blank `entryCode`/`customerId`/`rawInput`.

Surface `message` to the user in a small inline error banner (e.g. above the input) rather than a
blocking alert; don't crash the chat on error, just let them retry.

## UI requirements

- Single page (`app/page.tsx` or a route of your choosing) with:
  - A scrolling message list — bot bubbles left-aligned, user bubbles right-aligned, auto-scroll to
    bottom on new message.
  - A "Start conversation" state before the first message is sent (e.g. a button, or auto-start on
    page load — either is fine, pick one and keep it simple).
  - Option buttons rendered below the last bot message when `options` is non-empty.
  - A text input + send button, disabled while a request is in flight or when the session has
    ended, shown only when free-text input is expected.
  - A loading indicator (e.g. "…typing" bubble or spinner) while waiting on the API.
  - The inline error banner described above.
- Keep the component tree small: a root client component is fine for this scope; split into
  `MessageList`, `MessageBubble`, `OptionButtons`, `ChatInput` only if it keeps `page.tsx` readable.
- No design system needed — clean, readable, mobile-friendly single column, that's enough.

## Project setup expectations

- `npx create-next-app@latest` (TypeScript, App Router, Tailwind — accept the standard prompts).
- Put the API base URL and a small typed `fetch` wrapper (start/reply/getStatus + the TS interfaces
  above) in one file, e.g. `lib/api.ts`, so the UI code isn't littered with raw `fetch` calls.
- `.env.local` with `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`.
- No test suite required for this pass; a working `npm run dev` against the running backend is the
  bar.

## CORS note (read before wiring up requests)

The backend currently has **no CORS configuration**, so a browser `fetch` from
`http://localhost:3000` straight to `http://localhost:8080` will be blocked. Two options — pick
whichever is faster for Claude to implement, don't do both:

1. **Next.js rewrite proxy (recommended, no backend change needed):** in `next.config.ts`, add a
   `rewrites()` entry that proxies `/api/:path*` on the Next.js dev server to
   `${API_BASE_URL}/api/:path*`, and point the frontend at same-origin `/api/...` instead of the
   absolute backend URL. This avoids touching the Spring Boot service at all.
2. **Backend CORS config:** if the rewrite approach doesn't fit, ask separately for a
   `@CrossOrigin`/`CorsConfigurationSource` bean to be added to the `chat-bot-service` repo allowing
   `http://localhost:3000` — that's a backend-repo change, out of scope for this Next.js task.

Default to option 1.

## Out of scope for this pass

Auth/login, multiple concurrent conversations, conversation history persistence across sessions,
i18n, animations beyond basic transitions, and any styling beyond "clean and usable."
