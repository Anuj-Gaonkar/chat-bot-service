package in.bank.hdfc.chat_bot_service.conversation;

import jakarta.validation.constraints.NotBlank;

public record ReplyRequest(@NotBlank String rawInput) {
}
