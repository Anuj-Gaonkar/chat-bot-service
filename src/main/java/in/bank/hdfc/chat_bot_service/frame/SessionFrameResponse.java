package in.bank.hdfc.chat_bot_service.frame;

import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A customer's whole conversation, replayed as one object - the session header plus every step
 * in order. Reconstructed on read from {@code workflow_session}/{@code workflow_session_event}
 * against the graph's *current* node/transition text (see {@link SessionFrameService}), not a
 * byte-exact historical snapshot.
 */
public record SessionFrameResponse(String sessionId, String customerId, Long workflowVersionId,
		SessionStatus status, Instant startedAt, Instant lastInteractionAt, Instant endedAt,
		Map<String, Object> context, List<FrameStepResponse> steps) {

	static SessionFrameResponse from(WorkflowSession session, List<FrameStepResponse> steps) {
		return new SessionFrameResponse(session.getSessionId(), session.getCustomerId(),
				session.getWorkflowVersionId(), session.getStatus(), session.getStartedAt(),
				session.getLastInteractionAt(), session.getEndedAt(), session.getContext(), steps);
	}
}
