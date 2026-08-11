package in.bank.hdfc.chat_bot_service.frame;

import in.bank.hdfc.chat_bot_service.engine.TemplateRenderer;
import in.bank.hdfc.chat_bot_service.entity.EventCode;
import in.bank.hdfc.chat_bot_service.entity.NodeType;
import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSessionEvent;
import in.bank.hdfc.chat_bot_service.entity.WorkflowTransition;
import in.bank.hdfc.chat_bot_service.frame.FrameStepResponse.FrameOptionResponse;
import in.bank.hdfc.chat_bot_service.repository.WorkflowNodeRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionEventRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowTransitionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstructs a customer's whole journey ("frame") by replaying its
 * {@code workflow_session_event} log against the graph as it stands *today* - see the class
 * comment on {@link SessionFrameResponse}. Deliberately read-only and separate from
 * {@link in.bank.hdfc.chat_bot_service.engine.WorkflowEngine}, which only ever cares about the
 * live turn, not the whole history.
 */
@Service
@RequiredArgsConstructor
public class SessionFrameService {

	private final WorkflowSessionRepository workflowSessionRepository;
	private final WorkflowSessionEventRepository workflowSessionEventRepository;
	private final WorkflowNodeRepository workflowNodeRepository;
	private final WorkflowTransitionRepository workflowTransitionRepository;

	@Transactional(readOnly = true)
	public SessionFrameResponse reconstructFrame(String sessionId) {
		WorkflowSession session = workflowSessionRepository.findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown session " + sessionId));
		return buildFrame(session);
	}

	@Transactional(readOnly = true)
	public SessionFrameResponse reconstructFrameForCustomer(String customerId) {
		WorkflowSession session = workflowSessionRepository.findFirstByCustomerIdOrderByStartedAtDesc(customerId)
				.orElseThrow(() -> new IllegalArgumentException("No sessions found for customer " + customerId));
		return buildFrame(session);
	}

	private SessionFrameResponse buildFrame(WorkflowSession session) {
		List<WorkflowSessionEvent> events =
				workflowSessionEventRepository.findBySessionIdOrderByEventIdAsc(session.getSessionId());

		List<FrameStepResponse> steps = new ArrayList<>();
		int sequenceNo = 1;
		for (WorkflowSessionEvent event : events) {
			WorkflowNode node = workflowNodeRepository.findById(event.getNodeId())
					.orElseThrow(() -> new IllegalStateException("Node " + event.getNodeId() + " not found"));
			steps.add(buildStep(sequenceNo++, event, node, session.getContext()));
		}
		return SessionFrameResponse.from(session, steps);
	}

	private FrameStepResponse buildStep(int sequenceNo, WorkflowSessionEvent event, WorkflowNode node,
			Map<String, Object> context) {
		// START/ACTION node text is internal-only (e.g. "Calls the payment gateway..."), never
		// shown to the customer - same rule WorkflowEngine.advanceAndFinalize applies live.
		String message = (node.getNodeType() == NodeType.START || node.getNodeType() == NodeType.ACTION)
				? null
				: TemplateRenderer.render(node.getMessage(), context);

		List<FrameOptionResponse> optionsShown =
				node.getNodeType() == NodeType.QUESTION ? optionsShownFor(node.getNodeId(), event) : List.of();

		String rawInput = rawInputOf(event);

		return new FrameStepResponse(sequenceNo, event.getCreatedAt(), node.getNodeCode(), node.getNodeType(),
				node.getTitle(), message, optionsShown, rawInput, event.getEventCode(),
				event.getEventCode() != EventCode.INVALID_INPUT);
	}

	/** What was on screen when this QUESTION node's reply event fired, with the chosen one flagged. */
	private List<FrameOptionResponse> optionsShownFor(Long nodeId, WorkflowSessionEvent event) {
		Integer chosenOptionIndex = parseChosenOptionIndex(event);
		return workflowTransitionRepository.findByFromNodeIdOrderByDisplayOrderAsc(nodeId).stream()
				.filter(t -> t.getEventCode() == EventCode.OPTION || t.getEventCode() == EventCode.YES
						|| t.getEventCode() == EventCode.NO)
				.map(t -> new FrameOptionResponse(t.getOptionIndex(), t.getOptionLabel(),
						isChosen(t, event, chosenOptionIndex)))
				.toList();
	}

	private boolean isChosen(WorkflowTransition transition, WorkflowSessionEvent event, Integer chosenOptionIndex) {
		if (event.getEventCode() == EventCode.INVALID_INPUT) {
			return false;
		}
		if (event.getEventCode() == EventCode.OPTION) {
			return transition.getEventCode() == EventCode.OPTION
					&& transition.getOptionIndex() != null
					&& transition.getOptionIndex().equals(chosenOptionIndex);
		}
		return transition.getEventCode() == event.getEventCode(); // YES/NO, matched literally
	}

	private Integer parseChosenOptionIndex(WorkflowSessionEvent event) {
		if (event.getEventCode() != EventCode.OPTION) {
			return null;
		}
		String rawInput = rawInputOf(event);
		if (rawInput == null) {
			return null;
		}
		try {
			return Integer.parseInt(rawInput.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The raw text/number the customer sent, or null if this hop had no reply attached to it
	 * (e.g. an auto-advanced MESSAGE/ACTION node) - never the literal string "null". */
	private String rawInputOf(WorkflowSessionEvent event) {
		if (event.getPayload() == null) {
			return null;
		}
		Object rawInput = event.getPayload().get("rawInput");
		return rawInput != null ? String.valueOf(rawInput) : null;
	}
}
