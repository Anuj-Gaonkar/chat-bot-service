# chat-bot-service — Code Walkthrough

Written for an associate-level Java/Spring developer joining this project. It explains *what*
every part of the code does and, more importantly, *why* it's built that way. For the domain
model this grew out of, see `SINGLE-MODULE-CHATBOT-BUILD-CONTEXT.md` — that file now documents
the schema/design decisions as they currently stand, not a from-scratch build spec (the project
is long past that phase). For a method-by-method deep dive on the engine specifically, see
`ENGINE_WALKTHROUGH.md`.

---

## 1. What this service actually does

It's a **WhatsApp chatbot backend for HDFC Bank**, scoped way down from what "chatbot" usually
implies: there's no AI, no natural-language understanding. A customer gets a WhatsApp message
with a link (e.g. "your account balance is below the minimum"), taps it, and lands in a fixed,
pre-authored conversation — the bank asks pre-defined questions, offers pre-defined button
options, and the customer picks one. No free text is ever interpreted as intent (the one place
free text is accepted, an `INPUT` node, just stores it verbatim — it's never parsed).

The seeded flow (`WorkflowSeeder`) is the **AMB shortfall outreach** journey — a customer whose
Average Monthly Balance has dropped below the required minimum is asked how they'd like to
proceed, and the conversation branches five ways from there: fund the account now, expect funds
shortly, temporary cash-flow constraints, wasn't aware of the requirement, or no longer actively
uses the account. See `SINGLE-MODULE-CHATBOT-BUILD-CONTEXT.md` §5 for the exact node/transition
tables, or hit `GET /api/graph/ascii` against a running instance for a live tree diagram.

**The mental model that explains almost every design decision in this codebase:**

- The **flow definition** (what nodes exist, what connects to what) is a directed graph **with
  cycles** — think a finite state machine, not a tree. Multiple branches lead back to the same
  node (every action failure, and a couple of "remind me later" sub-choices, loop back to the
  main menu).
- A **session** (one customer's actual conversation) is a straight line through that graph —
  they're only ever standing at one node, moving forward one step at a time.
- A **frame** (added later, see §8) is neither of those — it's a *replay* of a session's straight
  line, reconstructed after the fact for support/analytics use, not something the live engine
  produces.

Once you hold those three apart, the trickiest-looking pieces of code — `WorkflowEngine
.advanceAndFinalize()`, `GraphService.appendChildren()`, and `SessionFrameService.buildFrame()`
— all make sense: one *walks a line forward, live*, one *walks a graph with cycle detection*, and
one *replays a line that's already been walked*.

---

## 2. Project layout (package-by-responsibility)

```
in.bank.hdfc.chat_bot_service
├── ChatBotServiceApplication   – main() entry point
├── entity          – JPA @Entity classes = the database tables
├── repository       – Spring Data JPA interfaces = how we query those tables
├── engine          – WorkflowEngine: runs an actual conversation, turn by turn
├── conversation      – REST layer on top of the engine: the live chat API a client drives
├── graph           – read-only API: view the flow's *definition* (not a live session)
├── frame           – read-only API: replay a *finished-or-in-progress* session's whole journey
├── seed            – WorkflowSeeder: inserts the demo flow on first startup
└── web             – GlobalExceptionHandler / ApiError: engine exceptions → HTTP status codes
```

Still deliberately **one Spring Boot module**, not split into a `core`/`api`/`persistence`
multi-module Maven build — see §0.3 of the build-context doc for the fuller argument (a module
depended on as a library installs as an unusable "fat jar" by default; not worth it at this
scale).

---

## 3. Tech stack cheat sheet

| File | What it's for |
|---|---|
| `pom.xml` | Java 21, Spring Boot 4.x. Key deps: `spring-boot-starter-data-jpa`, `spring-boot-starter-webmvc`, `postgresql` driver, `springdoc-openapi` (Swagger UI), `lombok`. |
| `application.yaml` | Points at Postgres on `localhost:5434` (not the default 5432 — see docker-compose below), `ddl-auto: update` (Hibernate auto-adds/alters columns from the `@Entity` classes as they evolve — this is *why* a brand-new nullable column like `conclusion_code` shows up in the DB the moment the app boots, before any migration SQL runs; the migration files under `db-backups/migrations/` exist to *populate/tag* that data and keep the live dev DB in sync with the seeder's intent, not to create the column itself). |
| `docker-compose.yml` | Spins up Postgres 16, mapped to host port **5434**. Run `docker compose up -d` before starting the app. |
| `ChatBotServiceApplication.java` | Standard Spring Boot bootstrap, plus one Windows-specific fix: pins the JVM's default timezone to UTC before Spring starts (some Windows machines report the legacy alias `Asia/Calcutta`, which Postgres's tzdata rejects outright). |

**Lombok annotations you'll see on every entity** (`@Getter`, `@Setter`, `@NoArgsConstructor`,
`@AllArgsConstructor`, `@Builder`) are compile-time code generators, not runtime magic —
`@Builder` in particular is used heavily in `WorkflowSeeder` for fluent construction.
`@RequiredArgsConstructor` (on services/controllers) generates a constructor over every `final`
field, which is what makes constructor injection work without writing the constructor by hand.

**A recurring gotcha worth knowing up front**: `spring-boot:run` does not hot-reload a running
JVM's compiled classes just because you edited and recompiled source on disk. If you edit
`WorkflowEngine.java` and curl against an already-running server, you'll see old behavior until
you actually kill the old process and start a fresh one — check `Get-Process -Name java` (or
`ps`/`lsof -i :8080` on Unix) before assuming a code change isn't working.

---

## 4. The domain model (`entity` package)

Every entity maps 1:1 to a Postgres table.

### `Workflow` / `WorkflowVersion`
Unchanged from the original design: `Workflow` is the permanent identity of a business flow;
`WorkflowVersion` is a versioned snapshot (`DRAFT`/`PUBLISHED`/`ARCHIVED`) that nodes and
transitions actually belong to, so editing a flow never affects a customer already mid-conversation
on the old version. `startNodeId` is a plain `Long`, not a JPA relationship, to avoid a circular
insert dependency.

### `WorkflowNode` → `workflow_node` table
- **`nodeId` is manually assigned, gapped by 10** (100, 110, 120, ...) — leaves room to insert a
  node later without renumbering. `WorkflowTransition.transitionId`, by contrast, *is* a plain
  auto-increment, since transitions are never inserted "between" other transitions.
- **`nodeType`** (`START`/`MESSAGE`/`QUESTION`/`INPUT`/`ACTION`/`END`) drives what the engine does
  at that node — see §6.
- **`conclusionCode`** (added later) — set only on `END`-type nodes, a static tag declaring the
  business outcome landing there represents (`FUNDED`, `ESCALATED_TO_EXECUTIVE`,
  `CALLBACK_REQUESTED`, ...). Null everywhere else. See §9.

No "options" column — a node's possible next steps live entirely in `WorkflowTransition` rows
pointing at it.

### `WorkflowTransition` → `workflow_transition` table
One row = one possible move: `fromNodeId` → (`eventCode`) → `toNodeId`. `eventCode` is `AUTO` /
**`OPTION`** / `YES` / `NO` / `SUCCESS` / `FAILURE` / `TIMEOUT` / `INVALID_INPUT` — note there is
**no** `OPTION_1`/`OPTION_2`/`OPTION_3` in the enum. That was the original design and it capped a
`QUESTION` node at 3 options; it was replaced with a single generic `OPTION` event code plus an
`optionIndex` column (1-based position among a node's OPTION siblings, null for every other event
code). A `QUESTION` node can now offer any number of options without ever touching this enum or
its DB `CHECK` constraint again. `optionLabel` is customer-visible text (null for system
transitions like `AUTO`/`SUCCESS`/`FAILURE`). `displayOrder` controls render order.

- **`entryReasonCode`** (added later) — set only on `AMB_MENU`'s 5 outgoing options, tagging
  which top-level path each one represents (`FUND_NOW`, `FUNDS_SHORTLY`,
  `CASH_FLOW_CONSTRAINTS`, `UNAWARE_OF_REQUIREMENT`, `CHURN_RISK`). See §9.

### `WorkflowActionConfig` → `workflow_action_config` table
1:1 with an `ACTION`-typed node. Describes what backend call that node should simulate
(`endpoint`, `httpMethod`, `requestTemplate`/`responseMapping` — not interpreted generically, see
`WorkflowEngine.simulateAction()`, which hardcodes behavior per `nodeCode` instead) and which
`EventCode` fires on success vs. failure.

### `WorkflowEntryPoint` → `workflow_entry_point` table
`entryCode` (e.g. `AMB_SHORTFALL_Q2`) → which `workflowVersionId`/`startNodeId` to start at,
valid for a date range. Unchanged.

### `WorkflowSession` → `workflow_session` table
One customer's in-progress-or-finished conversation: `sessionId`, `workflowVersionId`,
`customerId` (a plain string — for this demo it's whatever the caller supplies, e.g. a phone
number; there's no customer lookup/validation), `currentNodeId`, `status`
(`ACTIVE`/`COMPLETED`/`ABANDONED`/`EXPIRED`/`ERROR` — **only `ACTIVE` and `COMPLETED` are
actually ever set today**; the other three exist in the enum as scaffolding for a future
idle-session reaper that hasn't been built), and `context` — a real Postgres `jsonb` column
(`JsonbMapConverter`, Jackson-backed) holding whatever variables the flow has accumulated
(`payment_link`, `reminder_date`, `reason`, `entry_reason_code`, ...) used to fill
`{{placeholder}}` tokens.

- **`conclusionCode`** / **`entryReasonCode`** (added later) — frozen copies from the node/
  transition tags above, written by `WorkflowEngine` the moment a session reaches an `END` node.
  See §9.

### `WorkflowSessionEvent` → `workflow_session_event` table
An **append-only audit log** — one row per node visited / event fired during a session, kept
deliberately separate from the mutable `WorkflowSession` row. The original design note here said
this was "not meant to be replayed as a customer-facing chat transcript" — that's still true in
the narrow sense (`chat-ui` keeps its own in-memory transcript, it never reads this table), but
this log **is** now replayed, for a different audience: `SessionFrameService` (§8) reconstructs a
whole session from it for support/debugging/analytics use. One consequence worth knowing: the
`END` case in `advanceAndFinalize()` explicitly logs an event for itself (nothing else does that
automatically for a terminal node), specifically so a completed session's very last message shows
up when replayed — this was a real bug found and fixed while building the frame feature.

---

## 5. Repositories (`repository` package)

Plain `interface X extends JpaRepository<Entity, IdType>`, with a handful of **Spring Data
derived query methods** (parsed from the method name, no `@Query`/SQL needed):

- `WorkflowNodeRepository.findByWorkflowVersionIdOrderByNodeIdAsc(Long versionId)`
- `WorkflowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(Long fromNodeId)` — one
  node's outgoing transitions, in order (used by the engine, one node at a time)
- `WorkflowTransitionRepository.findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc(Collection<Long>)`
  — the batched version, for `GraphService` (every node's transitions in one query)
- `WorkflowSessionRepository.findFirstByCustomerIdOrderByStartedAtDesc(String customerId)` — a
  customer can have multiple sessions over time; this is "their most recent one," used by the
  frame API's phone-number lookup
- `WorkflowSessionEventRepository.findBySessionIdOrderByEventIdAsc(String sessionId)` — a
  session's whole event log in exact chronological order (`event_id`, not `created_at` — two fast
  hops could tie on timestamp precision, never on the auto-increment PK), used by
  `SessionFrameService`

---

## 6. The engine (`engine` package)

Runs an actual conversation, channel-agnostic (usable from a REST controller, a console runner,
a future WhatsApp webhook, without changing a line of `WorkflowEngine` itself). Public API is
still exactly two methods — `start(entryCode, customerId, initialContext)` and
`reply(sessionId, rawInput)` — see `ENGINE_WALKTHROUGH.md` for the full method-by-method
treatment. Two behavioral notes that changed since the engine was first built:

- **Reply matching is now generic.** A `QUESTION` node's options are matched either literally
  (`YES`/`NO`) or by 1-based index against the `OPTION` event code's `optionIndex` (the channel
  echoes back the plain number it was shown, e.g. `"3"`) — see `WorkflowEngine.matchReply()`.
  There's no per-option enum value anymore, so a node can offer any number of options.
- **`ACTION` nodes never render customer-facing text.** An `ACTION` node's `message` column is an
  internal description of the simulated backend call (e.g. "Calls the payment gateway to create a
  secured funding link"), not copy meant for the customer — `advanceAndFinalize()`'s `ACTION` case
  runs the simulated call and logs the event, but does *not* add a `RenderedStep` for it. (Earlier
  in this project's life it did, and that internal description leaked into the live chat UI — this
  was a real bug reported and fixed.)

---

## 7. The `conversation` package — the live chat API

This is the REST layer on top of the engine (originally deferred as "Phase 2" in the build-context
doc; it's fully built now). `ConversationController`:

- `POST /api/conversations` — start a session. Body: `{ entryCode, customerId, context }`.
- `POST /api/conversations/{sessionId}/messages` — reply to the current question. Body:
  `{ rawInput }`.
- `GET /api/conversations/{sessionId}` — read-only snapshot, doesn't advance anything.

`ConversationResponse` is the public, channel-agnostic shape returned by the first two — it
collapses `EngineTurnResult.steps()` into one newline-joined `message` string (so a channel like
WhatsApp renders one turn as one bubble) and maps `OptionView` → `OptionResponse`. Kept as a
separate record from the engine's own `EngineTurnResult` specifically so the engine's internal
DTO shape stays free to change independently of what a REST client needs.

`GlobalExceptionHandler` (`web` package) maps the engine's plain JDK exceptions to HTTP status:
`IllegalArgumentException` (unknown session/entry code — a lookup miss) → `404`;
`IllegalStateException` (session isn't waiting for input, or an internal invariant violation) →
`409`; validation failures → `400`.

---

## 8. The `frame` package — replaying a whole journey

Added well after the live chat API, for a different use case: "show me exactly what this one
customer went through, end to end" — for support/debugging, or bulk analytics later. Two
endpoints (`SessionFrameController`):

- `GET /api/sessions/{sessionId}/frame`
- `GET /api/customers/{customerId}/frame` — looks up the customer's most recent session first

`SessionFrameService.reconstructFrame()` builds a `SessionFrameResponse` by:
1. Loading the session row (header: status, timestamps, final `context`, `conclusionCode`,
   `entryReasonCode`).
2. Loading its whole `workflow_session_event` log, in order.
3. For each event, joining out to the node it happened at (to get the node's message/type) and,
   for `QUESTION`-type nodes, the node's outgoing transitions (to know what options were on
   screen and which one was `chosen`).
4. Re-rendering each step's message via the same `TemplateRenderer` the live engine uses, against
   the session's **final** `context` — not a byte-exact historical snapshot of what the customer
   saw at that moment, but a replay against the graph as it stands *today*. (A deliberate,
   discussed tradeoff: it needs no new capture machinery and works on every session already in
   the DB, at the cost of possibly reflecting later edits to a node's wording if the graph changes
   after the fact.)
5. Computing `pathSummary` — the full breadcrumb of every option actually chosen, joined with
   `" > "` (e.g. `"I no longer actively use this account > Service concern > Request a
   callback"`). This is free to compute: step 3 already worked out which option was `chosen` at
   each question, `pathSummary` just collects those labels in order. It exists specifically
   because a session's frozen `conclusionCode`/`entryReasonCode` (§9) are coarse — several
   different sub-paths can converge on the same node and get the same tags, and `pathSummary` is
   how you tell them apart without a new persisted field for every possible distinction.

Frame steps deliberately include `INVALID_INPUT` attempts (wrong-answer retries) — useful for
spotting confusing UX moments, even though a live customer-facing transcript wouldn't show them.
`ACTION`-node steps carry no `message` (same suppression rule as §6) but do appear in the step
list, so the frame still explains *why* a value like `payment_link` shows up in context between
two customer-visible steps.

---

## 9. Session conclusions — `conclusionCode` and `entryReasonCode`

Two small, additive columns exist specifically so bulk analysis ("how many customers got
escalated to an executive," "what % drop off at the main menu") doesn't need to re-derive an
outcome from `current_node_id`/`status` every time:

- **`conclusionCode`** — *what happened*. Tagged once per `END` node in `WorkflowSeeder` (see
  `tagConclusions()`), copied onto `WorkflowSession.conclusionCode` by `WorkflowEngine` the
  instant the session reaches that node. A permanent snapshot, immune to the taxonomy being
  edited later.
- **`entryReasonCode`** — *why they engaged*. Tagged once per `AMB_MENU` option in
  `WorkflowSeeder` (see `tagEntryReasons()`), captured into session `context` the moment
  `AMB_MENU` is answered (`applyNodeChoiceRule`'s `"AMB_MENU"` case), then frozen onto
  `WorkflowSession.entryReasonCode` alongside `conclusionCode` when the session completes. This
  exists because several different paths converge on the same `conclusionCode` — e.g. "I expect
  funds shortly" (direct) and "cash flow constraints" → "remind me later" both end on
  `REMINDER_SET` — and `entryReasonCode` is what tells those two apart in a `GROUP BY`.

Sessions that never finish aren't tagged eagerly (no scheduled abandonment-detection job exists).
Instead, a Postgres **view**, `session_outcome` (see `db-backups/migrations/
2026-08-11_session_conclusions.sql`), derives an outcome dynamically for anything still `ACTIVE`:
`COALESCE(conclusion_code, 'DROPPED_AT:' || current node's node_code)`. One query —
`SELECT conclusion, entry_reason_code, COUNT(*) FROM session_outcome GROUP BY conclusion, entry_reason_code`
— covers every session regardless of whether it ever finished.

---

## 10. The graph API (`graph` package) — viewing the flow's *definition*

Unchanged in shape from the original design: `GET /api/graph` (grouped JSON — each node embeds
its own outgoing transitions) and `GET /api/graph/ascii` (a recursive, cycle-aware plain-text
tree, printing `(already shown above)` on a repeat visit to a node like `AMB_MENU` instead of
recursing forever). One change: `GraphService.messageSuffix()` used to render node messages
against a small hardcoded `DEMO_CONTEXT` map (`customer_name`, `amb_required`, etc.) so the
diagram didn't show raw `{{token}}` placeholders — those placeholders were removed from every
seeded message (see §11), so it now renders against `Map.of()` (empty context) instead. The only
placeholders left in any node message are `{{payment_link}}`, which is engine-generated at
runtime and was always going to render literal on a diagram anyway (`TemplateRenderer` leaves
missing keys untouched rather than erroring).

---

## 11. The seeder (`seed/WorkflowSeeder.java`)

Still `ApplicationRunner`-based and idempotent (`if (workflowRepository.count() > 0) return;`).
The seeded flow itself has been rebuilt more than once since the original 3-branch design (add
money now / remind me later / don't maintain AMB) — it's now the 5-branch **AMB shortfall
outreach** journey matching the bank's actual WhatsApp script, with every placeholder that would
have needed real customer data (`{{customer_name}}`, `{{amb_required}}`, `{{shortfall_amount}}`,
`{{amb_charge}}`) rewritten into generic copy — this is a demo-scale service with no customer data
lookup, so the bot never asks for or references a specific amount; where money changes hands
(funding the account), it just sends a link and lets the customer decide the amount.

Current shape, branching from `AMB_MENU` (120):
1. **Fund now** → generates a real (simulated) payment link, sends it, ends — no live
   funded/not-funded branch (the engine has no real async wait).
2. **Funds shortly** → asks when (3/7/15 days), schedules a reminder.
3. **Cash-flow constraints** → remind me later (reuses branch 2's sub-flow), speak to an
   executive, or a generic charges redirect.
4. **Unaware of requirement** → three informational redirects, one reserved as a placeholder for
   a future real "account upgrade journey" sub-flow.
5. **No longer active** → five distinct reason-specific endings; two of them ("service concern",
   "other") offer a tappable "Request a callback" button — modeled as an ordinary single-option
   `QUESTION` node, no new schema/engine concept needed for a "button."

Two seeder helper methods worth knowing about beyond `seedNodes()`/`seedTransitions()`/
`seedActionConfigs()`: `tagConclusions()` and `tagEntryReasons()` (§9) — both run as a pass over
the already-built node/transition lists just before `saveAll()`, so tagging a new outcome/entry
reason never means touching the `node(...)`/`transition(...)` builder helpers or their call
sites.

For the exact current node/transition tables, read `WorkflowSeeder.java` directly, or hit
`GET /api/graph/ascii` against a running instance.

---

## 12. Tests (`WorkflowEngineTest`)

Still a `@SpringBootTest` — boots the real Spring context, talks to the real dev Postgres (via
docker-compose), relies on the seeder's idempotency. No Testcontainers, no mocking — an
integration test. **Postgres must be running** first.

19 tests as of the current graph, covering: every branch end to end, the `simulate_failure`
retry-and-succeed pattern, the `FUNDS_TIMING` date computation, the `INVALID_INPUT` re-prompt
path, out-of-range option handling, and — notably — a test that specifically proves two
*different* paths (funds-shortly vs. cash-flow→remind-later) land on the *same*
`conclusionCode` but get *different* `entryReasonCode`, directly validating §9's whole reason for
existing.

---

## 13. Running it locally

```bash
docker compose up -d                 # starts Postgres on localhost:5434
./mvnw spring-boot:run                 # starts the app; seeder runs automatically
```

Then:
- `POST http://localhost:8080/api/conversations` — start a conversation (the live chat API)
- `GET http://localhost:8080/api/graph` / `/api/graph/ascii` — the flow's definition
- `GET http://localhost:8080/api/sessions/{id}/frame` / `/api/customers/{id}/frame` — replay a
  session
- `http://localhost:8080/swagger-ui/index.html` — Swagger UI
- `chat-ui` (sibling repo) is a Next.js test harness that drives the conversation API and the
  frame API against a phone number — see that repo's docs for its own setup.

Remember the stale-JVM gotcha from §3 if you're iterating: kill the old `java` process before
trusting a fresh `curl` against newly-compiled code.

---

## 14. Deliberate scope limits — still true today

- No free-text NLU — a reply is either matched exactly (`YES`/`NO` literal, or `OPTION` +
  numeric index) or it isn't.
- No `CONDITION` node type (pure business-rule branching with no user input) — still a flagged,
  not-built gap.
- `INPUT` node handling has no real validation loop — it accepts and stores whatever free text is
  typed (used today by `CHURN_REASON_OTHER`, the "other, please specify" reason).
- No i18n/translation tables — English only.
- **No session idle-timeout/abandonment job** — `ABANDONED`/`EXPIRED` exist as enum values but
  nothing ever sets them; a genuinely in-progress session and one the customer walked away from
  hours ago look identical in `workflow_session.status` (`ACTIVE` either way). `session_outcome`
  (§9) works around this for reporting purposes without needing the job to exist.
- No generic rule/template-interpretation engine — every business rule and simulated backend call
  is hardcoded per `nodeCode`, on purpose.
- The frame reconstruction (§8) is **not** a byte-exact historical replay — it re-renders against
  today's node text and the session's final context. A "capture the exact rendered text at the
  moment it happened" version was discussed and deliberately deferred; if it's ever built, it'd
  add a rendered-text snapshot to `workflow_session_event.payload` at write time, and
  `SessionFrameService` would prefer that over re-rendering wherever it's present.
