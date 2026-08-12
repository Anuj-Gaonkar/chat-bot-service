package in.bank.hdfc.chat_bot_service.whatsapp;

import in.bank.hdfc.chat_bot_service.conversation.ConversationResponse;
import in.bank.hdfc.chat_bot_service.conversation.OptionResponse;
import in.bank.hdfc.chat_bot_service.entity.EventCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Translates the channel-agnostic {@link ConversationResponse} (the same DTO
 * {@code ConversationController} and chat-ui's test harness already use) into a WhatsApp Business
 * Cloud API "send message" payload, ready to POST as-is via {@link WhatsAppClient}.
 *
 * <p>Reply ids are set to exactly the string {@code WorkflowEngine#matchReply} already expects
 * back as {@code rawInput} ({@code "YES"}/{@code "NO"} or a plain option index like {@code "3"}) -
 * so the inbound webhook handler needs zero translation, it just forwards the tapped id straight
 * into {@code workflowEngine.reply(sessionId, id)}.
 */
@Component
class WhatsAppMessageMapper {

	// WhatsApp Cloud API hard limits on "interactive" messages - see the Message API reference.
	private static final int MAX_BUTTONS = 3;
	private static final int BUTTON_TITLE_MAX = 20;
	private static final int LIST_ROW_TITLE_MAX = 24;
	private static final int LIST_ROW_DESCRIPTION_MAX = 72;

	Map<String, Object> toPayload(String to, ConversationResponse response) {
		List<OptionResponse> options = response.options();
		if (options.isEmpty()) {
			return textPayload(to, response.message());
		}
		boolean fitsAsButtons = options.size() <= MAX_BUTTONS
				&& options.stream().allMatch(o -> o.optionLabel().length() <= BUTTON_TITLE_MAX);
		return fitsAsButtons ? buttonPayload(to, response.message(), options) : listPayload(to, response.message(), options);
	}

	private Map<String, Object> textPayload(String to, String body) {
		Map<String, Object> payload = envelope(to, "text");
		payload.put("text", Map.of("body", body));
		return payload;
	}

	private Map<String, Object> buttonPayload(String to, String bodyText, List<OptionResponse> options) {
		List<Map<String, Object>> buttons = new ArrayList<>();
		for (OptionResponse option : options) {
			buttons.add(Map.of("type", "reply", "reply",
					Map.of("id", replyId(option), "title", truncate(option.optionLabel(), BUTTON_TITLE_MAX))));
		}

		Map<String, Object> interactive = new LinkedHashMap<>();
		interactive.put("type", "button");
		interactive.put("body", Map.of("text", bodyText));
		interactive.put("action", Map.of("buttons", buttons));

		Map<String, Object> payload = envelope(to, "interactive");
		payload.put("interactive", interactive);
		return payload;
	}

	private Map<String, Object> listPayload(String to, String bodyText, List<OptionResponse> options) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (OptionResponse option : options) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", replyId(option));
			row.put("title", truncate(option.optionLabel(), LIST_ROW_TITLE_MAX));
			row.put("description", truncate(option.optionLabel(), LIST_ROW_DESCRIPTION_MAX));
			rows.add(row);
		}

		Map<String, Object> action = new LinkedHashMap<>();
		action.put("button", "Choose an option");
		action.put("sections", List.of(Map.of("title", "Options", "rows", rows)));

		Map<String, Object> interactive = new LinkedHashMap<>();
		interactive.put("type", "list");
		interactive.put("body", Map.of("text", bodyText));
		interactive.put("action", action);

		Map<String, Object> payload = envelope(to, "interactive");
		payload.put("interactive", interactive);
		return payload;
	}

	private String replyId(OptionResponse option) {
		return option.eventCode() == EventCode.YES || option.eventCode() == EventCode.NO
				? option.eventCode().name()
				: String.valueOf(option.optionIndex());
	}

	private String truncate(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	private Map<String, Object> envelope(String to, String type) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("messaging_product", "whatsapp");
		payload.put("recipient_type", "individual");
		payload.put("to", to);
		payload.put("type", type);
		return payload;
	}
}
