# chat-bot-service — `engine` Package Deep Dive

This builds directly on **`CODE_WALKTHROUGH.md` §6** ("The engine package"). That section gives
you the narrative — *what the engine is for and the mental model behind it* (a session walks a
straight line through a graph that itself has cycles). This document goes one level deeper:
**every method in `engine/`, what it does, and why it's written the way it is** — matching
`WorkflowEngine.java` as it stands today (generic `OPTION`+index matching, no per-option enum
ceiling, `ACTION` nodes never render customer-facing text, conclusions frozen on completion).

Read order below is bottom-up — the small DTOs first, then the templating utility, then
`WorkflowEngine` itself, roughly in call order.

---

## 1. The DTOs — what a "turn" looks like from the outside

### `RenderedStep.java`

```java
public record RenderedStep(String nodeCode, NodeType nodeType, String message) {
}
```

One node's fully-rendered output — its `{{placeholders}}` already substituted. Immutable, no
behavior — three fields in, three accessors out.

### `OptionView.java`

```java
public record OptionView(EventCode eventCode, Integer optionIndex, String optionLabel) {
}
```

One selectable option, present only when the engine stopped at a `QUESTION` node waiting for a
reply. `optionIndex` is **null for `YES`/`NO` transitions** — those are matched literally, the
channel echoes back the word itself — and set for the generic `OPTION` event code, which is the
1-based position the channel must echo back verbatim (e.g. `"3"`) to pick that option. There is
no `OPTION_1`/`OPTION_2`/`OPTION_3` split anymore; `eventCode` alone is no longer enough to
identify *which* option was picked on a node with more than one `OPTION` transition, which is
exactly why `optionIndex` exists as its own field instead of being folded into `eventCode`.

### `EngineTurnResult.java`

```java
public record EngineTurnResult(String sessionId, SessionStatus status, List<RenderedStep> steps,
        String currentNodeCode, Long currentNodeId, List<OptionView> options) {
}
```

The full return value of one `start()`/`reply()` call.

- **`steps` is a `List`, not a single `RenderedStep`** — one call can walk through several nodes
  automatically (e.g. `INTRO` → `AMB_MENU` in a single `start()`) before it has to stop.
- **`currentNodeId` alongside `currentNodeCode`** — added so a REST client (or the frame API) has
  a stable numeric id to key off, not just the human-readable code.
- **`options` is always populated, even when empty** — `List.of()`, never `null`, for
  `MESSAGE`/`ACTION`/`INPUT`/`END` nodes, so callers never null-check before `.isEmpty()`.

Deliberately channel-agnostic — not a REST DTO. The `conversation` package's
`ConversationResponse` is the REST-facing shape built on top of this (see
`CODE_WALKTHROUGH.md` §7), specifically so this record can keep evolving independently of
whatever a given channel's contract needs.

---

## 2. `TemplateRenderer.java` — the only "templating engine" in the codebase

```java
public static String render(String template, Map<String, Object> context) {
    if (template == null) {
        return null;
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
        Object value = context.get(matcher.group(1));
        String replacement = value != null ? String.valueOf(value) : matcher.group(0);
        matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
}
```

`PLACEHOLDER` is `\{\{(\w+)\}\}`. For every `{{token}}` match, look it up in `context`; if
present, substitute `String.valueOf(value)`, otherwise leave the match exactly as written.

**Why "leave it literal" instead of throwing or blanking it out**: three different callers use
this under different conditions — `WorkflowEngine.renderStep()` (live session, real context
values), `GraphService.messageSuffix()` (rendering the flow's *definition* for the ASCII diagram,
against an empty context — see below), and `SessionFrameService` (replaying a finished session
against its *final* context). A missing key is never actually an error condition for any of
these callers; it's either "this value hasn't been generated yet in this session" or "this
diagram has no session at all." Throwing would crash the diagram endpoint on any node
referencing a runtime-only value; blanking would silently hide that anything was omitted.

**One change worth flagging**: every seeded node message that used to reference customer-specific
data (`{{customer_name}}`, `{{amb_required}}`, `{{shortfall_amount}}`, `{{amb_charge}}`) has since
been rewritten into generic copy — this is a demo-scale service with no real customer-data lookup,
so those placeholders were removed entirely rather than ever being filled. `GraphService` used to
render the ASCII diagram against a small hardcoded `DEMO_CONTEXT` map specifically to fill those
in; that map is gone now, and the diagram renders against `Map.of()` (empty context). The only
placeholder left in any seeded message is `{{payment_link}}`, which genuinely is only available
inside a live session (an `ACTION` node generates it at runtime) — on the diagram it renders
literal, same as always.

---

## 3. `WorkflowEngine.java` — method by method

The class is a `@Service` with `@RequiredArgsConstructor` injecting six repositories; every
public method is `@Transactional` — a mid-turn failure rolls back the *entire* turn instead of
leaving a session parked half-updated.

### `start(entryCode, customerId, initialContext)`

```java
@Transactional
public EngineTurnResult start(String entryCode, String customerId, Map<String, Object> initialContext) {
    WorkflowEntryPoint entryPoint = workflowEntryPointRepository.findById(entryCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown entry point " + entryCode));
    WorkflowNode startNode = loadNode(entryPoint.getStartNodeId());

    Instant now = Instant.now();
    WorkflowSession session = WorkflowSession.builder()
            .sessionId(generateSessionId())
            .workflowVersionId(entryPoint.getWorkflowVersionId())
            .customerId(customerId)
            .currentNodeId(startNode.getNodeId())
            .status(SessionStatus.ACTIVE)
            .context(initialContext != null ? new LinkedHashMap<>(initialContext) : new LinkedHashMap<>())
            .startedAt(now)
            .lastInteractionAt(now)
            .build();

    return advanceAndFinalize(session, startNode);
}
```

Resolves `entryCode` to a `WorkflowEntryPoint`, builds a brand-new (not yet persisted)
`WorkflowSession`, hands it to `advanceAndFinalize()` — the single place any session actually gets
saved, whether it got there via `start()` or `reply()`. `customerId` is whatever the caller
supplies with no validation or lookup — for this demo it's typically a phone number, entered by
whoever's driving `chat-ui`.

`new LinkedHashMap<>(initialContext)` is a defensive copy — `context` gets mutated heavily later
(`applyNodeChoiceRule`, `simulateAction`, `handleInputReply`), and holding the caller's own map
object would mean those mutations leak back into whatever the caller does with that map
afterward. `LinkedHashMap` (not `HashMap`) preserves insertion order for readable debug/log output
— functionally irrelevant, but nice when eyeballing a session's `context` jsonb column.

### `reply(sessionId, rawInput)`

```java
@Transactional
public EngineTurnResult reply(String sessionId, String rawInput) {
    WorkflowSession session = loadSession(sessionId);
    WorkflowNode current = loadNode(session.getCurrentNodeId());
    session.setLastInteractionAt(Instant.now());

    return switch (current.getNodeType()) {
        case QUESTION -> handleQuestionReply(session, current, rawInput);
        case INPUT -> handleInputReply(session, current, rawInput);
        default -> throw new IllegalStateException(
                "Session " + sessionId + " is not waiting for input (current node type " + current.getNodeType() + ")");
    };
}
```

Loads the session and its current node, stamps `lastInteractionAt` once (before dispatching, so
every successful path updates it without repeating the line in both handlers), dispatches purely
on `nodeType`. Only `QUESTION`/`INPUT` legitimately wait for input — anything else means the
caller double-submitted, retried a stale request, or is replying to an already-finished session,
and that's loud (`IllegalStateException` → `409 Conflict` at the REST layer), not silently
ignored.

### `handleQuestionReply(session, current, rawInput)`

```java
private EngineTurnResult handleQuestionReply(WorkflowSession session, WorkflowNode current, String rawInput) {
    List<WorkflowTransition> options = outgoing(current.getNodeId());
    Optional<WorkflowTransition> match = matchReply(options, rawInput);

    if (match.isEmpty()) {
        logEvent(session, current, EventCode.INVALID_INPUT, rawInputPayload(rawInput));
        workflowSessionRepository.save(session);
        String rendered = TemplateRenderer.render(current.getMessage(), session.getContext());
        String message = "Sorry, that wasn't one of the options. " + rendered;
        return new EngineTurnResult(session.getSessionId(), session.getStatus(),
                List.of(new RenderedStep(current.getNodeCode(), current.getNodeType(), message)),
                current.getNodeCode(), current.getNodeId(), toOptionViews(options));
    }

    WorkflowTransition transition = match.get();
    applyNodeChoiceRule(session, current, transition);
    logEvent(session, current, transition.getEventCode(), rawInputPayload(rawInput));
    WorkflowNode next = loadNode(transition.getToNodeId());
    return advanceAndFinalize(session, next);
}
```

1. Load every outgoing transition from this `QUESTION` node — the valid answers.
2. Try to match the raw reply to one of them via `matchReply()` (below).
3. **No match**: log `INVALID_INPUT`, save the session *unchanged* (`currentNodeId` doesn't
   move), and return a result that re-renders the *same* question with a "Sorry, that wasn't one
   of the options." prefix. Returns directly — does **not** call `advanceAndFinalize()` — because
   nothing actually moved.
4. **Match found**: `applyNodeChoiceRule()` runs first (a node-code-keyed business rule, if this
   node has one — see below), then the event is logged with the real `eventCode`, and control
   passes to `advanceAndFinalize()` with the matched transition's target.

### `matchReply(options, rawInput)`

```java
private Optional<WorkflowTransition> matchReply(List<WorkflowTransition> options, String rawInput) {
    if (rawInput == null) {
        return Optional.empty();
    }
    String trimmed = rawInput.trim();

    if (trimmed.equalsIgnoreCase("YES") || trimmed.equalsIgnoreCase("NO")) {
        EventCode literal = EventCode.valueOf(trimmed.toUpperCase());
        return options.stream().filter(t -> t.getEventCode() == literal).findFirst();
    }

    try {
        int index = Integer.parseInt(trimmed);
        return options.stream()
                .filter(t -> t.getEventCode() == EventCode.OPTION && index == t.getOptionIndex())
                .findFirst();
    } catch (NumberFormatException e) {
        return Optional.empty();
    }
}
```

This is the whole "understanding" the engine does of a reply — still exact-match only, no NLU,
but the *mechanism* changed from the original design. There used to be a fixed `EventCode` enum
value per option (`OPTION_1`, `OPTION_2`, `OPTION_3` — a hard ceiling of 3 choices per node) that
`Enum.valueOf()` parsed the raw reply straight into. That's gone: every multiple-choice reply
that isn't literally `"YES"`/`"NO"` is now parsed as a plain integer and matched against
`optionIndex` on that node's `OPTION`-typed transitions — a `QUESTION` node can now offer any
number of options without ever touching the `EventCode` enum or its DB `CHECK` constraint again.
`NumberFormatException` (garbage input, or an `INPUT`-style free-text-looking string) and "parsed
fine but no `OPTION` transition has that index" both collapse to `Optional.empty()` — the caller
doesn't need to distinguish those two failure reasons, the customer experience is identical
either way.

### `handleInputReply(session, current, rawInput)`

```java
private EngineTurnResult handleInputReply(WorkflowSession session, WorkflowNode current, String rawInput) {
    session.getContext().put(current.getNodeCode(), rawInput);
    switch (current.getNodeCode()) {
        case "CUSTOM_DATE_INPUT" -> session.getContext().put("reminder_date", rawInput);
        case "CHURN_REASON_OTHER" -> session.getContext().put("reason", rawInput);
        default -> {
            // no downstream action reads this node's free text under a specific key
        }
    }
    logEvent(session, current, EventCode.AUTO, rawInputPayload(rawInput));
    WorkflowNode next = follow(current, EventCode.AUTO);
    return advanceAndFinalize(session, next);
}
```

Still a stub in the sense that there's no validation loop — whatever's typed is accepted and
stored, always advancing via the node's single `AUTO` transition. Two things happen to the typed
text: it's always stored under a generic key (`context[nodeCode] = rawInput`, useful for
debugging/audit regardless of what the flow does with it), and — only for the specific `INPUT`
nodes that currently exist in the seeded flow — also copied to the semantic key a downstream node
actually reads (`reminder_date` for a custom date, `reason` for "other, please specify" churn
reasons). `CUSTOM_DATE_INPUT` is currently unreachable in the live graph (the "choose another
date" option was removed from `FUNDS_TIMING` per the latest script), but the `INPUT` node and this
case are left in place rather than deleted, matching how a couple of dormant `ACTION`/`END` nodes
elsewhere are deliberately kept as templates for future reuse.

### `advanceAndFinalize(session, arrivalNode)`

```java
private EngineTurnResult advanceAndFinalize(WorkflowSession session, WorkflowNode arrivalNode) {
    List<RenderedStep> steps = new ArrayList<>();
    WorkflowNode current = arrivalNode;

    while (true) {
        switch (current.getNodeType()) {
            case START -> {
                logEvent(session, current, EventCode.AUTO, null);
                current = follow(current, EventCode.AUTO);
            }
            case MESSAGE -> {
                steps.add(renderStep(current, session));
                logEvent(session, current, EventCode.AUTO, null);
                current = follow(current, EventCode.AUTO);
            }
            case ACTION -> {
                EventCode outcome = simulateAction(session, current);
                logEvent(session, current, outcome, null);
                current = follow(current, outcome);
            }
            case QUESTION -> {
                steps.add(renderStep(current, session));
                session.setCurrentNodeId(current.getNodeId());
                workflowSessionRepository.save(session);
                return new EngineTurnResult(/* ... */);
            }
            case INPUT -> {
                steps.add(renderStep(current, session));
                session.setCurrentNodeId(current.getNodeId());
                workflowSessionRepository.save(session);
                return new EngineTurnResult(/* ... */);
            }
            case END -> {
                steps.add(renderStep(current, session));
                logEvent(session, current, EventCode.AUTO, null);
                session.setConclusionCode(current.getConclusionCode());
                session.setEntryReasonCode((String) session.getContext().get("entry_reason_code"));
                session.setCurrentNodeId(current.getNodeId());
                session.setStatus(SessionStatus.COMPLETED);
                session.setEndedAt(Instant.now());
                workflowSessionRepository.save(session);
                return new EngineTurnResult(/* ... */);
            }
        }
    }
}
```

The engine's core loop — `CODE_WALKTHROUGH.md` §1 points to this as "walks a line." Per node
type:

- **`START`**: no message (pure entry marker), logs and advances via `AUTO`.
- **`MESSAGE`**: renders, logs, advances via `AUTO`. Never stops here.
- **`ACTION`**: runs `simulateAction()` to get `SUCCESS`/`FAILURE`, logs *that* outcome, follows
  it. **Notice it does not call `renderStep()` at all** — an `ACTION` node's `message` column is
  an internal description of the simulated call (e.g. "Calls the payment gateway to create a
  secured funding link"), never customer-facing copy. Earlier this loop *did* render it, and that
  internal description leaked straight into the live chat UI — a real bug, fixed by simply
  dropping the `steps.add(...)` call for this case. `ACTION` steps still show up in a replayed
  frame (§`CODE_WALKTHROUGH.md` §8), just with a null `message`, so the audit trail still explains
  *why* a context value like `payment_link` appeared, without ever surfacing that text to a
  customer.
- **`QUESTION`/`INPUT`**: render, move `currentNodeId`, save, `return` — the two points where the
  engine legitimately has to stop and wait for a `reply()`.
- **`END`**: render, **log an `AUTO` event for itself** (the one node type where nothing else in
  the code path logs an event on its behalf — `QUESTION`/`INPUT` get their event logged later,
  when the *reply* comes in, but nothing ever comes in after an `END`). This was a real,
  previously-undiscovered gap: without it, a session's very last message never appeared in
  `workflow_session_event`, so every completed session's replayed frame was silently missing its
  ending. Then it freezes two extra fields onto the session — `conclusionCode` (copied straight
  from this node's static tag) and `entryReasonCode` (copied out of whatever's currently sitting
  in `context["entry_reason_code"]`, which `applyNodeChoiceRule`'s `AMB_MENU` case wrote in
  earlier — see below) — before marking `COMPLETED` and saving.

**Why `START`/`MESSAGE`/`ACTION` don't return but `QUESTION`/`INPUT`/`END` do**: the entire state
machine, expressed as one fact per node type — does the engine need a human in the loop before it
can keep going? No for the first three (automatic bookkeeping or a simulated call with a
deterministic-enough outcome); yes for the last three (a choice, free text, or "nothing more to
do"). Only the session's *final* resting state after a whole multi-node walk gets written to the
database — one `UPDATE`, not one per node, and if something threw partway through, the whole
`@Transactional` call rolls back cleanly instead of leaving `currentNodeId` pointing at some
intermediate node it should have already walked past.

### `renderStep(node, session)`

```java
private RenderedStep renderStep(WorkflowNode node, WorkflowSession session) {
    return new RenderedStep(node.getNodeCode(), node.getNodeType(),
            TemplateRenderer.render(node.getMessage(), session.getContext()));
}
```

Called from four places in `advanceAndFinalize()` (`MESSAGE`/`QUESTION`/`INPUT`/`END` — not
`ACTION`, per above) plus once more in `handleQuestionReply()`'s invalid-reply path. Pulled out so
"how do you turn a node into a `RenderedStep`" is defined exactly once.

### `applyNodeChoiceRule(session, node, transition)`

```java
private void applyNodeChoiceRule(WorkflowSession session, WorkflowNode node, WorkflowTransition transition) {
    switch (node.getNodeCode()) {
        case "AMB_MENU" -> session.getContext().put("entry_reason_code", transition.getEntryReasonCode());
        case "FUNDS_TIMING" -> applyFundsTimingRule(session, transition.getOptionIndex());
        case "CASH_FLOW_MENU" -> session.getContext().put("assistance_type", transition.getOptionLabel());
        case "CHURN_REASON_MENU" -> {
            if (transition.getOptionIndex() != null && transition.getOptionIndex() <= 4) {
                session.getContext().put("reason", transition.getOptionLabel());
            }
        }
        default -> {
            // no business rule for this node's choice
        }
    }
}
```

This replaced what used to be a single `if ("REMIND_WHEN".equals(...))` special case with a real
(still tiny, still node-code-keyed, still explicitly *not* a generic rule engine) `switch` — the
flow grew from one branch needing a business rule to four:

- **`AMB_MENU`** — the newest case, added specifically to support session conclusions (§9 of
  `CODE_WALKTHROUGH.md`). Every `AMB_MENU` option is tagged (in the seeder) with which top-level
  path it represents; this just copies that tag into context the moment the choice is made, ready
  to be frozen onto the session if/when it reaches an `END` node.
- **`FUNDS_TIMING`** — delegates to `applyFundsTimingRule()` (below), the direct descendant of the
  original `REMIND_WHEN` rule.
- **`CASH_FLOW_MENU`** — records which assistance type was picked (its label, verbatim) into
  context, read later by the `ROUTE_TO_EXECUTIVE` action's (simulated) request.
- **`CHURN_REASON_MENU`** — records the churn reason, but *only* for options 1-4 (fixed labels);
  option 5 ("Other, please specify") has no fixed label yet — the `CHURN_REASON_OTHER` `INPUT`
  node fills in `reason` once the customer actually types it (see `handleInputReply()` above).

Still exactly the scope call this project has made from the start: one small `switch`, not a
generic "attach a rule to any node" mechanism, because there is a small, enumerable set of
business rules and no requirement yet for an author to define new ones without a code change.

### `applyFundsTimingRule(session, optionIndex)`

```java
private void applyFundsTimingRule(WorkflowSession session, Integer optionIndex) {
    if (optionIndex == null) {
        return;
    }
    LocalDate reminderDate = switch (optionIndex) {
        case 1 -> LocalDate.now(ZoneOffset.UTC).plusDays(3);
        case 2 -> LocalDate.now(ZoneOffset.UTC).plusDays(7);
        case 3 -> LocalDate.now(ZoneOffset.UTC).plusDays(15);
        default -> throw new IllegalStateException("Unexpected FUNDS_TIMING option " + optionIndex);
    };
    session.getContext().put("reminder_date", reminderDate.toString());
}
```

`FUNDS_TIMING` offers exactly three timed choices ("Within 3/7/15 days"); this turns whichever
was picked into an actual calendar date, computed in UTC (deterministic regardless of server
timezone/DST — same reasoning as pinning the JVM's default timezone at startup), written to
`context["reminder_date"]` for `END_FUNDS_REMINDER`'s message to reference later. `default ->
throw` rather than silently no-op-ing: this method is only reachable once `matchReply()` has
already confirmed the index came from a real `OPTION` transition on this exact node, so hitting
the default branch would mean the seed data itself grew a 4th `FUNDS_TIMING` option without this
rule being updated — worth failing loudly on, not silently leaving `reminder_date` unset. (A 4th
option, "choose another date" → a free-text `INPUT` node, existed at one point and was removed
from the live graph per the latest script; this method's `default` case is exactly what would
catch a future re-introduction of it without a matching engine update.)

### `simulateAction(session, node)`

```java
private EventCode simulateAction(WorkflowSession session, WorkflowNode node) {
    WorkflowActionConfig config = workflowActionConfigRepository.findById(node.getNodeId())
            .orElseThrow(() -> new IllegalStateException("No action config for node " + node.getNodeCode()));
    Map<String, Object> context = session.getContext();

    if (Boolean.TRUE.equals(context.get("simulate_failure"))) {
        context.remove("simulate_failure");
        return config.getOnFailureEvent();
    }

    switch (node.getNodeCode()) {
        case "GENERATE_FUND_LINK" -> context.put("payment_link",
                "https://pay.hdfcbank.example/fund/" + session.getSessionId());
        case "SCHEDULE_FUNDS_REMINDER" -> context.put("reminder_id", "RMD-" + session.getSessionId());
        case "ROUTE_TO_EXECUTIVE" -> context.put("executive_handoff_id", "EXE-" + session.getSessionId());
        case "CONVERT_SALARY_ACCOUNT" -> context.put("salary_conversion_id", "SAL-" + session.getSessionId());
        case "LOG_CALLBACK_REQUEST" -> context.put("callback_request_id", "CB-" + session.getSessionId());
        default -> {
            // no context side-effect needed (e.g. CHECK_FUNDING_STATUS)
        }
    }
    return config.getOnSuccessEvent();
}
```

There's no real payment gateway or CRM behind any of this. Five `ACTION` nodes now have a
hardcoded side effect (up from the original two) as the flow grew branches: `GENERATE_FUND_LINK`
writes the (fake) payment link that `FUND_TODAY_ACK`'s message goes on to reference via
`{{payment_link}}`; the other four write a fake reference id into context, mirroring the same
`"PREFIX-" + sessionId` pattern. `CHECK_FUNDING_STATUS` is one of a couple of `ACTION` nodes kept
in the schema but currently unreachable from the live graph (a template for a future real
24-hour-later recheck, which this engine — with no real async wait — can't model as a live graph
edge in the same turn as an acknowledgment); it falls to `default`, no side effect.

`simulate_failure` is still a one-shot context flag, unrelated to any real reliability
simulation: set it, the *next* `ACTION` node fails and the flag is consumed, so a retry
immediately after succeeds. Exists purely so a test (or manual run) can deterministically exercise
every `FAILURE → AMB_MENU` loop-back path without flaky random behavior.

### `rawInputPayload`, `toOptionViews`, `logEvent`, `loadNode`/`loadSession`, `outgoing`, `follow`, `generateSessionId`

Unchanged in shape and reasoning from the original design — small, single-purpose helpers:

- `rawInputPayload(rawInput)` wraps the customer's literal text into the `payload` jsonb column
  under key `"rawInput"`, used only for reply-driven `logEvent()` calls (auto/action-outcome
  events pass `null`).
- `toOptionViews(transitions)` maps persistence-layer `WorkflowTransition` entities to the
  engine's own `OptionView` DTO — the boundary that keeps `EngineTurnResult` from ever leaking a
  JPA entity/lazy proxy to a caller.
- `logEvent(...)` writes one `workflow_session_event` row per node hop/event, called from nearly
  every branch — the audit trail is meant to be complete, not just the parts a human explicitly
  triggered. (See `CODE_WALKTHROUGH.md` §8/§9 for what now reads this log back.)
- `loadNode()` throws `IllegalStateException` (a `nodeId` reaching it is never caller-supplied —
  it always comes from data already in the DB; a miss means the seed data itself is broken).
  `loadSession()` throws `IllegalArgumentException` (a caller-supplied `sessionId` might
  legitimately not exist — typo, stale link). Different exceptions on purpose, mapped to
  different HTTP statuses by `GlobalExceptionHandler` (`409` vs. `404`).
- `outgoing(nodeId)` / `follow(node, eventCode)` are the one-hop primitives everything else is
  built from — `follow()` throws rather than returning `Optional` for the same "seed data must be
  internally consistent" reasoning as `loadNode()`.
- `generateSessionId()` — `"S" + 10 hex chars from a random UUID` — unchanged.

---

## 4. How it all connects — one call traced end to end

`reply(sessionId, "5")` against a session sitting at `AMB_MENU`:

1. `reply()` loads the session and the `AMB_MENU` node, stamps `lastInteractionAt`, sees
   `nodeType == QUESTION`, dispatches to `handleQuestionReply()`.
2. `handleQuestionReply()` loads `AMB_MENU`'s 5 outgoing transitions via `outgoing()`. `"5"` isn't
   `"YES"`/`"NO"`, so `matchReply()` parses it as `5` and finds the `OPTION` transition with
   `optionIndex == 5` → "I no longer actively use this account" → `CHURN_ACK` (600).
3. `applyNodeChoiceRule()` fires its `"AMB_MENU"` case: `context["entry_reason_code"] =
   "CHURN_RISK"` (this transition's tag).
4. The event is logged (`eventCode = OPTION`, `payload = {"rawInput": "5"}`), and
   `advanceAndFinalize(session, CHURN_ACK)` runs.
5. `advanceAndFinalize()` enters its loop at `CHURN_ACK` (`MESSAGE` — renders, logs, auto-advances)
   → `CHURN_REASON_MENU` (`QUESTION` — renders, saves, returns). One `EngineTurnResult`: two
   rendered steps, `CHURN_REASON_MENU`'s 5 options, session still `ACTIVE`.

If the customer had instead eventually answered their way to an `END` node, step 5's loop would
keep going past any further `MESSAGE`/`ACTION` nodes, hit the `END` case, log its own arrival
event, freeze `conclusionCode`/`entryReasonCode` onto the session, and mark it `COMPLETED` — the
last state a `GET /api/sessions/{id}/frame` call would later replay in full.
