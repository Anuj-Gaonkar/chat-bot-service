package in.bank.hdfc.chat_bot_service.conversation;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record StartConversationRequest(@NotBlank String entryCode, @NotBlank String customerId,
		Map<String, Object> context) {
}
