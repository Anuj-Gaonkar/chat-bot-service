package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.engine.EngineTurnResult;
import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.util.List;

/** Public, channel-agnostic REST shape for one engine turn - kept separate from
 * {@link EngineTurnResult} so the engine stays free to change its internal record shape. */
public record ConversationResponse(String sessionId, SessionStatus status, List<StepResponse> steps,
		String currentNodeCode, List<OptionResponse> options) {

	static ConversationResponse from(EngineTurnResult result) {
		return new ConversationResponse(result.sessionId(), result.status(),
				result.steps().stream().map(StepResponse::from).toList(), result.currentNodeCode(),
				result.options().stream().map(OptionResponse::from).toList());
	}
}