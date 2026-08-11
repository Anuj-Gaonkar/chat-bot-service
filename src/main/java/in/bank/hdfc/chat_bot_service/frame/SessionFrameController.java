package in.bank.hdfc.chat_bot_service.frame;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reconstruction of a customer's whole conversation, for support/debug/analytics use -
 * not part of the live conversation loop ({@link in.bank.hdfc.chat_bot_service.conversation.ConversationController}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Session Frame", description = "Reconstructs a customer's whole conversation journey from the event log")
public class SessionFrameController {

	private final SessionFrameService sessionFrameService;

	@GetMapping("/api/sessions/{sessionId}/frame")
	@Operation(summary = "Reconstruct one session's whole journey by session ID")
	public SessionFrameResponse getSessionFrame(@PathVariable String sessionId) {
		return sessionFrameService.reconstructFrame(sessionId);
	}

	@GetMapping("/api/customers/{customerId}/frame")
	@Operation(summary = "Reconstruct a customer's most recent session's whole journey (e.g. by phone number)")
	public SessionFrameResponse getCustomerFrame(@PathVariable String customerId) {
		return sessionFrameService.reconstructFrameForCustomer(customerId);
	}
}
