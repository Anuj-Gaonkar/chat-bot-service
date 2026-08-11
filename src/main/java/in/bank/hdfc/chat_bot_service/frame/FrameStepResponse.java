package in.bank.hdfc.chat_bot_service.frame;

import in.bank.hdfc.chat_bot_service.entity.EventCode;
import in.bank.hdfc.chat_bot_service.entity.NodeType;
import java.time.Instant;
import java.util.List;

/**
 * One node hop in a customer's journey, reconstructed from a {@code workflow_session_event} row.
 *
 * <p>{@code message} is the node's own text (null for START/ACTION - never customer-facing, same
 * rule as the live engine). For a QUESTION node, this is the question that was asked;
 * {@code optionsShown} is what was on screen, {@code rawInput}/{@code eventCode} is how the
 * customer replied, and {@code matched} is false only for an {@link EventCode#INVALID_INPUT}
 * attempt (kept in the frame - it's part of what actually happened, even though it wouldn't
 * appear in the customer's own chat transcript).
 */
public record FrameStepResponse(int sequenceNo, Instant timestamp, String nodeCode, NodeType nodeType, String title,
		String message, List<FrameOptionResponse> optionsShown, String rawInput, EventCode eventCode,
		boolean matched) {

	public record FrameOptionResponse(Integer optionIndex, String optionLabel, boolean chosen) {
	}
}
