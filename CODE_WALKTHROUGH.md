# chat-bot-service — Code Walkthrough

Written for an associate-level Java/Spring developer joining this project. It explains
*what* every part of the code does and, more importantly, *why* it's built that way. For the
original design spec, see `SINGLE-MODULE-CHATBOT-BUILD-CONTEXT.md` — this file explains the
code that resulted from it.

---

## 1. What this service actually does

It's a **WhatsApp chatbot backend for HDFC Bank**, but scoped way down from what "chatbot"
usually implies: there's no AI, no natural-language understanding. A customer gets a WhatsApp
message with a link (e.g. "your account balance is below the minimum"), taps it, and lands in a
fixed, pre-authored conversation: the bank asks pre-defined questions, offers pre-defined
button options ("Add money now" / "Remind me later" / "Don't maintain AMB"), and the customer
picks one. That's it — no free text is ever interpreted as intent.

**The mental model that explains almost every design decision in this codebase:**

- The **flow definition** (what nodes exist, what connects to what) is a directed graph **with
  cycles** — think a finite state machine, not a tree. Multiple branches can lead back to the
  same node (e.g. every "No" or failed backend call routes back to the main menu node).
- A **session** (one customer's actual conversation) is a straight line through that graph —
  they're only ever standing at one node, moving forward one step at a time.

Once you hold that distinction in your head, the two trickiest-looking pieces of code —
`WorkflowEngine.advanceAndFinalize()` and `GraphService.appendChildren()` — both make sense:
one *walks a line* (a session), the other *walks a graph with cycle detection* (a diagram of
every possible path).

---

## 2. Project layout (package-by-responsibility)

```
in.bank.hdfc.chat_bot_service
├── ChatBotServiceApplication   – main() entry point
├── entity          – JPA @Entity classes = the database tables
├── repository       – Spring Data JPA interfaces = how we query those tables
├── engine          – WorkflowEngine: runs an actual conversation, turn by turn
├── graph           – read-only API: view the flow's *definition* (not a live session)
└── seed            – WorkflowSeeder: inserts the one demo flow on first startup
```

This is deliberately **one Spring Boot module**, not split into a `core`/`api`/`persistence`
multi-module Maven build. At this project's size that split adds friction (config duplication,
one module ending up packaged as an unusable "fat jar" if another module depends on it) for no
real benefit — see section 0.3 of the build-context doc if you want the fuller argument.

---

## 3. Tech stack cheat sheet

| File | What it's for |
|---|---|
| `pom.xml` | Java 21, Spring Boot 4.1.0. Key deps: `spring-boot-starter-data-jpa` (JPA/Hibernate), `spring-boot-starter-webmvc` (REST controllers), `postgresql` driver, `springdoc-openapi` (Swagger UI), `lombok` (see below). |
| `application.yaml` | Points at Postgres on `localhost:5434` (not the default 5432 — see docker-compose below), `ddl-auto: update` (Hibernate creates/alters tables automatically from the `@Entity` classes — fine for this POC, would normally be Flyway/Liquibase migrations in a real production system), `show-sql: true` (logs every SQL statement — handy while learning the codebase, noisy in prod). |
| `docker-compose.yml` | Spins up Postgres 16 in a container, mapped to host port **5434** (not 5432) because 5432 was already taken on the dev machine. Run `docker compose up -d` before starting the app. |
| `ChatBotServiceApplication.java` | Standard Spring Boot bootstrap, plus one Windows-specific fix: pins the JVM's default timezone to UTC before Spring starts, because on some Windows machines the JVM reports its timezone as the legacy alias `Asia/Calcutta`, which Postgres's tzdata rejects outright, crashing every DB connection attempt. |

**Lombok annotations you'll see on every entity** (`@Getter`, `@Setter`, `@NoArgsConstructor`,
`@AllArgsConstructor`, `@Builder`): these are compile-time code generators, not runtime magic.
`@Getter`/`@Setter` write the obvious `getX()`/`setX()` methods for you. `@Builder` gives you a
fluent way to construct an object (`WorkflowNode.builder().nodeId(100L).nodeCode("START")...build()`)
instead of a long constructor call — you'll see this used heavily in `WorkflowSeeder`.
`@RequiredArgsConstructor` (on services) generates a constructor that takes every `final` field
— that's what makes constructor injection work without writing the constructor by hand.

---

## 4. The domain model (`entity` package)

Every entity maps 1:1 to a Postgres table. A few of them look over-engineered for a POC until
you read the comment explaining why — that's deliberate, so read the code comments, they carry
real design intent.

### `Workflow` → `workflow` table
The permanent identity of a business flow (e.g. "AMB shortfall outreach"). Just an id and a
name. Survives even if the flow gets completely redesigned.

### `WorkflowVersion` → `workflow_version` table
A **version** of a workflow: `status` is `DRAFT`/`PUBLISHED`/`ARCHIVED`. Nodes and transitions
belong to a *version*, not directly to a `Workflow`. Why the extra layer? So editing a flow
creates a **new** version instead of mutating the live one — a customer mid-conversation on the
old version is never affected by an in-flight edit. `startNodeId` is a plain `Long`, not a JPA
`@ManyToOne` relationship to `WorkflowNode` — using a real relationship here would create a
circular dependency at insert time (the version needs a node to exist, but nodes need a version
to exist first).

### `WorkflowNode` → `workflow_node` table
One state in the flow. The two fields worth calling out:
- **`nodeId` is manually assigned, gapped by 10** (100, 110, 120, ...) — look at `WorkflowSeeder`
  and you'll see every node ID is a round number. This is **not** `@GeneratedValue` on purpose:
  it leaves room to insert a new node later (e.g. `135` between `130` and `140`) without
  renumbering everything downstream. Compare this to `WorkflowTransition.transitionId`, which
  *is* a plain auto-increment — transitions are never inserted "between" other transitions the
  way nodes are, so there's no reason to gap those.
- **`nodeType`** (`START`/`MESSAGE`/`QUESTION`/`INPUT`/`ACTION`/`END`) is the single field that
  drives what the engine actually *does* at that node — see section 6, it's the most important
  enum in the whole codebase.

Note there's no "options" column on a node — a node's possible next steps live entirely in
`WorkflowTransition` rows pointing at it, because a node can have any number of outgoing
choices, which a fixed set of columns can't represent.

### `WorkflowTransition` → `workflow_transition` table (renamed from the earlier `workflow_edge`)
One row = one possible move: `fromNodeId` → (`eventCode`) → `toNodeId`. `eventCode` is an enum
(`AUTO`, `OPTION_1..3`, `YES`, `NO`, `SUCCESS`, `FAILURE`, `TIMEOUT`, `INVALID_INPUT`).
`optionLabel` is only populated when the transition is a customer-visible choice (e.g. "Add
Rs.4,500 now") — it's `null` for system-driven transitions like `AUTO`/`SUCCESS`/`FAILURE`,
which the customer never sees as a button. `displayOrder` controls what order multiple options
are shown in. Deliberately **one table** covering both customer choices and automatic system
transitions — an earlier design split those into two tables and that created two
sources-of-truth for the same underlying question ("given this node and this event, where do I
go?").

### `WorkflowActionConfig` → `workflow_action_config` table
1:1 with an `ACTION`-typed node (its primary key **is** the node's id — no separate identity
column). Describes what backend call that node should simulate: `endpoint`, `httpMethod`,
`requestTemplate`/`responseMapping` (not yet interpreted generically — see the engine's
`simulateAction()`, which hardcodes behavior per `nodeCode` instead), and which `EventCode`
fires on success vs. failure.

### `WorkflowEntryPoint` → `workflow_entry_point` table
The bridge between a WhatsApp campaign link and a flow version: `entryCode` (e.g.
`AMB_SHORTFALL_Q2`, the short code from the link) → which `workflowVersionId` and
`startNodeId` to start at, valid for a date range (`validFrom`/`validTo`). Many entry points can
point at the same published version — several campaigns can share one flow.

### `WorkflowSession` → `workflow_session` table
One customer's **in-progress conversation**: `sessionId`, which version they're pinned to
(so a live edit never changes their flow mid-conversation), `currentNodeId`, `status`
(`ACTIVE`/`COMPLETED`/`ABANDONED`/`EXPIRED`/`ERROR`), and `context` — a `Map<String, Object>`
of captured variables (`customer_name`, `shortfall_amount`, a generated `payment_link`, etc.)
used to fill in `{{placeholder}}` tokens in node messages.

`context` is stored as a real Postgres `jsonb` column, not a giant text blob (`@Lob`), via
`JsonbMapConverter` — a small `AttributeConverter<Map<String,Object>, String>` that
serializes/deserializes the map through Jackson's `ObjectMapper`. This is what
`@Convert(converter = JsonbMapConverter.class)` on the field wires up.

### `WorkflowSessionEvent` → `workflow_session_event` table
An **append-only audit log** — one row per node visited / event fired during a session. Kept
deliberately separate from the mutable `WorkflowSession` row, and deliberately **not** meant to
be replayed as a customer-facing chat transcript later (it's an audit/analytics trail; if a
"show me the whole conversation" feature is ever needed, the plan is to record the engine's own
rendered output as it happens, in its own concept — not reconstruct it from this log).

---

## 5. Repositories (`repository` package)

These are all plain `interface X extends JpaRepository<Entity, IdType>` — Spring Data JPA
generates the implementation at runtime, you never write one. Most just get free CRUD methods
(`findById`, `save`, etc.) for nothing. The few with extra method signatures use **Spring Data's
derived query convention** — the method name itself is parsed into a query, no `@Query`
annotation or SQL needed:

- `WorkflowVersionRepository.findFirstByStatus(WorkflowVersionStatus status)` → `SELECT * FROM
  workflow_version WHERE status = ? LIMIT 1`
- `WorkflowNodeRepository.findByWorkflowVersionIdOrderByNodeIdAsc(Long versionId)` → all nodes
  for a version, sorted by id
- `WorkflowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(Long fromNodeId)` → every
  outgoing transition from one node, in display order (used by the engine, one node at a time)
- `WorkflowTransitionRepository.findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc(Collection<Long> ids)`
  → the same thing but for a *batch* of node ids in one query (used by `GraphService`, which
  needs every node's transitions at once to build the full graph view — avoids one query per
  node).

If you haven't seen this convention before: `findBy` + field name (+ `In` for "field is one of
these values") + `OrderBy` + field name + `Asc`/`Desc` — Spring parses that method name
character by character and builds the query from it.

---

## 6. The engine (`engine` package) — the heart of the service

This is the part that actually runs a conversation. It's built to be **channel-agnostic** —
usable from a REST controller, a console test runner, or eventually a real WhatsApp webhook,
without changing a line of `WorkflowEngine` itself. Its public API is exactly two methods:

```java
EngineTurnResult start(String entryCode, String customerId, Map<String, Object> initialContext)
EngineTurnResult reply(String sessionId, String rawInput)
```

### The three small DTOs it returns

- **`RenderedStep(nodeCode, nodeType, message)`** — one node's rendered output during a turn
  (its message with `{{placeholders}}` already substituted).
- **`OptionView(eventCode, optionLabel)`** — one selectable option, if the engine stopped at a
  `QUESTION`/`INPUT` node waiting for a reply.
- **`EngineTurnResult(sessionId, status, steps, currentNodeCode, options)`** — the full result of
  one `start()`/`reply()` call: **every** `MESSAGE`/`ACTION` node the engine passed through
  automatically, ending on the node where it had to stop (a question, an input prompt, or the
  end of the flow).

That "batch multiple steps into one result" part matters: a real conversation might go
`WELCOME` → `AGENDA` → `AMB_MENU` in one `start()` call, because `WELCOME` and `AGENDA` are pure
`MESSAGE` nodes with no user input needed — the engine doesn't stop until it hits something that
*does* need input (a `QUESTION`) or the flow ends.

### `start()` — begin a session

Looks up the `WorkflowEntryPoint` by its code (e.g. `AMB_SHORTFALL_Q2`), builds a brand-new
`WorkflowSession` row sitting at that entry point's start node, with whatever `initialContext`
was passed in (demo values like `customer_name`, `shortfall_amount` — there's no real customer
lookup in this POC), then hands off to `advanceAndFinalize()`.

### `reply()` — the customer answered something

Loads the session and its current node, then dispatches on `nodeType`:
- `QUESTION` → `handleQuestionReply()`
- `INPUT` → `handleInputReply()`
- anything else → throws — you can't "reply" to a `MESSAGE`/`ACTION`/`END` node, the engine was
  never waiting for input there in the first place.

`handleQuestionReply()` is worth reading closely (`WorkflowEngine.java:81`):
1. Load this node's outgoing transitions.
2. Try to parse the raw customer text into an `EventCode` (`parseEventCode` — just
   `EventCode.valueOf(rawInput.trim().toUpperCase())` wrapped in a try/catch; this is the *only*
   place anything resembling "understanding" the reply happens, and it's exact-match only, no
   NLU).
3. Find a transition whose `eventCode` matches.
4. **If nothing matches**: log an `INVALID_INPUT` event, then **re-render the same question**
   prefixed with "Sorry, that wasn't one of the options." — and importantly, return immediately
   without moving `currentNodeId`. The session stays exactly where it was.
5. **If it matches**: there's one flow-specific special case — if the current node's code is
   `REMIND_WHEN`, call `applyRemindWhenRule()` first (translates "In 3 days"/"Next week" into an
   actual `reminder_date` written into session context). Then log the event and advance to the
   matched transition's target node via `advanceAndFinalize()`.

That `REMIND_WHEN` special case is a good example of this project's chosen scope: it's a small,
explicitly `nodeCode`-keyed `if`, not a generic business-rule engine. The build-context doc is
explicit that building a generic rule/template interpreter is *out of scope* — one hardcoded
flow is all this needs right now.

### `advanceAndFinalize()` — the state-machine loop (`WorkflowEngine.java:115`)

This is the core loop. Given a node to arrive at, it keeps stepping forward automatically until
it hits something that requires stopping:

```java
while (true) {
    switch (current.getNodeType()) {
        case START   -> // no message, just auto-follow to the real first node
        case MESSAGE  -> // render it, log AUTO, keep going
        case ACTION   -> // simulate the backend call, follow SUCCESS or FAILURE
        case QUESTION -> // render it, save session, RETURN (waiting for a reply)
        case INPUT    -> // render it, save session, RETURN (waiting for free text)
        case END      -> // render it, mark session COMPLETED, RETURN (done)
    }
}
```

`START`, `MESSAGE`, and `ACTION` don't `return` — they fall through to the next iteration of the
loop with `current` reassigned to the next node (via `follow()`). `QUESTION`, `INPUT`, and `END`
all `return` a result, because those are the only three node types where the engine legitimately
has to stop and wait (or finish).

`follow(node, eventCode)` (`WorkflowEngine.java:252`) is the one-hop primitive underneath all of
this: look up the node's outgoing transitions, find the one matching the given event code, load
and return its target node. If none matches, it throws — that would mean the seed data is
inconsistent (e.g. an `ACTION` node with no `FAILURE` transition configured), which should never
happen with a correctly-seeded flow.

### `simulateAction()` — fake backend calls (`WorkflowEngine.java:186`)

There's no real payment gateway or CRM in this POC. Every `ACTION` node "succeeds" by default;
setting `simulate_failure=true` in a session's context makes the *next* action fail exactly
once, then that flag is consumed (removed) so a retry succeeds. This exists specifically so
tests (and manual runs) can exercise the `FAILURE → AMB_MENU` cycle-back paths without a real
integration. Side effects (like generating a fake `payment_link`) are hardcoded per `nodeCode`
in a `switch` — again, not driven generically from `requestTemplate`/`responseMapping`.

### `TemplateRenderer` (its own tiny class, `engine/TemplateRenderer.java`)

A one-method utility: finds `{{token}}` patterns in a string via regex and replaces each with
`context.get(token)` if present. If a key is missing from the context, the token is left
**literal** rather than throwing or blanking it out — useful because a node's raw `message` can
be rendered outside of a live session (e.g. by the `/api/graph` endpoints below), where
runtime-only values like `{{payment_link}}` were never going to be available anyway.

---

## 7. The graph API (`graph` package) — viewing the flow's *definition*

Unlike the engine (which runs one customer's *live, linear* path), this package answers a
different question: **"what does the whole flow look like?"** — the full graph, branches and
cycles included. Two endpoints, both read-only, both built from the same seeded data:

### `GET /api/graph` — JSON view

`GraphController.getGraph()` → `GraphService.getGraph()` (`GraphService.java:33`):
1. Find the currently `PUBLISHED` `WorkflowVersion` (this POC only ever seeds one).
2. Load its `Workflow` and `WorkflowEntryPoint`.
3. Load every `WorkflowNode` for that version, and every `WorkflowTransition` originating from
   any of those nodes **in one batched query** (`findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc`)
   rather than one query per node.
4. Build a `WorkflowGraphResponse`: each node embeds its own outgoing transitions directly
   (`NodeDto` → `List<TransitionDto>`). This "grouped" shape was a deliberate choice over either
   (a) two separate top-level `nodes`/`transitions` arrays, which forces the API consumer to
   cross-reference by id, or (b) a deeply-nested recursive tree, which would need special-casing
   for nodes reached more than once (like `AMB_MENU`, reached from three different branches) and
   gets 8+ levels deep on this flow.

### `GET /api/graph/ascii` — plain-text tree diagram

This is the endpoint we added most recently, and it *does* build a recursive tree — on purpose,
because here the goal is a human-readable diagram, not a machine-consumable API shape, so the
downsides that ruled out a tree for the JSON endpoint don't apply.

`GraphController.getGraphAscii()` → `GraphService.getAsciiDiagram()`
(`GraphService.java` — the method added after `getGraph()`). It fetches the same
version/workflow/entryPoint/nodes/transitions as `getGraph()`, then walks the graph starting
from the entry point's start node, indenting one level deeper per hop:

```java
Set<Long> printed = new HashSet<>();
printed.add(start.getNodeId());
appendChildren(start, "", printed, nodeById, transitionsByFromNodeId, out);
```

`appendChildren()` is a classic recursive tree-printer (the same shape as the Unix `tree`
command): for each outgoing transition of the current node, print one line prefixed with either
`+--- ` (more siblings follow) or `\--- ` (last sibling), then — **only if this target node
hasn't been printed before** — recurse into it with the prefix extended by `|    ` (if this
branch wasn't the last one, so the vertical bar needs to keep going down past it) or `     `
(blank, if it was the last branch — nothing more to connect down to).

The **cycle-detection** (`printed.add(target.getNodeId())` returning `false` on a repeat) is the
whole reason this can't be naive recursion: `AMB_MENU` (node 130) is a transition target from at
least five different places (`CONFIRM_TOPUP` NO, `LINK_FAILED` AUTO, `SET_REMINDER` FAILURE,
`CONFIRM_OPT_OUT` NO, `RECORD_OPT_OUT` FAILURE). Without tracking what's already been printed,
the walk would recurse into `AMB_MENU`'s children again each time, which recurse back into it,
forever. Instead, a repeat visit just prints `(already shown above)` on that one line and stops
— no further recursion down that branch.

Each line's format: `{EVENT_CODE}["option label"] --> {node_id} {NODE_CODE} [{NODE_TYPE}]  "{message}"`.
Messages are passed through `TemplateRenderer` against a small hardcoded `DEMO_CONTEXT` map
(`customer_name`, `amb_required`, `shortfall_amount`, `amb_charge`) so the diagram reads
naturally instead of showing raw `{{customer_name}}` tokens — this mirrors the sibling
`chatbot-webapp` project's `GraphService.getAsciiDiagram()`, which this was modeled on (adapted
here from that project's JPA `@ManyToOne` relationships to this project's flat
`fromNodeId`/`toNodeId` foreign-key longs).

---

## 8. The seeder (`seed/WorkflowSeeder.java`)

Implements `ApplicationRunner`, so Spring calls its `run()` once, automatically, right after the
application context starts up. First line: `if (workflowRepository.count() > 0) return;` — if
any workflow already exists, do nothing. That one check is what makes it **idempotent** and
safe to leave running on every single app startup, in every environment, forever (no separate
"only run once" flag or migration tool needed for this POC).

When it does run, it inserts, in order: one `Workflow`, one `PUBLISHED` `WorkflowVersion`, the
18 `WorkflowNode` rows, the 23 `WorkflowTransition` rows, the 3 `WorkflowActionConfig` rows, and
one `WorkflowEntryPoint` (`AMB_SHORTFALL_Q2`, valid 2026-07-01 to 2026-09-30). If you want to see
the exact shape of the flow, section 5 of `SINGLE-MODULE-CHATBOT-BUILD-CONTEXT.md` has full
tables for all of this, or just read the seeder's `seedNodes()`/`seedTransitions()` directly —
they're plain, readable builder calls.

**The flow itself, in one paragraph**: `START`(100) → `WELCOME`(110) → `AGENDA`(120) →
`AMB_MENU`(130), which branches three ways: *add money now* (→`CONFIRM_TOPUP`→`GENERATE_LINK`
[ACTION]→`PAYMENT_LINK_SENT`→`END_TOPUP`, with "No" or a failed link generation looping back to
130), *remind me later* (→`REMIND_WHEN`→`SET_REMINDER`[ACTION]→`REMINDER_SET`→`END_REMINDER`,
failure loops back to 130), and *don't maintain AMB* (→`AMB_CHARGES_INFO`→`CONFIRM_OPT_OUT`→
`RECORD_OPT_OUT`[ACTION]→`OPT_OUT_CONFIRMED`→`END_OPT_OUT`, "No" or failure loops back to 130).
This is exactly what the `/api/graph/ascii` endpoint renders as a tree.

---

## 9. Tests (`WorkflowEngineTest`)

A `@SpringBootTest` — it boots the *real* Spring context and talks to the *real* dev Postgres
(via the same docker-compose instance you run locally), relying on the seeder's idempotency to
have consistent data available. No Testcontainers, no mocking of the engine's dependencies —
this is an integration test, not a unit test. Each `@Test` drives a full path through
`start()`/`reply()` calls and asserts on the resulting `currentNodeCode`/`status`/rendered
`steps()`:

- `topUpSuccessPath` — the whole "add money" branch end to end, checks `{{payment_link}}` really
  got substituted with a real value.
- `topUpFailureLoopsBackToMenuAndClearsFlagAfterOneUse` — proves the `simulate_failure` flag
  fails exactly once, then a retry succeeds.
- `remindMeOption1IsThreeDaysOut` / `remindMeOption2IsSevenDaysOut` — proves the `REMIND_WHEN`
  business rule computes the right date for each option.
- `optOutPath` — the "don't maintain AMB" branch end to end.
- `unmatchedReplyReShowsSameQuestionWithoutAdvancing` — replies with garbage ("BANANA"), asserts
  the session doesn't move and gets the "Sorry, that wasn't one of the options" prefix, then
  proves a subsequent valid reply still works normally.

Because it's a real integration test, **Postgres must be running** (`docker compose up -d`
first) before `mvn test` will pass.

---

## 10. Running it locally

```bash
docker compose up -d                 # starts Postgres on localhost:5434
./mvnw spring-boot:run                 # starts the app; seeder runs automatically
```

Then:
- `GET http://localhost:8080/api/graph` — JSON graph
- `GET http://localhost:8080/api/graph/ascii` — plain-text tree diagram
- `http://localhost:8080/swagger-ui/index.html` — Swagger UI (from springdoc-openapi)
- `http://localhost:8080/v3/api-docs` — raw OpenAPI JSON

There is currently no REST endpoint to *drive* `WorkflowEngine.start()`/`reply()` directly —
that's "Phase 2" in the build-context doc (conversation/session endpoints), explicitly deferred
until asked for. Today the engine is only exercised by `WorkflowEngineTest`.

---

## 11. Deliberate scope limits — don't be surprised by these

Straight from the build-context doc's guardrails section — these are choices, not gaps someone
forgot about:

- No free-text NLU — a reply is either an exact `EventCode` match or it isn't.
- No `CONDITION` node type (pure business-rule branching with no user input) — flagged as a
  known future gap, not built speculatively.
- `INPUT` node handling is a stub (`handleInputReply()` always accepts and advances) — the seed
  flow has no `INPUT` node, so there was nothing to validate against yet.
- No i18n/translation tables — English only.
- No session timeout → `EXPIRED` logic, even though the enum value exists.
- No generic rule/template-interpretation engine — every business rule and simulated backend
  call is hardcoded per `nodeCode`, on purpose, for this one flow.
- No Phase 2 conversation endpoints (see section 10 above) until explicitly requested.
