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

	private EngineTurnResult handleInputReply(WorkflowSession session, WorkflowNode current, String rawInput) {
		// Stub only - contract calls for a validation loop but no INPUT node exists in the
		// seed flow to exercise it (build context doc section 3). Always accepts and advances.
		session.getContext().put(current.getNodeCode(), rawInput);
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
					EventCode outcome = simulateAction(session, current);
					steps.add(renderStep(current, session));
					logEvent(session, current, outcome, null);
					current = follow(current, outcome);
				}
				case QUESTION -> {
					steps.add(renderStep(current, session));
					session.setCurrentNodeId(current.getNodeId());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), toOptionViews(outgoing(current.getNodeId())));
				}
				case INPUT -> {
					steps.add(renderStep(current, session));
					session.setCurrentNodeId(current.getNodeId());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), List.of());
				}
				case END -> {
					steps.add(renderStep(current, session));
					session.setCurrentNodeId(current.getNodeId());
					session.setStatus(SessionStatus.COMPLETED);
					session.setEndedAt(Instant.now());
					workflowSessionRepository.save(session);
					return new EngineTurnResult(session.getSessionId(), session.getStatus(), steps,
							current.getNodeCode(), List.of());
				}
			}
		}
	}

	private RenderedStep renderStep(WorkflowNode node, WorkflowSession session) {
		return new RenderedStep(node.getNodeCode(), node.getNodeType(),
				TemplateRenderer.render(node.getMessage(), session.getContext()));
	}

	/**
	 * Node-code-keyed business rule, not a generic rule engine (build context doc section 6).
	 * OPTION_1 ("In 3 days") -> today + 3 days; OPTION_2 ("Next week") -> today + 7 days.
	 */
	private void applyRemindWhenRule(WorkflowSession session, EventCode chosenOption) {
		LocalDate reminderDate = switch (chosenOption) {
			case OPTION_1 -> LocalDate.now(ZoneOffset.UTC).plusDays(3);
			case OPTION_2 -> LocalDate.now(ZoneOffset.UTC).plusDays(7);
			default -> throw new IllegalStateException("Unexpected REMIND_WHEN option " + chosenOption);
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
			case "GENERATE_LINK" ->
					context.put("payment_link", "https://pay.hdfcbank.example/topup/" + session.getSessionId());
			case "SET_REMINDER" -> context.put("reminder_id", "RMD-" + session.getSessionId());
			default -> {
				// no context side-effect needed (e.g. RECORD_OPT_OUT)
			}
		}
		return config.getOnSuccessEvent();
	}

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

	private Map<String, Object> rawInputPayload(String rawInput) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("rawInput", rawInput);
		return payload;
	}

	private List<OptionView> toOptionViews(List<WorkflowTransition> transitions) {
		return transitions.stream().map(t -> new OptionView(t.getEventCode(), t.getOptionLabel())).toList();
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
