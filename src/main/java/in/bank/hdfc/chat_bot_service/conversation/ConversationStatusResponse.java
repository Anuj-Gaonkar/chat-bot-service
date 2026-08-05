package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.time.Instant;
import java.util.Map;

/** Point-in-time snapshot of a session, for clients that reconnect or poll rather than
 * driving a turn. Unlike {@link ConversationResponse} this carries no rendered steps - the
 * engine only renders steps as a side effect of {@code start()}/{@code reply()}. */
public record ConversationStatusResponse(String sessionId, SessionStatus status, String customerId,
		Long currentNodeId, Map<String, Object> context, Instant startedAt, Instant lastInteractionAt,
		Instant endedAt) {
}
