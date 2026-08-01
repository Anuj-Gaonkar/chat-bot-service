package in.bank.hdfc.chat_bot_service.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises {@link WorkflowEngine} against the seeded AMB shortfall flow. Runs against the same
 * compose Postgres as the dev app (seeder is idempotent, so pre-existing seed data is reused) -
 * no Testcontainers dependency added for this POC.
 */
@SpringBootTest
class WorkflowEngineTest {

	@Autowired
	private WorkflowEngine engine;

	@Test
	void topUpSuccessPath() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-TOPUP-OK", Map.of(
				"customer_name", "Anuj", "amb_required", "10000", "shortfall_amount", "4500"));
		assertEquals("AMB_MENU", started.currentNodeCode());
		assertEquals(SessionStatus.ACTIVE, started.status());
		assertEquals(3, started.options().size());

		EngineTurnResult afterMenu = engine.reply(started.sessionId(), "OPTION_1");
		assertEquals("CONFIRM_TOPUP", afterMenu.currentNodeCode());

		EngineTurnResult afterConfirm = engine.reply(started.sessionId(), "YES");
		assertEquals("END_TOPUP", afterConfirm.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterConfirm.status());

		boolean paymentLinkRendered = afterConfirm.steps().stream()
				.filter(s -> "PAYMENT_LINK_SENT".equals(s.nodeCode()))
				.anyMatch(s -> !s.message().contains("{{"));
		assertTrue(paymentLinkRendered, "{{payment_link}} should have been substituted");
	}

	@Test
	void topUpFailureLoopsBackToMenuAndClearsFlagAfterOneUse() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-TOPUP-FAIL",
				Map.of("simulate_failure", true));

		engine.reply(started.sessionId(), "OPTION_1");
		EngineTurnResult afterFailedLink = engine.reply(started.sessionId(), "YES");
		assertEquals("AMB_MENU", afterFailedLink.currentNodeCode(), "failed link generation should loop back to AMB_MENU");
		assertEquals(SessionStatus.ACTIVE, afterFailedLink.status());

		// flag was consumed by the first failure - the retry should now succeed
		engine.reply(started.sessionId(), "OPTION_1");
		EngineTurnResult afterRetry = engine.reply(started.sessionId(), "YES");
		assertEquals("END_TOPUP", afterRetry.currentNodeCode());
	}

	@Test
	void remindMeOption1IsThreeDaysOut() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-REMIND-3D", Map.of());
		engine.reply(started.sessionId(), "OPTION_2");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "OPTION_1");

		assertEquals("END_REMINDER", afterChoice.currentNodeCode());
		String expectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(3).toString();
		boolean reminderDateRendered = afterChoice.steps().stream()
				.filter(s -> "REMINDER_SET".equals(s.nodeCode()))
				.anyMatch(s -> s.message().contains(expectedDate));
		assertTrue(reminderDateRendered, "reminder_date should be today + 3 days for OPTION_1");
	}

	@Test
	void remindMeOption2IsSevenDaysOut() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-REMIND-7D", Map.of());
		engine.reply(started.sessionId(), "OPTION_2");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "OPTION_2");

		assertEquals("END_REMINDER", afterChoice.currentNodeCode());
		String expectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(7).toString();
		boolean reminderDateRendered = afterChoice.steps().stream()
				.filter(s -> "REMINDER_SET".equals(s.nodeCode()))
				.anyMatch(s -> s.message().contains(expectedDate));
		assertTrue(reminderDateRendered, "reminder_date should be today + 7 days for OPTION_2");
	}

	@Test
	void optOutPath() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-OPTOUT", Map.of());
		EngineTurnResult afterMenu = engine.reply(started.sessionId(), "OPTION_3");
		assertEquals("CONFIRM_OPT_OUT", afterMenu.currentNodeCode());

		EngineTurnResult afterConfirm = engine.reply(started.sessionId(), "YES");
		assertEquals("END_OPT_OUT", afterConfirm.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterConfirm.status());
	}

	@Test
	void unmatchedReplyReShowsSameQuestionWithoutAdvancing() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-INVALID", Map.of());

		EngineTurnResult afterBadReply = engine.reply(started.sessionId(), "BANANA");
		assertEquals("AMB_MENU", afterBadReply.currentNodeCode());
		assertEquals(SessionStatus.ACTIVE, afterBadReply.status());
		assertTrue(afterBadReply.steps().get(0).message().startsWith("Sorry, that wasn't one of the options."));

		// session must still be sitting at AMB_MENU, ready to accept a real option
		EngineTurnResult afterValidReply = engine.reply(started.sessionId(), "OPTION_1");
		assertEquals("CONFIRM_TOPUP", afterValidReply.currentNodeCode());
	}
}
