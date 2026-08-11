# HDFC WhatsApp Chatbot — Domain Model & Design Decisions

## 0. What this file is now

This started as a from-scratch build spec, handed to Claude Code inside an already-scaffolded,
empty Spring Boot project. **The build described here is done** — Phase 1 and Phase 2 (§7) are
both live, plus features neither phase originally anticipated (session-frame replay, conclusion
tagging — §7.3, §7.4). This file has been repurposed into what it's actually useful for now: the
**domain model and the deliberate design decisions behind it**, kept in sync with the real schema
rather than a historical build-order artifact. For the code that implements this, see
`CODE_WALKTHROUGH.md` (what/why, package by package) and `ENGINE_WALKTHROUGH.md` (the engine,
method by method). For the exact current node/transition data, read `WorkflowSeeder.java`
directly or hit `GET /api/graph/ascii` against a running instance — this doc summarizes the
current flow's *shape*, not an exhaustive row-by-row table, since a full duplicate table here
would just drift out of sync with the seeder every time the flow is edited (which has happened
more than once).

## 1. Use case

R&D / POC for a WhatsApp chatbot that only ever asks pre-defined questions, offers pre-defined
options, and returns pre-defined responses — **no open-ended NLU, no free-text understanding**.
Used to reach existing bank customers over WhatsApp for feedback, product advertising, and
retention. A customer taps a link or button in a WhatsApp message and lands in a chatbot session
that walks them through a fixed, business-authored conversational flow. The one seeded flow today
is **AMB shortfall outreach** — a customer below the required Average Monthly Balance is walked
through funding their account, requesting a reminder, or explaining why they can't/won't, ending
in one of ~12 distinct outcomes.

**The mental model:** the flow *definition* (nodes + transitions) is a directed graph with
cycles — not a tree, not a DAG — because branches converge back onto shared nodes (e.g. a menu)
and can loop (retry, "no," a failed backend call). Think finite state machine: nodes are states,
transitions are edges labeled by an event. A given *session's* actual conversation is a linear
path — the customer is only ever at one node at a time. `node_code` is a stable, human-readable
business label (not engine-enforced); `node_type` is a small fixed enum that drives what the
engine actually does.

## 2. Domain model — current schema

Two decisions from the original design remain load-bearing everywhere:

- **`workflow_edge` was renamed to `workflow_transition`** — table, JPA entity, repository,
  everywhere.
- **`workflow_node.node_id` is gapped, not a plain auto-increment** — manually assigned in steps
  of 10 (100, 110, 120, ...), leaving room to insert a node later without renumbering.
  `workflow_transition.transition_id` stays a plain auto-increment.

A third decision was made later, after the original 3-choice ceiling turned out to be a real
constraint:

- **`event_code`'s `OPTION_1`/`OPTION_2`/`OPTION_3` values were collapsed into one generic
  `OPTION` value, plus a new `option_index` column on `workflow_transition`** (1-based position
  among a node's `OPTION` siblings; null for every other event code). A `QUESTION` node can now
  offer any number of options without ever touching the enum or its DB `CHECK` constraint again.

Give every column an explicit name (don't rely on JPA's default field-name-as-column-name).

### `workflow`
| Column | Type | Notes |
|---|---|---|
| `workflow_id` | PK, identity | permanent identity of a business flow; survives every redesign |
| `name` | text | |

### `workflow_version`
| Column | Type | Notes |
|---|---|---|
| `workflow_version_id` | PK, identity | |
| `workflow_id` | FK → workflow | |
| `version_number` | int | |
| `status` | enum: `DRAFT` / `PUBLISHED` / `ARCHIVED` | |
| `start_node_id` | plain `Long`, **not** a JPA relationship | avoids a circular insert dependency |
| `created_at` | timestamp | |
| `published_at` | timestamp, nullable | |

Nodes and transitions are scoped to a `workflow_version`, not `workflow` directly — editing a
flow creates a new version instead of mutating a live one.

### `workflow_node`
| Column | Type | Notes |
|---|---|---|
| `node_id` | PK, **manually assigned**, gapped by 10 | |
| `workflow_version_id` | FK → workflow_version | |
| `node_code` | text | business phase, e.g. `AMB_MENU` |
| `node_type` | enum: `START` / `MESSAGE` / `QUESTION` / `INPUT` / `ACTION` / `END` | drives engine behavior — §3 |
| `title` | text | short label |
| `message` | text | `{{placeholder}}` tokens filled from session context; internal-only for `START`/`ACTION` nodes (never shown to the customer — see §3) |
| `back_allowed` | boolean | |
| `home_allowed` | boolean | |
| `exit_allowed` | boolean | |
| `conclusion_code` | text, nullable | set only on `END` nodes — a static tag for the business outcome landing there represents (§7.4) |

A node does not contain its own options as columns — outgoing `workflow_transition` rows do.

### `workflow_transition` (renamed from `workflow_edge`)
| Column | Type | Notes |
|---|---|---|
| `transition_id` | PK, identity | plain auto-increment |
| `from_node_id` | FK → workflow_node | |
| `event_code` | enum: `AUTO` / `OPTION` / `YES` / `NO` / `SUCCESS` / `FAILURE` / `TIMEOUT` / `INVALID_INPUT` | |
| `option_index` | int, nullable | 1-based position among this node's `OPTION` siblings; null for every other event code |
| `option_label` | text, nullable | populated only for customer-visible choices |
| `to_node_id` | FK → workflow_node | |
| `display_order` | int | render order |
| `entry_reason_code` | text, nullable | set only on a handful of "entry" transitions (currently `AMB_MENU`'s 5 options) — which top-level path this choice represents (§7.4) |

One row per possible transition — customer-visible choices and automatic system transitions
share this table on purpose, avoiding two divergence-prone sources of truth for "given this node
and this event, go here."

### `workflow_action_config`
| Column | Type | Notes |
|---|---|---|
| `node_id` | PK, FK → workflow_node | 1:1 with an `ACTION`-typed node |
| `endpoint` | text | integration/API endpoint to call |
| `http_method` | text | |
| `request_template` | text | not interpreted generically — see engine's `simulateAction()` |
| `response_mapping` | text | ditto |
| `timeout_ms` | int | |
| `on_success_event` | enum (EventCode) | |
| `on_failure_event` | enum (EventCode) | |

### `workflow_entry_point`
| Column | Type | Notes |
|---|---|---|
| `entry_code` | PK, text | e.g. `AMB_SHORTFALL_Q2` |
| `workflow_version_id` | FK → workflow_version | |
| `start_node_id` | FK → workflow_node | may differ from the version's own default |
| `valid_from` | date | |
| `valid_to` | date | |

### `workflow_session`
| Column | Type | Notes |
|---|---|---|
| `session_id` | PK, text (e.g. `S12345`) | |
| `workflow_version_id` | FK → workflow_version | pins the session to the version it started on |
| `customer_id` | text | whatever the caller supplies (a phone number, for this demo) — no lookup/validation |
| `current_node_id` | FK → workflow_node | |
| `status` | enum: `ACTIVE` / `COMPLETED` / `ABANDONED` / `EXPIRED` / `ERROR` | **only `ACTIVE`/`COMPLETED` are ever actually set** — the other three are scaffolding for an idle-session reaper that hasn't been built |
| `context` | jsonb | captured variables used to fill `{{placeholder}}` tokens |
| `started_at` | timestamp | |
| `last_interaction_at` | timestamp | |
| `ended_at` | timestamp, nullable | |
| `conclusion_code` | text, nullable | frozen from the arrival node's `conclusion_code` the instant an `END` node is reached (§7.4) |
| `entry_reason_code` | text, nullable | frozen from context the same moment, originally set when `AMB_MENU` was answered (§7.4) |

`context` is a real Postgres `jsonb` column via an `AttributeConverter<Map<String,Object>,
String>` (Jackson-backed), not `@Lob`.

### `workflow_session_event` (append-only audit log)
| Column | Type | Notes |
|---|---|---|
| `event_id` | PK, identity | |
| `session_id` | FK → workflow_session | |
| `node_id` | FK → workflow_node | node visited |
| `event_code` | enum (EventCode) | what fired |
| `payload` | jsonb | raw detail, e.g. `{"rawInput": "3"}` |
| `created_at` | timestamp | |

One row per node visited/event fired. Deliberately separate from the mutable `workflow_session`
row. The original design note here said this table was **not** meant to be replayed as a
customer-facing chat transcript — that's still true (`chat-ui` keeps its own transcript
client-side, never reads this table) — but it *is* now replayed for a different purpose: §7.3's
session-frame API reconstructs a whole session from it, for support/debug/analytics use. One
consequence: the engine's `END` case explicitly logs an event for itself now (nothing else does
that for a terminal node type) specifically so a completed session's last message shows up when
replayed — a real gap found and fixed while building that feature.

**Deferred, not built:** `workflow_node_translation`/`workflow_transition_translation` (i18n) —
still English-only, still not worth building speculatively.

## 3. Node-type contract — exact engine behavior per `node_type`

| `node_type` | Engine behavior |
|---|---|
| `START` | Entry point, one per `workflow_version`. No message shown. Immediately follows its `AUTO` transition. |
| `MESSAGE` | Renders `message`, no input expected, auto-advances via `AUTO`. |
| `QUESTION` | Renders `message`, derives options from every `workflow_transition` row with this `from_node_id`, ordered by `display_order`. Waits for a reply, matches it (literal `YES`/`NO`, or numeric `option_index` for `OPTION`), follows that transition. |
| `INPUT` | Shows a prompt, accepts free text, writes it into `context`, advances via `AUTO`. No validation loop — accepts anything. |
| `ACTION` | Looks up `workflow_action_config`, simulates the configured backend call, follows `SUCCESS`/`FAILURE`. **Its own `message` is never shown to the customer** — that column is an internal description of the call (e.g. "Calls the payment gateway..."), not customer copy. This wasn't true in the original design (the engine used to render it) — fixed after the internal text leaked into the live chat UI. |
| `END` | Terminal. Renders `message`, marks the session `COMPLETED`, logs its own arrival event, freezes `conclusion_code`/`entry_reason_code` onto the session (§7.4). No outgoing transitions. |

**A reply that doesn't match any outgoing transition from the current node** re-shows the same
question (with a "that wasn't one of the options" prefix) rather than crashing or silently
dropping the input, and is itself logged as an `INVALID_INPUT` event — kept in the audit trail
(and shown in a replayed session frame) even though a live customer transcript wouldn't need it.

**Not built, deliberately:** a `CONDITION` node type for pure business-rule branching with no
user input and no backend call. Still flagged as a known gap, not added speculatively.

## 4. Runtime walkthrough (worked example)

**Arriving at a `QUESTION` node:** render `message`, query outgoing transitions ordered by
`display_order`, send exactly that many options, set `current_node_id`, log a
`workflow_session_event`, wait.

**Customer replies:** match the reply (literal `YES`/`NO`, or numeric index against `OPTION`
transitions), look up the matched transition's `to_node_id`, log the event, move
`current_node_id`, repeat the arrival cycle at the new node.

**Cycle example — a failed `ACTION` looping back to `AMB_MENU`:** e.g.
`GENERATE_FUND_LINK`(200, `ACTION`) → `FAILURE` → `AMB_MENU`(120). The cycle transition points
directly at the menu node, not at `START` — earlier `MESSAGE` nodes are never replayed, only the
target node's own message. No rollback needed: nothing with side effects downstream of the
failure has run yet, and `simulate_failure` (the one-shot test/demo flag every `ACTION` node
checks) is consumed on the failing attempt, so an immediate retry succeeds. The menu node has no
notion of "first visit" vs. "revisit" — it behaves identically regardless of which transition led
into it. (This is also why `entry_reason_code`, §7.4, always reflects the customer's *latest*
`AMB_MENU` answer rather than their first, if they looped back and answered differently the
second time.)

## 5. The AMB shortfall flow — current shape (not an exhaustive table)

Seeded once, idempotently, by `WorkflowSeeder` (`if (workflowRepository.count() > 0) return;`).
One workflow, one `PUBLISHED` version, entry point `AMB_SHORTFALL_Q2`.

Every seeded message that originally referenced customer-specific data
(`{{customer_name}}`, `{{amb_required}}`, `{{shortfall_amount}}`, `{{amb_charge}}`) has since been
rewritten into generic copy — there's no real customer-data lookup in this demo, and the bot never
asks for or states a specific amount. Where money actually needs to move (funding the account),
the flow just sends a link (`{{payment_link}}`, generated at runtime) and lets the customer decide
the amount themselves.

`AMB_MENU` (120) branches five ways, matching `entry_reason_code`'s taxonomy:

1. **`FUND_NOW`** — generates a (simulated) payment link, sends it, ends. No live funded/
   not-funded branch — the engine has no real async wait, so a genuine later recheck can't show
   its result in the same turn as the acknowledgment. (A `CHECK_FUNDING_STATUS`/`END_FUNDED`/
   `END_FUND_PENDING` trio exists in the schema but is currently unreachable from the live graph —
   kept as a template for whatever eventually sends a real follow-up check, e.g. via a second
   entry point straight into that `ACTION` node.)
2. **`FUNDS_SHORTLY`** — asks when (3/7/15 days), schedules a reminder → `REMINDER_SET`.
3. **`CASH_FLOW_CONSTRAINTS`** — "remind me later" (reuses branch 2's sub-flow, same
   `REMINDER_SET` ending but a different `entry_reason_code`), "speak to an executive" →
   `ESCALATED_TO_EXECUTIVE`, or a generic charges redirect → `INFO_REDIRECT`.
4. **`UNAWARE_OF_REQUIREMENT`** — three informational options, two sharing a generic
   `INFO_REDIRECT` ending, one ("upgrade benefits") reserved as a named-but-empty placeholder
   (`ACCOUNT_UPGRADE_JOURNEY`, conclusion `UPGRADE_INTEREST`) for a future real multi-step
   upgrade sub-flow.
5. **`CHURN_RISK`** — five distinct, reason-specific endings (no shared "close the account?
   yes/no" question). Two of them ("service concern," "other — please specify") offer a tappable
   "Request a callback" button — modeled as an ordinary single-`OPTION` `QUESTION` node feeding a
   shared logging `ACTION`, no new schema/engine concept needed for a "button."

Every `ACTION` node's failure branch, and the failure branch of every downstream action across
all five branches, loops back to `AMB_MENU` (120) — the one convergence point in the whole graph.

**Action configs** — six `ACTION` nodes today, all simulated (no real payment gateway or CRM),
`timeout_ms=5000`, `on_success_event=SUCCESS`, `on_failure_event=FAILURE`: `GENERATE_FUND_LINK`,
`CHECK_FUNDING_STATUS` (dormant), `SCHEDULE_FUNDS_REMINDER`, `ROUTE_TO_EXECUTIVE`,
`CONVERT_SALARY_ACCOUNT`, `LOG_CALLBACK_REQUEST`.

**Business-rule enrichment specific to this flow** (§`ENGINE_WALKTHROUGH.md` §3's
`applyNodeChoiceRule`): `AMB_MENU`'s choice is tagged into context as `entry_reason_code`;
`FUNDS_TIMING`'s choice becomes a concrete `reminder_date`; `CASH_FLOW_MENU`'s choice is recorded
as `assistance_type`; `CHURN_REASON_MENU`'s choice (for the 4 fixed-label options) is recorded as
`reason`. All node-code-keyed special cases, not a generic rule engine — see §6.

**Simulated backend calls**: every `ACTION` node "succeeds" by default; `simulate_failure=true` in
session context makes the next one fail exactly once, then the flag is consumed — used to exercise
every `FAILURE → AMB_MENU` path in tests without a real integration.

For the exact current node IDs, messages, and transition table, read `WorkflowSeeder.java` or hit
`GET /api/graph/ascii`.

## 6. Engine — scope and deliberate simplifications

- **One flow, hardcoded, not a generic rule engine.** `ACTION` node side effects and every
  business rule in §5 are switched on `node_code`, not driven generically off
  `request_template`/`response_mapping`. Still intentional POC scope.
- **`start(entryCode, customerId, initialContext)` / `reply(sessionId, rawInput)`** — the engine's
  public API, channel-agnostic. A turn returns everything that happened automatically until the
  engine needs input again.
- **Template rendering**: `{{placeholder}}` tokens filled from session context; missing keys are
  left untouched rather than erroring.
- **Reply matching**: `matchReply()` — literal `YES`/`NO`, or a numeric index against `OPTION`
  transitions (no NLU, no per-option enum ceiling — see the `event_code` decision in §2).

## 7. REST API

### 7.1 `GET /api/graph` / `GET /api/graph/ascii` (originally "Phase 1")

Read-only views of the flow's *definition*. `/api/graph` returns a grouped JSON shape (each node
embeds its own outgoing transitions). `/api/graph/ascii` is a cycle-aware recursive plain-text
tree — prints `(already shown above)` on a repeat visit to a convergence node like `AMB_MENU`
instead of recursing forever. Swagger UI at `/swagger-ui/index.html`.

### 7.2 `POST /api/conversations`, `POST /api/conversations/{sessionId}/messages`, `GET /api/conversations/{sessionId}` (originally "Phase 2," now built)

The live conversation loop, on top of the same engine. `POST /api/conversations` takes
`{ entryCode, customerId, context }` — `customerId` is caller-supplied with no lookup (a phone
number, for the demo UI). Every response is the **full turn's rendered output collapsed into one
newline-joined `message`**, not per-node granularity — a channel like WhatsApp renders one turn as
one bubble.

### 7.3 `GET /api/sessions/{sessionId}/frame`, `GET /api/customers/{customerId}/frame` (added later)

Not part of the original spec. Reconstructs a customer's whole journey — every node visited, what
options were on screen and which was chosen (including `INVALID_INPUT` retries), and a derived
`pathSummary` breadcrumb — by replaying `workflow_session_event` against the graph as it stands
*today* (not a byte-exact historical snapshot; see `CODE_WALKTHROUGH.md` §8 for the tradeoff).
Built for support/debug lookups and eventual bulk analytics, not for the live customer-facing
transcript (`chat-ui` never calls these).

### 7.4 Session conclusions (added later, no dedicated endpoint — queried directly)

`workflow_node.conclusion_code` / `workflow_session.conclusion_code` tag *what* a session's
outcome was; `workflow_transition.entry_reason_code` / `workflow_session.entry_reason_code` tag
*why* the customer engaged in the first place (needed because several different paths converge on
the same `conclusion_code` — see §5's branch 2/3 note). A Postgres view, `session_outcome`
(`db-backups/migrations/2026-08-11_session_conclusions.sql`), derives an outcome dynamically for
sessions that never reach an `END` node (`'DROPPED_AT:' || current node_code`), since no
idle-session reaper exists to tag those eagerly. See `CODE_WALKTHROUGH.md` §9 for the full design
rationale.

## 8. Tech stack & setup

- Java 21, Spring Boot 4.x (Spring Web, Spring Data JPA)
- Postgres 16 via Docker Compose, host port **5434** (5432 was already taken on the original dev
  machine — check `docker ps` before assuming a port is free on a new one).
- `ddl-auto=update` — schema evolves across restarts (new nullable columns like `conclusion_code`
  show up automatically the moment the app boots on updated entity classes, *before* any
  migration SQL runs — the `db-backups/migrations/` files exist to populate/tag data and keep a
  live dev DB in sync with the seeder's intent, not to create columns).
- springdoc-openapi for Swagger.
- No Flyway — still iterating; `db-backups/migrations/` (plain, hand-run SQL files, applied via
  `docker exec ... psql ... < file.sql`) is the current substitute, used every time the seeded
  flow or schema changes on a DB that already has data (the seeder itself is idempotent and won't
  re-seed, so a schema/data change needs its own SQL file applied directly).

**A known environment pitfall**: pgjdbc reports the JVM's default timezone as a Postgres
connection startup parameter; on some Windows machines this resolves to the legacy alias
`Asia/Calcutta`, which Postgres's tzdata rejects outright. Pin
`TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` at the top of `main()` if you hit this.

**A recurring dev-loop gotcha**: `spring-boot:run` does not hot-reload a running JVM. Kill any
existing `java` process (`Get-Process -Name java` / `lsof -i :8080`) before trusting a fresh
`curl` against newly-compiled code — this has caused real confusion more than once.

## 9. Scope guardrails — still true today

- No free-text NLU — a reply is either matched exactly or it isn't.
- No `CONDITION` node type.
- No `INPUT` validation loop — accepts and stores whatever's typed (exercised today by
  `CHURN_REASON_OTHER`).
- No i18n / translation tables.
- **No session idle-timeout/abandonment job** — `SessionStatus` has `ABANDONED`/`EXPIRED` values,
  but nothing drives them. `session_outcome` (§7.4) works around this for reporting without the
  job existing.
- No generic rule/template-interpretation engine.
- The session-frame replay (§7.3) is a re-render against *current* node text, not a byte-exact
  historical capture — a deliberate, discussed tradeoff, not an oversight.

## 10. Open items, past and present

Resolved since the original list:
- ~~Whether the Phase 1 API field is `edges` or `transitions`~~ — `transitions`, consistently.
- ~~Phase 2 conversation endpoints~~ — built (§7.2).
- ~~QUESTION-node fallback on unmatched reply~~ — kept as the simplest default (re-show with a
  prefix), still not revisited as a considered decision, but it's shipped and working across every
  branch of a much larger flow than it was designed against.

Still open:
- Whether the session-frame replay should ever be upgraded to byte-exact historical capture
  (capturing rendered text into `workflow_session_event.payload` at write time) — discussed,
  deliberately deferred, not built.
- Whether/when to build a real idle-session-abandonment job.
- Compliance review (RBI stance on chatbot-initiated financial actions; WhatsApp Business
  messaging policy) — still not done against this design, still not assumed cleared.
