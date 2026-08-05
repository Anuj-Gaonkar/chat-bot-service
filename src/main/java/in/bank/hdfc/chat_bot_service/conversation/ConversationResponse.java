package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.engine.EngineTurnResult;
import in.bank.hdfc.chat_bot_service.engine.RenderedStep;
import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.util.List;
import java.util.stream.Collectors;

/** Public, channel-agnostic REST shape for one engine turn - kept separate from
 * {@link EngineTurnResult} so the engine stays free to change its internal record shape.
 *
 * <p>{@code message} collapses every {@link EngineTurnResult#steps()} entry for this turn - the
 * auto-advanced MESSAGE/ACTION nodes plus the terminal QUESTION/INPUT/END node that stopped the
 * turn - into a single newline-joined string, so a channel like WhatsApp can render one turn as
 * one message bubble instead of one bubble per node. */
public record ConversationResponse(String sessionId, SessionStatus status, String message,
		Long currentNodeId, List<OptionResponse> options) {

	static ConversationResponse from(EngineTurnResult result) {
		return new ConversationResponse(result.sessionId(), result.status(), combinedMessage(result.steps()),
				result.currentNodeId(), result.options().stream().map(OptionResponse::from).toList());
	}

	private static String combinedMessage(List<RenderedStep> steps) {
		return steps.stream()
				.map(RenderedStep::message)
				.filter(text -> text != null && !text.isBlank())
				.collect(Collectors.joining("\n"));
	}
}
