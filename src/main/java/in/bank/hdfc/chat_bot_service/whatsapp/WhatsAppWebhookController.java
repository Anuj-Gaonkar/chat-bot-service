package in.bank.hdfc.chat_bot_service.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bank.hdfc.chat_bot_service.conversation.ConversationResponse;
import in.bank.hdfc.chat_bot_service.engine.WorkflowEngine;
import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The WhatsApp channel adapter's inbound half - Meta calls these two endpoints, never a customer
 * directly. Reuses {@link WorkflowEngine} as a plain in-process bean (the same one
 * {@code ConversationController} calls), so the DB-defined conversation graph and every existing
 * REST endpoint are completely unaffected by this adapter existing. See
 * WHATSAPP_LAYER0_INTEGRATION_PLAN.md for the full design.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
@RequiredArgsConstructor
@Slf4j
public class WhatsAppWebhookController {

	// The single seeded flow this demo drives - same entry point chat-ui's test harness uses.
	// Not user-editable input.
	private static final String ENTRY_CODE = "AMB_SHORTFALL_Q2";

	private final WorkflowEngine workflowEngine;
	private final WorkflowSessionRepository workflowSessionRepository;
	private final WhatsAppClient whatsAppClient;
	private final WhatsAppMessageMapper messageMapper;
	private final WhatsAppProperties properties;
	private final ObjectMapper objectMapper;

	/** Meta's one-time handshake when the webhook Callback URL is saved in the App Dashboard. */
	@GetMapping
	public ResponseEntity<String> verify(
			@RequestParam("hub.mode") String mode,
			@RequestParam("hub.verify_token") String verifyToken,
			@RequestParam("hub.challenge") String challenge) {
		if ("subscribe".equals(mode) && properties.verifyToken().equals(verifyToken)) {
			return ResponseEntity.ok(challenge);
		}
		log.warn("Rejected WhatsApp webhook verification (mode={})", mode);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
	}

	/** Every inbound customer message (and delivery-status ping, which we ignore) lands here. */
	@PostMapping
	public ResponseEntity<Void> receive(
			@RequestBody String rawBody,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
		if (!SignatureVerifier.isValid(rawBody, signature, properties.appSecret())) {
			log.warn("Rejected WhatsApp webhook call with invalid signature");
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		try {
			JsonNode root = objectMapper.readTree(rawBody);
			for (JsonNode change : changes(root)) {
				for (JsonNode message : change.path("value").path("messages")) {
					handleInbound(message);
				}
				// change.path("value").path("statuses") (sent/delivered/read receipts) is
				// deliberately ignored - not relevant to driving the conversation.
			}
		} catch (Exception e) {
			log.error("Failed to process WhatsApp webhook payload", e);
		}
		return ResponseEntity.ok().build();
	}

	private List<JsonNode> changes(JsonNode root) {
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode entry : root.path("entry")) {
			for (JsonNode change : entry.path("changes")) {
				result.add(change);
			}
		}
		return result;
	}

	private void handleInbound(JsonNode message) {
		String from = message.path("from").asText();
		String rawInput = extractRawInput(message);
		if (rawInput == null) {
			log.info("Ignoring unsupported WhatsApp message type from {}: {}", from, message.path("type").asText());
			return;
		}

		ConversationResponse response = workflowSessionRepository.findFirstByCustomerIdOrderByStartedAtDesc(from)
				.filter(session -> session.getStatus() == SessionStatus.ACTIVE)
				.map(session -> ConversationResponse.from(workflowEngine.reply(session.getSessionId(), rawInput)))
				.orElseGet(() -> ConversationResponse.from(workflowEngine.start(ENTRY_CODE, from, Map.of())));

		whatsAppClient.send(messageMapper.toPayload(from, response));
	}

	/** Text message -> its body. Button/list tap -> the reply id, which is already exactly the
	 * string {@code WorkflowEngine#matchReply} expects (see {@link WhatsAppMessageMapper}). */
	private String extractRawInput(JsonNode message) {
		String type = message.path("type").asText();
		return switch (type) {
			case "text" -> message.path("text").path("body").asText(null);
			case "interactive" -> {
				String interactiveType = message.path("interactive").path("type").asText();
				yield switch (interactiveType) {
					case "button_reply" -> message.path("interactive").path("button_reply").path("id").asText(null);
					case "list_reply" -> message.path("interactive").path("list_reply").path("id").asText(null);
					default -> null;
				};
			}
			default -> null;
		};
	}
}
