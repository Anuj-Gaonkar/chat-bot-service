package in.bank.hdfc.chat_bot_service.access;

import in.bank.hdfc.chat_bot_service.entity.ChatAccessToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate for the chat-ui link: the WhatsApp CTA sender mints a token for the recipient's number, and
 * chat-ui verifies the number the customer types against it before starting a conversation.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Chat Access", description = "Tokens that tie a chat-ui link to the phone number it was sent to")
public class ChatAccessTokenController {

	private final ChatAccessTokenService chatAccessTokenService;

	public record IssueTokenRequest(@NotBlank String phoneNumber) {
	}

	public record IssueTokenResponse(String token, Instant expiresAt) {
	}

	public record VerifyTokenRequest(@NotBlank String token, @NotBlank String phoneNumber) {
	}

	@PostMapping("/api/chat-tokens")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Mint a link token for a phone number (call when sending the CTA message)")
	public IssueTokenResponse issue(@Valid @RequestBody IssueTokenRequest request) {
		ChatAccessToken token = chatAccessTokenService.issue(request.phoneNumber());
		return new IssueTokenResponse(token.getToken(), token.getExpiresAt());
	}

	@PostMapping("/api/chat-tokens/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Check that a token is valid and was issued for this phone number; 403 otherwise")
	public void verify(@Valid @RequestBody VerifyTokenRequest request) {
		chatAccessTokenService.verify(request.token(), request.phoneNumber());
	}
}
