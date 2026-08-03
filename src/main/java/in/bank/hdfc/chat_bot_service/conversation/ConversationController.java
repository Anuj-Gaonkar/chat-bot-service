package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.engine.WorkflowEngine;
import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import in.bank.hdfc.chat_bot_service.repository.WorkflowNodeRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Headless conversation API - drives {@link WorkflowEngine} over plain JSON, independent of any
 * messaging channel (build context doc's deferred "Phase 2"). A WhatsApp/web/IVR adapter would
 * be a client of these same endpoints, not a fork of them.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Conversations", description = "Channel-agnostic API for starting and driving workflow sessions")
public class ConversationController {

	private final WorkflowEngine workflowEngine;
	private final WorkflowSessionRepository workflowSessionRepository;
	private final WorkflowNodeRepository workflowNodeRepository;

	@PostMapping("/api/conversations")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Start a new conversation session for an entry point")
	public ConversationResponse start(@Valid @RequestBody StartConversationRequest request) {
		return ConversationResponse.from(
				workflowEngine.start(request.entryCode(), request.customerId(), request.context()));
	}

	@PostMapping("/api/conversations/{sessionId}/messages")
	@Operation(summary = "Advance a session by replying to its current question")
	public ConversationResponse reply(@PathVariable String sessionId, @Valid @RequestBody ReplyRequest request) {
		return ConversationResponse.from(workflowEngine.reply(sessionId, request.rawInput()));
	}

	@GetMapping("/api/conversations/{sessionId}")
	@Transactional(readOnly = true)
	@Operation(summary = "Get a point-in-time snapshot of a session's status, without advancing it")
	public ConversationStatusResponse getStatus(@PathVariable String sessionId) {
		WorkflowSession session = workflowSessionRepository.findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown session " + sessionId));
		WorkflowNode currentNode = workflowNodeRepository.findById(session.getCurrentNodeId())
				.orElseThrow(() -> new IllegalStateException("Node " + session.getCurrentNodeId() + " not found"));

		return new ConversationStatusResponse(session.getSessionId(), session.getStatus(), session.getCustomerId(),
				currentNode.getNodeCode(), session.getContext(), session.getStartedAt(),
				session.getLastInteractionAt(), session.getEndedAt());
	}
}
