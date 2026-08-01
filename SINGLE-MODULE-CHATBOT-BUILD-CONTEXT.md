# HDFC WhatsApp Chatbot — Single-Module Build Context

## 0. How to use this file

You are being handed this file inside an **already-scaffolded Spring Boot project**
(Java 21, Spring Boot 4.x). Before writing anything:

1. Read the existing `pom.xml` and directory layout first. Extend what's there — don't
   regenerate the project or discard the scaffold.
2. This file is self-contained. You should not need any other document to build what's
   described here — everything you need (schema, seed data, engine behavior, API shape) is below.
3. Build **one Spring Boot module** — engine, persistence, and REST layer together in a single
   deployable. Do not split this into multiple Maven modules or introduce a library/wrapper
   boundary; that was tried in a sibling project and added real friction (a Spring Boot module
   depended on as a library installs as an unusable "fat jar" by default, config files collide
   across modules, etc.) for no benefit at this scale. One project, package-by-responsibility.
4. Work in **phases** — build Phase 1 completely (schema + seed data + one read-only REST
   endpoint) and confirm it runs before starting Phase 2 (conversation/session endpoints). Phase 2
   is described here for context, not as something to start unprompted — check in before building
   it.
5. If anything below is ambiguous or you have to make a judgment call, prefer asking over
   guessing, especially for the two explicit requirements in section 2 (the `workflow_transition`
   rename and gapped `node_id`s) — get those exactly right, they're deliberate, not incidental.

## 1. Use case

R&D / POC for a WhatsApp chatbot that only ever asks pre-defined questions, offers pre-defined
options, and returns pre-defined responses — **no open-ended NLU, no free-text understanding**.
Used to reach existing bank customers over WhatsApp for feedback, product advertising, and
retention. A customer taps a link or button in a WhatsApp message and lands in a chatbot session
that walks them through a fixed, business-authored conversational flow.

**The mental model:** the flow *definition* (nodes + transitions) is a directed graph with
cycles — not a tree, not a DAG — because branches converge back onto shared nodes (e.g. a menu)
and can loop (retry, "no," a failed backend call). Think finite state machine: nodes are states,
transitions are edges labeled by an event. A given *session's* actual conversation, by contrast,
is a linear path — the customer is only ever at one node at a time; "back" is just popping
history, not graph traversal. `node_code` is a stable, human-readable business label (not
engine-enforced); `node_type` is a small fixed enum that drives what the engine actually does.

## 2. Domain model — target schema

This is the schema to build **directly** — treat it as final, not as something still being
designed. It reflects two deliberate decisions on top of an earlier version of this schema that
used `workflow_edge` and a plain auto-increment `node_id`:

- **`workflow_edge` is renamed to `workflow_transition`** throughout — table name, JPA entity
  (`WorkflowTransition`, not `WorkflowEdge`), repository, service/DTO naming, everywhere. Not
  just the table.
- **`workflow_node.node_id` is gapped, not a plain auto-increment.** Assign IDs manually in steps
  of 10 (100, 110, 120, ...), not `@GeneratedValue`. This leaves room to insert a new node into an
  existing flow later (e.g. 135 between 130 and 140) without renumbering everything after it.
  `workflow_transition`'s own PK (`transition_id`) stays a plain auto-increment — transitions
  aren't inserted "between" other transitions the way nodes are, so the same argument doesn't
  apply there.

Give every column an explicit name (don't rely on JPA's default field-name-as-column-name — a
field named `id` should still map to a column named e.g. `node_id`, not `id`).

### `workflow`
| Column | Type | Notes |
|---|---|---|
| `workflow_id` | PK, identity | permanent identity of a business flow (e.g. "AMB shortfall outreach"); survives every redesign |
| `name` | text | |

### `workflow_version`
| Column | Type | Notes |
|---|---|---|
| `workflow_version_id` | PK, identity | |
| `workflow_id` | FK → workflow | |
| `version_number` | int | |
| `status` | enum: `DRAFT` / `PUBLISHED` / `ARCHIVED` | |
| `start_node_id` | plain `Long`, **not** a JPA relationship | nodes point back at this version, so a `@ManyToOne` here would create a circular insert dependency for no benefit |
| `created_at` | timestamp | |
| `published_at` | timestamp, nullable | |

Nodes and transitions are scoped to a `workflow_version`, not to `workflow` directly — editing a
flow creates a new version instead of mutating a live one, so in-flight sessions on the old
version are never affected.

### `workflow_node`
| Column | Type | Notes |
|---|---|---|
| `node_id` | PK, **manually assigned**, gapped by 10 | see decision above — not `@GeneratedValue` |
| `workflow_version_id` | FK → workflow_version | |
| `node_code` | text | business phase, e.g. `AMB_MENU` — stable across versions, not engine-enforced |
| `node_type` | enum: `START` / `MESSAGE` / `QUESTION` / `INPUT` / `ACTION` / `END` | drives engine behavior — see section 3 |
| `title` | text | short label |
| `message` | text | supports `{{placeholder}}` template tokens filled from session context |
| `back_allowed` | boolean | |
| `home_allowed` | boolean | |
| `exit_allowed` | boolean | |

A node does **not** contain its own options/choices as columns — a node can have any number of
outgoing transitions, which a fixed set of columns can't represent, and a JSON blob would lose
real foreign keys and make "what points into this node" unqueryable.

### `workflow_transition` (renamed from `workflow_edge`)
| Column | Type | Notes |
|---|---|---|
| `transition_id` | PK, identity | plain auto-increment — not gapped |
| `from_node_id` | FK → workflow_node | |
| `event_code` | enum: `AUTO` / `OPTION_1` / `OPTION_2` / `OPTION_3` / `YES` / `NO` / `SUCCESS` / `FAILURE` / `TIMEOUT` / `INVALID_INPUT` | |
| `option_label` | text, nullable | populated only when the transition is a customer-visible choice; null for automatic system transitions (`AUTO`/`SUCCESS`/`FAILURE`) |
| `to_node_id` | FK → workflow_node | |
| `display_order` | int | for rendering multiple options in order |

One row per possible transition — covers both customer-visible choices and automatic system
transitions in the same table (deliberately not split into separate "option" and "transition"
tables — that split was tried earlier and created two divergence-prone sources of truth for the
same thing: "given this node and this event, go here").

### `workflow_action_config`
| Column | Type | Notes |
|---|---|---|
| `node_id` | PK, FK → workflow_node | 1:1 with an `ACTION`-typed node |
| `endpoint` | text | integration/API endpoint to call |
| `http_method` | text | |
| `request_template` | text | how to build the request from session context (not yet interpreted generically — see section 6) |
| `response_mapping` | text | how to map the response back into session context |
| `timeout_ms` | int | |
| `on_success_event` | enum (EventCode) | which event fires on success |
| `on_failure_event` | enum (EventCode) | which event fires on failure |

### `workflow_entry_point`
| Column | Type | Notes |
|---|---|---|
| `entry_code` | PK, text | short link/campaign code from the WhatsApp message, e.g. `AMB_SHORTFALL_Q2` |
| `workflow_version_id` | FK → workflow_version | |
| `start_node_id` | FK → workflow_node | may differ from the version's own default `start_node_id` |
| `valid_from` | date | |
| `valid_to` | date | |

Many-to-one with `workflow_version` — several campaigns can point at the same published flow.

### `workflow_session`
| Column | Type | Notes |
|---|---|---|
| `session_id` | PK, text (e.g. `S12345`) | |
| `workflow_version_id` | FK → workflow_version | pins the session to the exact version it started on |
| `customer_id` | text | |
| `current_node_id` | FK → workflow_node | |
| `status` | enum: `ACTIVE` / `COMPLETED` / `ABANDONED` / `EXPIRED` / `ERROR` | |
| `context` | JSON | captured variables (amount entered, computed shortfall, etc.) used to fill `{{placeholder}}` tokens |
| `started_at` | timestamp | |
| `last_interaction_at` | timestamp | |
| `ended_at` | timestamp, nullable | |

Store `context` as a real Postgres `jsonb` column via an `AttributeConverter<Map<String,Object>,
String>` (Jackson-backed) — not `@Lob`.

### `workflow_session_event` (append-only audit log)
| Column | Type | Notes |
|---|---|---|
| `event_id` | PK, identity | |
| `session_id` | FK → workflow_session | |
| `node_id` | FK → workflow_node | node visited |
| `event_code` | enum (EventCode) | what fired |
| `payload` | JSON | raw detail for audit |
| `created_at` | timestamp | |

One row per node visited and event fired. Deliberately separate from the mutable
`workflow_session` row and deliberately **not** the source for a customer-facing chat transcript
(it's an audit/analytics trail — if Phase 2 needs "show me the whole conversation," prefer
recording the engine's own rendered output as it happens, in its own concept, over reconstructing
it by replaying this log against current session context; see section 7).

**Deferred, not built:** `workflow_node_translation` / `workflow_transition_translation` (i18n) —
this POC is English-only. Design for it later only when a non-English flow is actually needed;
don't build the hook speculatively now.

## 3. Node-type contract — exact engine behavior per `node_type`

| `node_type` | Engine behavior |
|---|---|
| `START` | Entry point, one per `workflow_version`. No message shown. Immediately follows its one `AUTO` transition. |
| `MESSAGE` | Renders `message` (with `{{placeholder}}` substitution from session context), no input expected, auto-advances via its `AUTO` transition. |
| `QUESTION` | Renders `message`, then derives its options by querying `workflow_transition` for every row with `from_node_id` = this node, ordered by `display_order` — however many rows exist is however many options get shown. Waits for the customer's reply, matches it to an `event_code`, follows that transition. |
| `INPUT` | Shows a prompt, accepts free text, writes it into `workflow_session.context`, then advances via its `AUTO` transition. **No validation loop yet** — the contract calls for "invalid entry loops back to itself," but implement this as a stub only (unexercised by the seed flow below, which has no `INPUT` node) — don't build real validation speculatively. |
| `ACTION` | Looks up `workflow_action_config` for the node, calls (simulates, for this POC — no real payment gateway or CRM) the configured backend, follows the `SUCCESS` or `FAILURE` transition based on the result. |
| `END` | Terminal. Renders `message`, marks the session `COMPLETED`. No outgoing transitions. |

**A reply that doesn't match any outgoing transition from the current node** should re-show the
same question (with a short "that wasn't one of the options" prefix) rather than crash or
silently drop the input. This is the simplest reasonable default for an open design question
(retry cap? re-send options? something else?), not a considered final answer — don't over-build
it.

**Not built, deliberately:** a `CONDITION` node type for pure business-rule branching with no
user input and no backend call (e.g. skip a step because the customer already holds a product).
Flagged as a known gap, not added speculatively — add it only when a real use case needs it.

## 4. Runtime walkthrough (worked example, informs how you write the engine)

**Arriving at a `QUESTION` node:** read `node_type`, render `message`, query
`workflow_transition WHERE from_node_id = <node>` ordered by `display_order`, send exactly that
many options, set `current_node_id`, log a `workflow_session_event`, then wait.

**Customer replies:** match the reply text to an `event_code`, look up that transition's
`to_node_id`, log the event, move `current_node_id`, repeat the arrival cycle at the new node.

**Cycle example — `CONFIRM_TOPUP` → "No" → `AMB_MENU`:** the cycle transition points directly at
the menu node, not at `START` — earlier `MESSAGE` nodes are not replayed, only the target node's
own message. No rollback needed: "No" happens before the `ACTION` node is ever reached, so
nothing with side effects has run yet. Context variables survive the loop untouched. The menu
node has no notion of "first visit" vs. "revisit" — it behaves identically regardless of which
transition led into it.

## 5. The AMB shortfall flow — exact data to seed

Seed exactly this flow, once, on startup, if `workflow` is empty (check `count() > 0` and skip —
idempotent, safe to run on every startup). One workflow, one `PUBLISHED` version, entry point
`AMB_SHORTFALL_Q2`, valid `2026-07-01` to `2026-09-30`.

**18 nodes** (`node_id`, `node_code`, `node_type`, `title` / `message` / `back_allowed` /
`home_allowed` / `exit_allowed`):

| node_id | node_code | node_type | title | message | back | home | exit |
|---|---|---|---|---|---|---|---|
| 100 | START | START | Start | System entry - triggered by the AMB-shortfall batch job | false | false | true |
| 110 | WELCOME | MESSAGE | Welcome | Hi {{customer_name}}, this is HDFC Bank on WhatsApp. | false | false | true |
| 120 | AGENDA | MESSAGE | Agenda | Your AMB this quarter is below the required Rs.{{amb_required}}. Let's sort this out - under a minute. | false | false | true |
| 130 | AMB_MENU | QUESTION | AMB menu | What would you like to do? | true | true | true |
| 140 | CONFIRM_TOPUP | QUESTION | Confirm top-up | Transfer Rs.{{shortfall_amount}} now to meet your AMB requirement? | true | true | true |
| 150 | GENERATE_LINK | ACTION | Generate pay link | Calls the payment gateway to create a top-up link | false | false | true |
| 160 | PAYMENT_LINK_SENT | MESSAGE | Payment link sent | Tap below to complete your Rs.{{shortfall_amount}} transfer: {{payment_link}} | false | false | true |
| 170 | LINK_FAILED | MESSAGE | Link failed | Something went wrong generating your payment link. Please try again from the HDFC app. | false | true | true |
| 180 | END_TOPUP | END | Done | Thanks! Once it reflects, your AMB requirement is met. | false | false | false |
| 190 | REMIND_WHEN | QUESTION | Reminder timing | When should we remind you? | true | true | true |
| 200 | SET_REMINDER | ACTION | Schedule reminder | Creates a CRM follow-up task for the chosen date | false | false | true |
| 210 | REMINDER_SET | MESSAGE | Reminder set | Sure, we'll check back with you on {{reminder_date}}. | false | false | true |
| 220 | END_REMINDER | END | Done | No problem, talk soon! | false | false | false |
| 230 | AMB_CHARGES_INFO | MESSAGE | Charges info | If AMB isn't maintained, a non-maintenance charge of Rs.{{amb_charge}} applies each quarter. | false | true | true |
| 240 | CONFIRM_OPT_OUT | QUESTION | Confirm opt-out | Do you want to proceed without maintaining AMB? | true | true | true |
| 250 | RECORD_OPT_OUT | ACTION | Record preference | Updates the CRM preference flag for this customer | false | false | true |
| 260 | OPT_OUT_CONFIRMED | MESSAGE | Opt-out confirmed | Noted. The applicable charges will reflect in your next statement. | false | false | true |
| 270 | END_OPT_OUT | END | Done | Thanks for your time. | false | false | false |

**23 transitions** (`from_node_id`, `event_code`, `option_label`, `to_node_id`, `display_order`):

| from | event_code | option_label | to | order |
|---|---|---|---|---|
| 100 | AUTO | — | 110 | 1 |
| 110 | AUTO | — | 120 | 1 |
| 120 | AUTO | — | 130 | 1 |
| 130 | OPTION_1 | Add Rs.4,500 now | 140 | 1 |
| 130 | OPTION_2 | Remind me later | 190 | 2 |
| 130 | OPTION_3 | Don't maintain AMB | 230 | 3 |
| 140 | YES | — | 150 | 1 |
| 140 | NO | — | 130 | 2 |
| 150 | SUCCESS | — | 160 | 1 |
| 150 | FAILURE | — | 170 | 2 |
| 160 | AUTO | — | 180 | 1 |
| 170 | AUTO | — | 130 | 1 |
| 190 | OPTION_1 | In 3 days | 200 | 1 |
| 190 | OPTION_2 | Next week | 200 | 2 |
| 200 | SUCCESS | — | 210 | 1 |
| 200 | FAILURE | — | 130 | 2 |
| 210 | AUTO | — | 220 | 1 |
| 230 | AUTO | — | 240 | 1 |
| 240 | YES | — | 250 | 1 |
| 240 | NO | — | 130 | 2 |
| 250 | SUCCESS | — | 260 | 1 |
| 250 | FAILURE | — | 130 | 2 |
| 260 | AUTO | — | 270 | 1 |

Three branches fan out from `AMB_MENU` (130); every "No" or failed action cycles back to it:
- **Add money now:** `CONFIRM_TOPUP`(140) → YES → `GENERATE_LINK`(150, ACTION) →
  `PAYMENT_LINK_SENT`(160) → `END_TOPUP`(180). NO or a failed link generation loops back to 130.
- **Remind me later:** `REMIND_WHEN`(190) → `SET_REMINDER`(200, ACTION) → `REMINDER_SET`(210) →
  `END_REMINDER`(220). A failed schedule attempt loops back to 130 (no separate failure message).
- **Skip AMB:** `AMB_CHARGES_INFO`(230) → `CONFIRM_OPT_OUT`(240) → YES → `RECORD_OPT_OUT`(250,
  ACTION) → `OPT_OUT_CONFIRMED`(260) → `END_OPT_OUT`(270). NO or a failed recording loops back to
  130.

**3 action configs** (all `timeout_ms=5000`, `on_success_event=SUCCESS`, `on_failure_event=FAILURE`):

| node_id | endpoint | http_method | request_template | response_mapping |
|---|---|---|---|---|
| 150 | /payments/links | POST | `{amount: shortfall_amount, customer_id}` | `{payment_link: $.link}` |
| 200 | /crm/reminders | POST | `{customer_id, remind_on: reminder_date}` | `{reminder_id: $.id}` |
| 250 | /crm/preferences | POST | `{customer_id, preference: 'AMB_OPT_OUT'}` | `{}` |

**Business-rule enrichment specific to this flow (not a generic hook):** when the customer picks
`REMIND_WHEN`'s options, translate the choice into a concrete `reminder_date` written to session
context — `OPTION_1` ("In 3 days") → today + 3 days; `OPTION_2` ("Next week") → today + 7 days.
Implement this as a small, explicitly node-code-keyed special case in the engine (switched on
`"REMIND_WHEN".equals(fromNode.getNodeCode())`), not as a generic rule engine — see section 6 for
why that's the deliberate scope here.

**Simulated backend calls:** the three `ACTION` nodes don't call a real payment gateway or CRM.
Simulate: always succeed, unless the session context has `simulate_failure=true` set (useful for
exercising the `FAILURE` → `AMB_MENU` cycle-back paths in tests/manual runs), in which case fail
exactly once and then consume/clear that flag.

## 6. Engine — scope and deliberate simplifications

- **One flow, hardcoded, not a generic rule engine.** The `ACTION` node behaviors and the
  `REMIND_WHEN` business rule above are switched on `node_code`, not driven generically off
  `request_template`/`response_mapping`. This is intentional POC scope — don't build a general
  flow-authoring/rule-interpretation engine speculatively. Do that work only when a second flow
  actually needs it.
- **`start(entryCode, customerId, initialContext)` / `reply(sessionId, rawInput)`** is the shape
  of the engine's public API — keep it channel-agnostic (usable by a REST controller, a console
  runner, a WhatsApp webhook adapter, etc. without changes). A turn (`start()` or `reply()`)
  should return everything that happened automatically until the engine needs input again: every
  `MESSAGE`/`ACTION` passed through, ending on the `QUESTION`/`INPUT`/`END` node where it stopped.
- **Template rendering:** `{{placeholder}}` tokens in `message`, filled from session context;
  missing keys are left untouched (literal `{{token}}`) rather than erroring — useful for
  placeholders only ever filled at runtime by an `ACTION` node (e.g. `{{payment_link}}`) when
  rendering the flow's *definition* outside of a live session.

## 7. REST API — phased

### Phase 1 (build this first)

One read-only endpoint: `GET /api/graph` — returns the seeded flow's node/transition graph as
JSON. Use a **grouped** shape: each node embeds its own outgoing transitions directly, rather
than two separate `nodes`/`transitions` arrays (forces the client to cross-reference by ID) or a
deeply nested recursive tree (gets 8+ levels deep on this flow, and needs special-casing for
nodes reached more than once, like `AMB_MENU`). Example:

```json
{
  "workflow": "AMB shortfall outreach",
  "entryPoint": "AMB_SHORTFALL_Q2",
  "nodes": [
    {
      "nodeId": 130, "nodeCode": "AMB_MENU", "nodeType": "QUESTION",
      "message": "What would you like to do?",
      "transitions": [
        { "eventCode": "OPTION_1", "optionLabel": "Add Rs.4,500 now", "toNodeCode": "CONFIRM_TOPUP" },
        { "eventCode": "OPTION_2", "optionLabel": "Remind me later", "toNodeCode": "REMIND_WHEN" },
        { "eventCode": "OPTION_3", "optionLabel": "Don't maintain AMB", "toNodeCode": "AMB_CHARGES_INFO" }
      ]
    }
  ]
}
```

(Field renamed to `transitions`, matching the `workflow_transition` rename — adjust if you'd
rather keep `edges` as public API vocabulary for compatibility with graph-visualization tooling;
either is fine, just be consistent.)

Add Swagger via springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) — Swagger UI at
`/swagger-ui/index.html`, OpenAPI JSON at `/v3/api-docs`. A couple of `@Tag`/`@Operation`
annotations on the controller is enough; don't over-document.

### Phase 2 (deferred — do not build until asked)

Conversation/session endpoints on top of the same engine:
- `POST /api/conversations` — start a session (hardcode entry point `AMB_SHORTFALL_Q2` and demo
  customer context server-side for now — `customer_name`, `amb_required`, `shortfall_amount`,
  `amb_charge` — there's no real customer lookup in this POC).
- `POST /api/conversations/{sessionId}/replies` — body `{ "input": "OPTION_1" }`.
- `GET /api/conversations/{sessionId}` — read-only fetch.

All three should return the **full conversation path so far**, not just the latest turn — record
each turn's rendered output (and the customer's raw input) as it happens, in its own
webapp-owned concept, rather than reconstructing it later from `workflow_session_event` (that
table is an audit/analytics log, not designed to be replayed as a customer-facing transcript —
see the note in section 2).

## 8. Tech stack & setup

- Java 21, Spring Boot 4.x (Spring Web, Spring Data JPA)
- Postgres 16, via Docker Compose. Check what host port is actually free on this machine before
  picking one — port 5432 was already taken by something else on a sibling project's machine, so
  don't assume it's free without checking (`docker ps`).
- `ddl-auto=update` (schema persists/evolves across restarts — this is meant to be a long-running
  server, not a one-shot console demo) — safe given seeding is idempotent.
- springdoc-openapi for Swagger, as above.
- No Flyway for now — single schema still being iterated on; revisit once it stabilizes.

**A known environment pitfall, not module-structure-specific, that may recur:** pgjdbc reports
the JVM's default timezone as a Postgres connection startup parameter. On at least one Windows
dev machine this resolved to the legacy alias `Asia/Calcutta`, which Postgres's tzdata rejected
outright (`FATAL: invalid value for parameter "TimeZone"`). If you hit this, pin
`TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` at the very top of `main()` rather than relying
on host locale.

## 9. Scope guardrails — don't build these speculatively

- No free-text NLU/understanding — matching a reply to an `event_code` is exact-match only.
- No `CONDITION` node type (section 3).
- No `INPUT` validation loop beyond a stub (section 3) — the seed flow has no `INPUT` node to
  exercise it anyway.
- No i18n / translation tables (section 2).
- No session timeout/idle → `EXPIRED` transition logic — `SessionStatus` should have the enum
  value, but nothing needs to drive it yet.
- No generic rule/template-interpretation engine (section 6) — one hardcoded flow is the scope.
- No Phase 2 conversation endpoints until explicitly asked for (section 7).

## 10. Open items to flag back, not silently resolve

- QUESTION-node fallback on an unmatched reply (section 3) is a simplest-default, not a
  considered decision — flag if it matters for a real requirement.
- Whether the Phase 1 API field is called `edges` or `transitions` (section 7) — either is
  defensible, pick one and note which.
- Compliance review (RBI stance on chatbot-initiated financial actions; WhatsApp Business
  messaging policy — template messages, 24-hour session window) has not been done against this
  design — out of scope to resolve here, just don't assume it's been cleared.
