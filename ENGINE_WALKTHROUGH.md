# chat-bot-service — `engine` Package Deep Dive

This builds directly on **`CODE_WALKTHROUGH.md` §6** ("The engine package — the heart of the
service"). That section gives you the narrative — *what the engine is for and the mental model
behind it* (a session walks a straight line through a graph that itself has cycles). This
document assumes you've read that, and goes one level deeper: **every method in
`engine/`, what it does, and why it's written the way it is.**

Read order below is bottom-up — the small DTOs first (what the engine hands back), then the one
utility class, then `WorkflowEngine` itself, method by method, roughly in call order.

---

## 1. The DTOs — what a "turn" looks like from the outside

### `RenderedStep.java`

```java
public record RenderedStep(String nodeCode, NodeType nodeType, String message) {
}
```

One node's fully-rendered output — its `{{placeholders}}` already substituted with real values.
It's a `record`, not a class, because it's pure, immutable data with no behavior: three fields
in, three accessors out, nothing else. There's no builder, no setters — once the engine renders
a step it never needs to be mutated again.

### `OptionView.java`

```java
public record OptionView(EventCode eventCode, String optionLabel) {
}
```

One selectable option, present only when the engine stopped at a node that's actually waiting
for a choice (a `QUESTION`). `eventCode` is what the client must send back in `reply()` to pick
this option (e.g. `OPTION_1`); `optionLabel` is the human-readable button text (e.g. "Add
Rs.4,500 now"). Splitting these into two fields — rather than making the client parse the label
back into an event code — keeps the reply contract exact-match and unambiguous: the caller
echoes `eventCode` verbatim, no string-matching on customer-facing copy required.

### `EngineTurnResult.java`

```java
public record EngineTurnResult(String sessionId, SessionStatus status, List<RenderedStep> steps,
        String currentNodeCode, List<OptionView> options) {
}
```

The full return value of one `start()`/`reply()` call. Two fields are worth pausing on:

- **`steps` is a `List`, not a single `RenderedStep`** — because one call can legitimately walk
  through several nodes automatically (e.g. `WELCOME` → `AGENDA` → `AMB_MENU` in a single
  `start()`) before it has to stop. The caller needs *all* of that rendered output, in order, not
  just the last one.
- **`options` is always populated, even when it's empty** — a `QUESTION` node returns its
  outgoing choices, but `MESSAGE`/`ACTION`/`INPUT`/`END` nodes return `List.of()` rather than
  `null`. This means calling code never has to null-check before checking `.isEmpty()` — a small
  thing, but it removes a whole class of NPE bugs at every call site.

The class-level Javadoc explicitly calls this "deliberately channel-agnostic — not a REST DTO."
That's a real constraint, not a stylistic note: nothing in this record knows about HTTP status
codes, JSON field naming conventions, or WhatsApp message formats. When the conversation REST API
was added on top of this engine, it introduced its own `ConversationResponse`/`StepResponse`/
`OptionResponse` records in the `conversation` package specifically so this record could keep
evolving independently of whatever shape a specific channel's contract needs.

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

**What it does**: `PLACEHOLDER` is the regex `\{\{(\w+)\}\}` — matches `{{token}}` and captures
`token`. For every match found in `template`, it looks the token up in `context`; if present, the
match is replaced with `String.valueOf(value)`, otherwise the match is left exactly as it was
(`matcher.group(0)`, the whole `{{token}}` literal). `Matcher.appendReplacement`/`appendTail` is
the standard idiom for "walk every regex match and rebuild the string with substitutions" — it's
more efficient than repeated `String.replace()` calls because it builds the result in one pass
instead of re-scanning the string once per token.

**Why "leave it literal" instead of throwing or blanking it out**: this is the one design choice
worth remembering. Two different callers use this method under very different conditions:

1. `WorkflowEngine.renderStep()` — rendering a node's message *inside a live session*, where
   `context` has real values (`customer_name`, a generated `payment_link`, etc.).
2. `GraphService.messageSuffix()` (in the `graph` package, see `CODE_WALKTHROUGH.md` §7) —
   rendering the *same* node messages for the `/api/graph/ascii` diagram, using a small hardcoded
   `DEMO_CONTEXT` that only has a handful of keys. A key like `{{payment_link}}` is genuinely
   never going to exist there — it's only generated at runtime by an `ACTION` node mid-session.

If missing keys threw, the ASCII diagram endpoint would crash on any node message referencing a
runtime-only value. If missing keys were blanked out, the diagram would silently show `"Here's
your link: "` with no indication anything was omitted. Leaving the token literal
(`"Here's your link: {{payment_link}}"`) is honest about what's missing without breaking either
caller — it degrades gracefully for the diagram use case and never fires at all in the live
session case (because by the time a node needing `payment_link` is rendered, the `ACTION` node
that generates it has already run).

**Why a `final` class with a private constructor and static method**: this is a pure function
with no state — there's nothing to instantiate. The private constructor is the standard Java
idiom for "this class isn't meant to be instantiated, ever," and `final` closes off subclassing
as an escape hatch around that.

---

## 3. `WorkflowEngine.java` — method by method

Quick orientation before the methods: the class is a `@Service` with `@RequiredArgsConstructor`
injecting six repositories, and every public method is `@Transactional`. That last point matters
more than it looks — `start()` and `reply()` can each touch four or five tables (session,
session event, node, transition, action config) across several repository calls; wrapping the
whole turn in one transaction means a mid-turn failure (say, the `ACTION` node's simulated call
throwing) rolls back the *entire* turn instead of leaving the session parked in a half-updated
state.

### `start(entryCode, customerId, initialContext)` — line 46

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

**What it does**: resolves the `entryCode` (e.g. `AMB_SHORTFALL_Q2`, the short code that would
come from a WhatsApp campaign link) to a `WorkflowEntryPoint` row, which pins down *which
workflow version* and *which node* this conversation begins at. Builds a brand-new
`WorkflowSession`, not yet persisted, and hands it — along with the resolved start node — to
`advanceAndFinalize()`, which does the actual walking and is the method that eventually saves it.

**Why it doesn't save the session itself**: notice `workflowSessionRepository.save(...)` never
appears in this method. That's deliberate — `advanceAndFinalize()` is the single place a session
gets persisted, regardless of whether it got there via `start()` or `reply()`. Having one save
point instead of two means there's exactly one place to reason about "when does a session's state
actually hit the database," rather than two call paths that could drift out of sync.

**Why `new LinkedHashMap<>(initialContext)` instead of using the map the caller passed in
directly**: defensive copy. `WorkflowSession.context` gets mutated later (e.g.
`simulateAction()` writes `payment_link` into it, `applyRemindWhenRule()` writes
`reminder_date`). If the engine held onto the caller's own map object, mutating it would be
mutating something the caller might still hold a reference to and reuse elsewhere — a classic
shared-mutable-state bug. Copying breaks that link. `LinkedHashMap` specifically (not
`HashMap`) preserves insertion order, which matters for nothing functionally here but makes
`show-sql`/debug logs and any future "dump the context" tooling deterministic and readable
instead of hash-order scrambled.

**Why `IllegalArgumentException` for an unknown entry code**: this is the engine's chosen
convention for "the caller gave me an identifier that doesn't resolve to anything" — see the
`web.GlobalExceptionHandler` in the `conversation` package, which maps this specific exception
type to an HTTP 404 for the REST layer. The engine itself doesn't know about HTTP; it just picks
a standard JDK exception whose *meaning* ("bad argument, not found") a caller in any channel can
reasonably interpret.

### `reply(sessionId, rawInput)` — line 68

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

**What it does**: loads the session and whatever node it's currently parked at, stamps
`lastInteractionAt`, then dispatches purely on that node's `nodeType`. Only two types make sense
to "reply" to — `QUESTION` (pick one of several options) and `INPUT` (free text) — everything
else falls to the `default` branch and throws.

**Why the `default` branch throws instead of silently ignoring the call**: a `MESSAGE`, `ACTION`,
or `END` node was never waiting for input in the first place — the engine auto-advances through
those inside `advanceAndFinalize()` without ever returning control to the caller at them. If
`reply()` gets called against a session parked at one of those, that's a caller bug (double-
submitting a reply after a session already ended, a stale client retry, etc.), and it should be
loud, not silently swallowed — which is exactly the kind of "state conflict" the REST layer maps
to `409 Conflict` rather than either succeeding unexpectedly or returning a generic `500`.

**Why `lastInteractionAt` is stamped here, before dispatching**, rather than inside each handler:
every successful path through `reply()` should update it, and doing it once at the top avoids
repeating the same line in both `handleQuestionReply()` and `handleInputReply()`.

### `handleQuestionReply(session, current, rawInput)` — line 81

```java
private EngineTurnResult handleQuestionReply(WorkflowSession session, WorkflowNode current, String rawInput) {
    List<WorkflowTransition> options = outgoing(current.getNodeId());
    Optional<EventCode> parsed = parseEventCode(rawInput);
    Optional<WorkflowTransition> match = parsed
            .flatMap(ec -> options.stream().filter(t -> t.getEventCode() == ec).findFirst());

    if (match.isEmpty()) {
        logEvent(session, current, EventCode.INVALID_INPUT, rawInputPayload(rawInput));
        workflowSessionRepository.save(session);
        String rendered = TemplateRenderer.render(current.getMessage(), session.getContext());
        String message = "Sorry, that wasn't one of the options. " + rendered;
        return new EngineTurnResult(session.getSessionId(), session.getStatus(),
                List.of(new RenderedStep(current.getNodeCode(), current.getNodeType(), message)),
                current.getNodeCode(), toOptionViews(options));
    }

    EventCode eventCode = parsed.get();
    if ("REMIND_WHEN".equals(current.getNodeCode())) {
        applyRemindWhenRule(session, eventCode);
    }
    logEvent(session, current, eventCode, rawInputPayload(rawInput));
    WorkflowNode next = loadNode(match.get().getToNodeId());
    return advanceAndFinalize(session, next);
}
```

**What it does**, step by step:
1. Load every possible outgoing transition from this `QUESTION` node — these *are* the valid
   answers.
2. Try to parse the raw customer text into an `EventCode` (see `parseEventCode()` below) and,
   only if that parse succeeded, look for a transition whose `eventCode` matches it.
3. **No match** (either the parse failed, or it parsed to a real `EventCode` that just isn't one
   of *this* node's options): log an `INVALID_INPUT` audit event, save the session as-is (its
   `currentNodeId` is untouched), and return a result that re-renders the *same* question with a
   "Sorry, that wasn't one of the options." prefix. Crucially, this returns directly — it does
   **not** call `advanceAndFinalize()` — because the session hasn't actually moved anywhere.
4. **Match found**: one flow-specific special case fires first — if this node's code is literally
   `"REMIND_WHEN"`, `applyRemindWhenRule()` runs before anything else, because it needs to know
   *which option* was chosen to compute a date. Then the event is logged (this time with the real
   `eventCode`, not `INVALID_INPUT`), and control passes to `advanceAndFinalize()` with the
   matched transition's target node — which is what actually moves `currentNodeId` forward and
   persists it.

**Why parsing and matching are two separate steps** (`parseEventCode` then a stream filter)
instead of one combined lookup: it cleanly separates two different failure reasons that both
collapse to the same user-facing outcome. "You typed something that isn't a recognized event
code at all" (parse failure) and "you typed a real event code, but it's not one of the choices
*this specific question* offers" (parse succeeded, no matching transition) are different bugs to
debug later, but the customer experience for both is identical — re-show the question. Keeping
them as two `Optional`s chained with `flatMap` means that logic reads as one sentence ("parse it,
then find a matching option") without a nested `if/else` pyramid.

**Why the invalid-reply path calls `TemplateRenderer.render()` itself** instead of reusing
`renderStep()`: `renderStep()` (see below) always renders the node's own unmodified message.
Here the message needs a prefix stitched on ("Sorry, that wasn't one of the options. " + the
normal rendered text), so it renders directly and builds the `RenderedStep` inline rather than
forcing `renderStep()` to grow an optional-prefix parameter for one caller.

**Why `"REMIND_WHEN".equals(current.getNodeCode())`** — a literal string compare on a node code —
**rather than a generic mechanism**: this is the same "hardcode it, don't build a rule engine"
choice `CODE_WALKTHROUGH.md` calls out repeatedly. There is exactly one flow-specific business
rule in the entire seeded flow (translate "in 3 days"/"next week" into an actual date), so it's
written as the smallest possible thing that could work: one `if`, keyed directly off the one node
code where it applies. A generic "attach a rule to any node" mechanism would be speculative
infrastructure for a requirement that doesn't exist yet.

### `handleInputReply(session, current, rawInput)` — line 106

```java
private EngineTurnResult handleInputReply(WorkflowSession session, WorkflowNode current, String rawInput) {
    // Stub only - contract calls for a validation loop but no INPUT node exists in the
    // seed flow to exercise it (build context doc section 3). Always accepts and advances.
    session.getContext().put(current.getNodeCode(), rawInput);
    logEvent(session, current, EventCode.AUTO, rawInputPayload(rawInput));
    WorkflowNode next = follow(current, EventCode.AUTO);
    return advanceAndFinalize(session, next);
}
```

**What it does**: stores whatever free text the customer typed into the session's `context` map,
keyed by the current node's code, logs it as an `AUTO` event, and advances via the node's single
`AUTO` transition (an `INPUT` node, by construction, only ever has one outgoing transition — there's
nothing to branch on since there's no validation).

**Why it's a stub, and why that's flagged in a comment rather than fixed**: the seed flow (see
`CODE_WALKTHROUGH.md` §8) has zero `INPUT`-typed nodes — every question in the AMB-shortfall flow
is a multiple-choice `QUESTION`. There is currently nothing to validate free text *against* (no
format rules, no "is this a valid amount" check), so writing a validation loop here would be
building against a contract that has no real example to verify it against yet. The comment is
there specifically so a future reader doesn't mistake "always accepts" for an oversight — it's
recorded as a known, deliberate gap (also listed in `CODE_WALKTHROUGH.md` §11's scope-limits
list).

### `advanceAndFinalize(session, arrivalNode)` — line 115

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
                steps.add(renderStep(current, session));
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

This is the engine's core loop, and it's the method `CODE_WALKTHROUGH.md` §1 points to as "walks
a line" — everything else in the class exists to feed a starting node into this loop correctly.

**What it does, per node type**:
- **`START`**: no message to render (it's a pure entry marker), just logs and immediately
  advances via its single `AUTO` transition.
- **`MESSAGE`**: renders its text, logs it, advances via `AUTO`. Never stops here.
- **`ACTION`**: runs `simulateAction()` first to determine `SUCCESS` or `FAILURE`, *then* renders
  the node's own message (its message is typically a "Generating your link..."-style line, shown
  regardless of outcome), logs whichever outcome actually happened, and follows *that* outcome's
  transition — meaning `ACTION` nodes can branch, unlike `START`/`MESSAGE`.
- **`QUESTION`** / **`INPUT`**: render, move `currentNodeId` to this node, **save the session**,
  and `return` — this is where the loop legitimately has to stop and wait for the caller to send
  a `reply()`.
- **`END`**: render, move `currentNodeId` here too, but additionally mark the session
  `COMPLETED` and stamp `endedAt`, save, and `return`. Terminal — nothing ever transitions out of
  an `END` node.

**Why `START`/`MESSAGE`/`ACTION` don't return but `QUESTION`/`INPUT`/`END` do**: this *is* the
engine's entire state machine, expressed as a single fact per node type — "does the engine need
a human in the loop before it can keep going?" For `START`/`MESSAGE`/`ACTION` the answer is no
(they're either automatic bookkeeping or a simulated system call with a deterministic-enough
outcome), so the loop just keeps consuming nodes. For `QUESTION`/`INPUT`/`END` the answer is yes
(a choice, free text, or "there's nothing more to do") — so those three, and only those three,
are where the method can exit.

**Why the session is only saved inside the three `return` branches, not on every loop
iteration**: an entire multi-node walk (`START` → `WELCOME` → `AGENDA` → `AMB_MENU`, say) happens
in memory against one `WorkflowSession` object, and only the *final* resting state gets written
to the database — one `UPDATE`, not four. This is both a performance choice (fewer round trips)
and a correctness one: if something threw partway through the loop, the whole `@Transactional`
call rolls back cleanly rather than leaving a session's `currentNodeId` pointing at some
intermediate `MESSAGE` node it should have already walked past.

**Why this is a `switch` over an enum rather than, say, polymorphic node subclasses** (a
`WorkflowNode` base class with a `MESSAGE`/`QUESTION`/etc. subclass each implementing its own
"handle" method): six node types, six fixed behaviors, all owned by the engine — there's no
plugin/extensibility requirement calling for polymorphism here, and `WorkflowNode` is a plain JPA
`@Entity` (Hibernate needs one concrete class to map to one table row, not a class hierarchy).
The exhaustive `switch` over the `NodeType` enum gets the same "compiler tells you if you forget
a case" safety a polymorphic dispatch would, with far less machinery.

### `renderStep(node, session)` — line 163

```java
private RenderedStep renderStep(WorkflowNode node, WorkflowSession session) {
    return new RenderedStep(node.getNodeCode(), node.getNodeType(),
            TemplateRenderer.render(node.getMessage(), session.getContext()));
}
```

A three-line helper, but it's called from five different places inside `advanceAndFinalize()`
and once more in `handleQuestionReply()`'s invalid-reply path — pulling it out means "how do you
turn a node into a `RenderedStep`" is defined exactly once, so if rendering logic ever needs to
change (e.g. adding a "was this option list also included" field), there's a single call site to
update.

### `applyRemindWhenRule(session, chosenOption)` — line 172

```java
private void applyRemindWhenRule(WorkflowSession session, EventCode chosenOption) {
    LocalDate reminderDate = switch (chosenOption) {
        case OPTION_1 -> LocalDate.now(ZoneOffset.UTC).plusDays(3);
        case OPTION_2 -> LocalDate.now(ZoneOffset.UTC).plusDays(7);
        default -> throw new IllegalStateException("Unexpected REMIND_WHEN option " + chosenOption);
    };
    session.getContext().put("reminder_date", reminderDate.toString());
}
```

**What it does**: `REMIND_WHEN` offers exactly two choices ("In 3 days" / "Next week"); this
turns whichever one was picked into an actual calendar date (`today + 3` or `today + 7`, computed
in UTC) and writes it into `session.context["reminder_date"]` so the later `REMINDER_SET`
message's `{{reminder_date}}` placeholder has something real to substitute.

**Why `default -> throw`** rather than silently doing nothing for any other `EventCode`: this
method is only ever called from the one call site in `handleQuestionReply()` that's already
confirmed `current.getNodeCode().equals("REMIND_WHEN")` and that `chosenOption` matched one of
this node's *actual* outgoing transitions — meaning, by construction, it can only legitimately be
`OPTION_1` or `OPTION_2`, because those are the only two transitions the seed data gives that
node. Hitting the `default` branch here would mean the seed data itself is inconsistent (a third
option was added to `REMIND_WHEN` in the database without updating this rule) — a bug worth
failing loudly on immediately, not silently ignoring and leaving `reminder_date` unset.

**Why UTC specifically**: dates computed from the JVM's local timezone would shift by a day
around midnight depending on server timezone/DST — using a fixed `ZoneOffset.UTC` makes "3 days
from now" deterministic regardless of what machine or timezone the app happens to be deployed to
(this is the same reasoning behind pinning the JVM's default timezone to UTC at startup, per
`CODE_WALKTHROUGH.md` §3's note on `ChatBotServiceApplication`).

### `simulateAction(session, node)` — line 186

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
        case "GENERATE_LINK" ->
                context.put("payment_link", "https://pay.hdfcbank.example/topup/" + session.getSessionId());
        case "SET_REMINDER" -> context.put("reminder_id", "RMD-" + session.getSessionId());
        default -> {
            // no context side-effect needed (e.g. RECORD_OPT_OUT)
        }
    }
    return config.getOnSuccessEvent();
}
```

**What it does**: there's no real payment gateway or CRM behind this POC, so every `ACTION` node
"calls" is faked here. It loads the node's `WorkflowActionConfig` (which just holds which
`EventCode` fires on success vs. failure for *this* node), then:
1. If the session's context has `simulate_failure = true` set, consume that flag (remove it —
   one-shot) and return the configured failure event.
2. Otherwise, apply a hardcoded side effect keyed on the node's code — `GENERATE_LINK` writes a
   fake `payment_link` URL built from the session id, `SET_REMINDER` writes a fake `reminder_id`
   — and return the configured success event.

**Why `simulate_failure` is a context flag rather than, say, a fixed failure rate or an actual
mocked external call**: this exists purely so a test (or a manual demo) can deterministically
force the `FAILURE → AMB_MENU` loop-back paths (see `CODE_WALKTHROUGH.md`'s flow description —
every action's failure branch loops back to the main menu) without needing a real backend or
random/flaky test behavior. Consuming the flag after one use (rather than leaving it set) means a
test can prove both halves of the story in one session: "the first attempt fails, and the retry
after that succeeds" — see `WorkflowEngineTest.topUpFailureLoopsBackToMenuAndClearsFlagAfterOneUse`.

**Why the side effects are a `nodeCode`-keyed `switch` instead of being driven generically by
`WorkflowActionConfig.requestTemplate`/`responseMapping`** (both of which exist as columns on
that entity, per `CODE_WALKTHROUGH.md` §4, but aren't interpreted here): building a generic
template-driven "simulate any HTTP call from a config row" interpreter is real infrastructure for
a requirement — actually calling out to arbitrary backends — this project explicitly doesn't have
yet. Three nodes, three hardcoded side effects (one of which is a no-op) is the entire
requirement today; the config columns are left in the schema as the seam where that generic
behavior would plug in later, without the engine speculatively building the interpreter now.

### `parseEventCode(rawInput)` — line 207

```java
private Optional<EventCode> parseEventCode(String rawInput) {
    if (rawInput == null) {
        return Optional.empty();
    }
    try {
        return Optional.of(EventCode.valueOf(rawInput.trim().toUpperCase()));
    } catch (IllegalArgumentException e) {
        return Optional.empty();
    }
}
```

**What it does**: this is, quite literally, the only place in the entire codebase that does
anything resembling "understanding" a customer's reply. It trims whitespace, upper-cases it, and
tries an exact match against the `EventCode` enum (`AUTO`, `OPTION_1`, `YES`, etc.) via
`Enum.valueOf()`. Any input that doesn't exactly match one of those names — "yes please", "1",
"add money" — fails to parse and becomes `Optional.empty()`.

**Why catch `IllegalArgumentException` from `valueOf()` instead of checking membership some other
way** (e.g. iterating `EventCode.values()` and comparing): `Enum.valueOf(Class, String)` is the
standard JDK mechanism for "string to enum, or fail" — reaching for it and catching its documented
failure mode is more idiomatic and less code than hand-rolling a lookup, and there's no
performance concern at this scale to justify avoiding the exception path.

**Why `Optional<EventCode>` as the return type instead of returning `null` or throwing**: this
method's *caller* (`handleQuestionReply()`) needs to distinguish "couldn't parse anything
recognizable" from "parsed fine, just isn't one of this particular node's options" — chaining
`Optional.flatMap()` (as shown in `handleQuestionReply()` above) expresses "if this succeeded,
then also try this" as one readable line, which a `null` check or an exception would make
noisier. This is exactly why "reply matching is exact-string-to-EventCode only, no NLU" (per
`CODE_WALKTHROUGH.md` §1) is such a clean, small method — there is genuinely no natural language
understanding here, just an enum parse with a safety net.

### `rawInputPayload(rawInput)` — line 218

```java
private Map<String, Object> rawInputPayload(String rawInput) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rawInput", rawInput);
    return payload;
}
```

A one-line wrapper, used only to build the `payload` argument for `logEvent()` calls that came
from a customer reply (as opposed to `AUTO`/action-outcome events, which pass `null` for
payload). Its entire job is giving the raw text a named key (`"rawInput"`) inside the
`WorkflowSessionEvent.payload` jsonb column, so a later reader of the audit log (see
`CODE_WALKTHROUGH.md` §4's `WorkflowSessionEvent` section) can tell *what the customer actually
typed*, not just which `EventCode` it resolved to — useful specifically for diagnosing
`INVALID_INPUT` events, where knowing the exact garbage input is the point.

### `toOptionViews(transitions)` — line 224

```java
private List<OptionView> toOptionViews(List<WorkflowTransition> transitions) {
    return transitions.stream().map(t -> new OptionView(t.getEventCode(), t.getOptionLabel())).toList();
}
```

A straight mapping from the persistence-layer `WorkflowTransition` entities to the engine's own
public `OptionView` DTO. Small, but it's the boundary that keeps `EngineTurnResult` (and
everything downstream of it) from ever holding a reference to a JPA entity — callers of the
engine get plain, detached data, not lazily-loaded Hibernate proxies that would break once the
transaction/session closes.

### `logEvent(session, node, eventCode, payload)` — line 228

```java
private void logEvent(WorkflowSession session, WorkflowNode node, EventCode eventCode, Map<String, Object> payload) {
    workflowSessionEventRepository.save(WorkflowSessionEvent.builder()
            .sessionId(session.getSessionId())
            .nodeId(node.getNodeId())
            .eventCode(eventCode)
            .payload(payload)
            .createdAt(Instant.now())
            .build());
}
```

Writes one row to the append-only `workflow_session_event` audit table (`CODE_WALKTHROUGH.md`
§4) every time the engine visits a node or resolves an event — including automatic ones like
`AUTO` and action outcomes, not just customer replies. It's called from nearly every branch of
`advanceAndFinalize()` and both `handle*Reply()` methods, which is intentional: the audit trail
is meant to be a complete record of everything the engine did during a turn, not just the parts a
human explicitly triggered.

### `loadNode(nodeId)` / `loadSession(sessionId)` — lines 238, 243

```java
private WorkflowNode loadNode(Long nodeId) {
    return workflowNodeRepository.findById(nodeId)
            .orElseThrow(() -> new IllegalStateException("Node " + nodeId + " not found"));
}

private WorkflowSession loadSession(String sessionId) {
    return workflowSessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown session " + sessionId));
}
```

Both are thin wrappers around `findById(...).orElseThrow(...)`, but notice they throw
**different** exception types on purpose: `loadSession()` throws `IllegalArgumentException`
because an unknown `sessionId` is a caller-supplied identifier that might legitimately not exist
(typo, expired session, stale link) — a 404-shaped problem. `loadNode()` throws
`IllegalStateException` because a `nodeId` reaching this method is *never* caller-supplied — it
always comes from data already inside the database (a session's `currentNodeId`, a transition's
`toNodeId`, an entry point's `startNodeId`). If one of those doesn't resolve to a real node, the
seed/migration data itself is broken, which is an internal invariant violation, not a bad
request — hence the different exception, and hence why the REST layer maps it to `409 Conflict`
rather than `404 Not Found`.

### `outgoing(nodeId)` — line 248

```java
private List<WorkflowTransition> outgoing(Long nodeId) {
    return workflowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(nodeId);
}
```

One-line delegate to the repository's derived query method. Kept as a private method (rather than
calling the repository directly at each of its four call sites) purely for readability —
`outgoing(current.getNodeId())` reads as intent ("this node's possible next steps") in a way that
the raw repository method name doesn't quite.

### `follow(node, eventCode)` — line 252

```java
private WorkflowNode follow(WorkflowNode node, EventCode eventCode) {
    WorkflowTransition transition = outgoing(node.getNodeId()).stream()
            .filter(t -> t.getEventCode() == eventCode)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                    "No " + eventCode + " transition from " + node.getNodeCode()));
    return loadNode(transition.getToNodeId());
}
```

**What it does**: the single "take one hop" primitive everything else in the class builds on —
load a node's outgoing transitions, find the one matching a specific event code, load and return
its target. Every automatic advance inside `advanceAndFinalize()` (`START`→`AUTO`,
`MESSAGE`→`AUTO`, `ACTION`→`SUCCESS`/`FAILURE`) and both reply handlers' post-match advances
funnel through this one method.

**Why it throws rather than returning `Optional<WorkflowNode>`**: unlike `parseEventCode()`
(where "no match" is an expected, handleable customer-input case), a missing transition here
means the *seed data* is incomplete — e.g. an `ACTION` node configured with a `FAILURE` event
but no `FAILURE`-typed transition row wired up for it. That should never happen with correctly
seeded data, and if it does, the right behavior is failing loudly the moment it's discovered, not
propagating an empty `Optional` for some caller three frames up to eventually mishandle.

### `generateSessionId()` — line 261

```java
private String generateSessionId() {
    return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
}
```

Builds an id like `S3F2A9B1C4` — a fixed `"S"` prefix (so session ids are visually
distinguishable from node codes, entry codes, etc. at a glance in logs) followed by 10 hex
characters taken from a random UUID with the dashes stripped out. Using only the first 10
characters of a 32-character UUID trades a small amount of collision resistance for a shorter,
more manageable id (it fits the `session_id` column's `length = 50` easily and reads better in
logs/URLs than a full 36-character UUID) — reasonable for this POC's scale, where the practical
collision odds are negligible and there's no business requirement (like a globally unique
cross-system id) that would demand the full UUID.

---

## 4. How it all connects — one call traced end to end

To tie the method-by-method breakdown together, here's what actually happens on a single
`reply(sessionId, "OPTION_1")` call against a session sitting at `AMB_MENU`:

1. `reply()` loads the session and the `AMB_MENU` node, stamps `lastInteractionAt`, sees
   `nodeType == QUESTION`, dispatches to `handleQuestionReply()`.
2. `handleQuestionReply()` loads `AMB_MENU`'s three outgoing transitions via `outgoing()`, parses
   `"OPTION_1"` via `parseEventCode()` → `EventCode.OPTION_1`, finds the matching transition
   (→ `CONFIRM_TOPUP`). Not `REMIND_WHEN`, so no special rule. Logs the event via `logEvent()`,
   loads `CONFIRM_TOPUP` via `loadNode()` (inside `follow`... actually here directly via
   `loadNode(match.get().getToNodeId())`), and calls `advanceAndFinalize(session, CONFIRM_TOPUP)`.
3. `advanceAndFinalize()` enters its loop at `CONFIRM_TOPUP`, sees it's a `QUESTION` node,
   renders it via `renderStep()`, sets `currentNodeId`, saves the session, and returns — one
   `RenderedStep`, the `CONFIRM_TOPUP` options, session still `ACTIVE`.

That's the whole engine: a handful of small, single-purpose methods, each throwing a specific
exception type for a specific failure reason, composed into two public entry points.
