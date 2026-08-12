package in.bank.hdfc.chat_bot_service.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.bank.hdfc.chat_bot_service.conversation.ConversationResponse;
import in.bank.hdfc.chat_bot_service.conversation.OptionResponse;
import in.bank.hdfc.chat_bot_service.entity.EventCode;
import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WhatsAppMessageMapperTest {

	private final WhatsAppMessageMapper mapper = new WhatsAppMessageMapper();

	@Test
	@SuppressWarnings("unchecked")
	void noOptionsBecomesPlainText() {
		ConversationResponse response = new ConversationResponse("s1", SessionStatus.COMPLETED,
				"Your funds link: https://example.test/pay", null, List.of());

		Map<String, Object> payload = mapper.toPayload("919998887770", response);

		assertEquals("whatsapp", payload.get("messaging_product"));
		assertEquals("919998887770", payload.get("to"));
		assertEquals("text", payload.get("type"));
		Map<String, Object> text = (Map<String, Object>) payload.get("text");
		assertEquals("Your funds link: https://example.test/pay", text.get("body"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void yesNoBecomesButtonsWithReplyIdsMatchingTheEngineEncoding() {
		ConversationResponse response = new ConversationResponse("s1", SessionStatus.ACTIVE,
				"Are you sure?", 10L,
				List.of(new OptionResponse(EventCode.YES, null, "Yes"), new OptionResponse(EventCode.NO, null, "No")));

		Map<String, Object> payload = mapper.toPayload("919998887770", response);

		assertEquals("interactive", payload.get("type"));
		Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
		assertEquals("button", interactive.get("type"));
		Map<String, Object> action = (Map<String, Object>) interactive.get("action");
		List<Map<String, Object>> buttons = (List<Map<String, Object>>) action.get("buttons");
		assertEquals(2, buttons.size());

		Map<String, Object> yesReply = (Map<String, Object>) buttons.get(0).get("reply");
		// "YES" is exactly what WorkflowEngine#matchReply parses back out of rawInput.
		assertEquals("YES", yesReply.get("id"));
		assertEquals("Yes", yesReply.get("title"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void longMenuLabelsFallBackToAListWithIndexReplyIds() {
		ConversationResponse response = new ConversationResponse("s1", SessionStatus.ACTIVE,
				"How can we help?", 1L,
				List.of(
						new OptionResponse(EventCode.OPTION, 1, "I no longer actively use this account"),
						new OptionResponse(EventCode.OPTION, 2, "I'm facing a temporary cash flow issue"),
						new OptionResponse(EventCode.OPTION, 3, "I have a service concern"),
						new OptionResponse(EventCode.OPTION, 4, "I'm switching to another bank"),
						new OptionResponse(EventCode.OPTION, 5, "Something else")));

		Map<String, Object> payload = mapper.toPayload("919998887770", response);

		assertEquals("interactive", payload.get("type"));
		Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
		assertEquals("list", interactive.get("type"));
		Map<String, Object> action = (Map<String, Object>) interactive.get("action");
		List<Map<String, Object>> sections = (List<Map<String, Object>>) action.get("sections");
		List<Map<String, Object>> rows = (List<Map<String, Object>>) sections.get(0).get("rows");

		assertEquals(5, rows.size());
		// "1" is exactly what WorkflowEngine#matchReply parses back as the OPTION index.
		assertEquals("1", rows.get(0).get("id"));
		assertTrue(((String) rows.get(0).get("title")).length() <= 24);
		assertEquals("I no longer actively use this account", rows.get(0).get("description"));
	}

	@Test
	void threeShortOptionLabelsStillFitAsButtons() {
		ConversationResponse response = new ConversationResponse("s1", SessionStatus.ACTIVE,
				"Pick one", 1L,
				List.of(
						new OptionResponse(EventCode.OPTION, 1, "Yes please"),
						new OptionResponse(EventCode.OPTION, 2, "No thanks"),
						new OptionResponse(EventCode.OPTION, 3, "Not sure")));

		Map<String, Object> payload = mapper.toPayload("919998887770", response);

		assertEquals("interactive", payload.get("type"));
		@SuppressWarnings("unchecked")
		Map<String, Object> interactive = (Map<String, Object>) payload.get("interactive");
		assertEquals("button", interactive.get("type"));
	}
}
