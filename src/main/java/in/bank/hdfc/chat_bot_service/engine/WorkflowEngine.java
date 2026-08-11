package in.bank.hdfc.chat_bot_service.engine;

import in.bank.hdfc.chat_bot_service.entity.EventCode;
import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import in.bank.hdfc.chat_bot_service.entity.WorkflowActionConfig;
import in.bank.hdfc.chat_bot_service.entity.WorkflowEntryPoint;
import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSessionEvent;
import in.bank.hdfc.chat_bot_service.entity.WorkflowTransition;
import in.bank.hdfc.chat_bot_service.repository.WorkflowActionConfigRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowEntryPointRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowNodeRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionEventRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowTransitionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Channel-agnostic conversation engine - usable by a REST controller, a console runner, a
 * WhatsApp webhook adapter, etc. without changes (build context doc section 6). One hardcoded
 * flow (AMB shortfall outreach), not a generic rule engine.
 */
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

	private final WorkflowEntryPointRepository workflowEntryPointRepository;
	private final WorkflowNodeRepository workflowNodeRepository;
	private final WorkflowTransitionRepository workflowTransitionRepository;
	private final WorkflowActionConfigRepository workflowActionConfigRepository;
	private final WorkflowSessionRepository workflowSessionRepository;
	private final WorkflowSessionEventRepository workflowSessionEventRepository;

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

	/**
	 * A QUESTION node's outgoing options are either YES/NO (matched literally, case-insensitive)
	 * or OPTION (matched by 1-based index - the channel echoes back the plain number it was
	 * shown, e.g. "3"). No enum ceiling on how many OPTION siblings a node can have.
	 */
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
					// ACTION node "message" is an internal description of the backend call (e.g.
					// "Calls the payment gateway..."), not customer-facing copy - don't add it to
					// steps, just log the event and move on to the next node's message.
					EventCode outcome = simulateAction(session, current);
					logEvent(session, current, outcome, null);
					current = follow(current, outcome);
				}
				case QUESTION -> {
					steps.add(renderStep(current, session));
					session.setCurrentNodeId(current.getNodeId());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), current.getNodeId(), toOptionViews(outgoing(current.getNodeId())));
				}
				case INPUT -> {
					steps.add(renderStep(current, session));
					session.setCurrentNodeId(current.getNodeId());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), current.getNodeId(), List.of());
				}
				case END -> {
					steps.add(renderStep(current, session));
					// Unlike QUESTION/INPUT, nothing ever logs a later event for this node - it's
					// terminal, so log the arrival itself. Without this, a session's very last
					// message never appears in workflow_session_event, which SessionFrameService
					// replays - every completed session's frame would be missing its ending.
					logEvent(session, current, EventCode.AUTO, null);
					// Freeze this END node's conclusion tag onto the session - see
					// WorkflowSession.conclusionCode.
					session.setConclusionCode(current.getConclusionCode());
					session.setCurrentNodeId(current.getNodeId());
					session.setStatus(SessionStatus.COMPLETED);
					session.setEndedAt(Instant.now());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), current.getNodeId(), List.of());
				}
			}
		}
	}

	private RenderedStep renderStep(WorkflowNode node, WorkflowSession session) {
		return new RenderedStep(node.getNodeCode(), node.getNodeType(),
				TemplateRenderer.render(node.getMessage(), session.getContext()));
	}

	/**
	 * Node-code-keyed business rules, not a generic rule engine (build context doc section 6).
	 * Only nodes whose chosen option feeds a downstream ACTION's request need a rule here.
	 */
	private void applyNodeChoiceRule(WorkflowSession session, WorkflowNode node, WorkflowTransition transition) {
		switch (node.getNodeCode()) {
			case "FUNDS_TIMING" -> applyFundsTimingRule(session, transition.getOptionIndex());
			case "CASH_FLOW_MENU" -> session.getContext().put("assistance_type", transition.getOptionLabel());
			case "CHURN_REASON_MENU" -> {
				// index 5 ("Other, please specify") has no fixed label to record yet - the
				// CHURN_REASON_OTHER INPUT node fills in "reason" once the customer types it.
				if (transition.getOptionIndex() != null && transition.getOptionIndex() <= 4) {
					session.getContext().put("reason", transition.getOptionLabel());
				}
			}
			default -> {
				// no business rule for this node's choice
			}
		}
	}

	/**
	 * FUNDS_TIMING options 1-3 ("Within 3/7/15 days") -> a fixed offset from today.
	 */
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

	/**
	 * Simulated backend call - always succeeds unless {@code simulate_failure=true} is set on
	 * session context, in which case it fails exactly once and clears the flag. Side effects are
	 * hardcoded per node_code (not driven generically off request_template/response_mapping).
	 */
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

	private Map<String, Object> rawInputPayload(String rawInput) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("rawInput", rawInput);
		return payload;
	}

	private List<OptionView> toOptionViews(List<WorkflowTransition> transitions) {
		return transitions.stream()
				.map(t -> new OptionView(t.getEventCode(), t.getOptionIndex(), t.getOptionLabel()))
				.toList();
	}

	private void logEvent(WorkflowSession session, WorkflowNode node, EventCode eventCode, Map<String, Object> payload) {
		workflowSessionEventRepository.save(WorkflowSessionEvent.builder()
				.sessionId(session.getSessionId())
				.nodeId(node.getNodeId())
				.eventCode(eventCode)
				.payload(payload)
				.createdAt(Instant.now())
				.build());
	}

	private WorkflowNode loadNode(Long nodeId) {
		return workflowNodeRepository.findById(nodeId)
				.orElseThrow(() -> new IllegalStateException("Node " + nodeId + " not found"));
	}

	private WorkflowSession loadSession(String sessionId) {
		return workflowSessionRepository.findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown session " + sessionId));
	}

	private List<WorkflowTransition> outgoing(Long nodeId) {
		return workflowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(nodeId);
	}

	private WorkflowNode follow(WorkflowNode node, EventCode eventCode) {
		WorkflowTransition transition = outgoing(node.getNodeId()).stream()
				.filter(t -> t.getEventCode() == eventCode)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"No " + eventCode + " transition from " + node.getNodeCode()));
		return loadNode(transition.getToNodeId());
	}

	private String generateSessionId() {
		return "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
	}
}
