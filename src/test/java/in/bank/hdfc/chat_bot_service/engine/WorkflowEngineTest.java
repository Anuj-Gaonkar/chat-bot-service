package in.bank.hdfc.chat_bot_service.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import in.bank.hdfc.chat_bot_service.repository.WorkflowSessionRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises {@link WorkflowEngine} against the seeded AMB shortfall flow - the 5-branch journey
 * from the poster, on the generic OPTION + option_index model. Runs against the same compose
 * Postgres as the dev app (seeder is idempotent, so pre-existing seed data is reused) - no
 * Testcontainers dependency added for this POC.
 */
@SpringBootTest
class WorkflowEngineTest {

	@Autowired
	private WorkflowEngine engine;

	@Autowired
	private WorkflowSessionRepository workflowSessionRepository;

	@Test
	void menuOffersAllFiveBranches() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-MENU", Map.of());
		assertEquals("AMB_MENU", started.currentNodeCode());
		assertEquals(SessionStatus.ACTIVE, started.status());
		assertEquals(5, started.options().size());
	}

	@Test
	void fundNowGeneratesLinkAndEndsTheTurn() {
		// FUND_TODAY_ACK is a terminal END node reached via the GENERATE_FUND_LINK action - the
		// engine has no real async wait, so there's no live "funded/not funded" branch here.
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-FUND-OK", Map.of());

		EngineTurnResult afterMenu = engine.reply(started.sessionId(), "1");
		assertEquals("FUND_TODAY_ACK", afterMenu.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterMenu.status());
		assertEquals(1, afterMenu.steps().size(),
				"only the ack should render - GENERATE_FUND_LINK is an ACTION node, its internal description must not appear");
		assertTrue(afterMenu.steps().get(0).message().contains("https://"),
				"the ack message should have the generated payment_link substituted in");

		String paymentLink = (String) workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getContext().get("payment_link");
		assertNotNull(paymentLink);

		assertEquals("FUND_LINK_SENT", workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getConclusionCode());
		assertEquals("FUND_NOW", workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getEntryReasonCode());
	}

	@Test
	void divergentPathsToTheSameConclusionGetDifferentEntryReasons() {
		// Both "I expect funds shortly" (direct) and "cash flow constraints" -> "remind me
		// later" end up on END_FUNDS_REMINDER/REMINDER_SET - conclusionCode alone can't tell
		// them apart. entryReasonCode should, since it's frozen from the *original* AMB_MENU
		// choice, not the node they both happened to converge on.
		EngineTurnResult shortly = engine.start("AMB_SHORTFALL_Q2", "CUST-ENTRY-SHORTLY", Map.of());
		engine.reply(shortly.sessionId(), "2"); // "I expect funds shortly"
		EngineTurnResult shortlyDone = engine.reply(shortly.sessionId(), "1"); // "Within 3 days"

		EngineTurnResult cashflow = engine.start("AMB_SHORTFALL_Q2", "CUST-ENTRY-CASHFLOW", Map.of());
		engine.reply(cashflow.sessionId(), "3"); // "I'm facing temporary cash flow constraints"
		engine.reply(cashflow.sessionId(), "1"); // "Remind me later"
		EngineTurnResult cashflowDone = engine.reply(cashflow.sessionId(), "1"); // "Within 3 days"

		assertEquals("END_FUNDS_REMINDER", shortlyDone.currentNodeCode());
		assertEquals("END_FUNDS_REMINDER", cashflowDone.currentNodeCode());

		String shortlyConclusion = workflowSessionRepository.findById(shortly.sessionId())
				.orElseThrow().getConclusionCode();
		String cashflowConclusion = workflowSessionRepository.findById(cashflow.sessionId())
				.orElseThrow().getConclusionCode();
		assertEquals("REMINDER_SET", shortlyConclusion);
		assertEquals("REMINDER_SET", cashflowConclusion, "same conclusion despite the different path");

		assertEquals("FUNDS_SHORTLY", workflowSessionRepository.findById(shortly.sessionId())
				.orElseThrow().getEntryReasonCode());
		assertEquals("CASH_FLOW_CONSTRAINTS", workflowSessionRepository.findById(cashflow.sessionId())
				.orElseThrow().getEntryReasonCode(), "entryReasonCode should distinguish what conclusionCode can't");
	}

	@Test
	void fundNowSimulatedFailureLoopsBackToMenuAndClearsFlagAfterOneUse() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-FUND-FAIL",
				Map.of("simulate_failure", true));

		EngineTurnResult afterFailedLink = engine.reply(started.sessionId(), "1");
		assertEquals("AMB_MENU", afterFailedLink.currentNodeCode(),
				"a failed link generation should loop back to AMB_MENU");
		assertEquals(SessionStatus.ACTIVE, afterFailedLink.status());

		// flag was consumed by the first failure - retrying should now succeed
		EngineTurnResult afterRetry = engine.reply(started.sessionId(), "1");
		assertEquals("FUND_TODAY_ACK", afterRetry.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterRetry.status());
	}

	@Test
	void fundsShortlyAckDoesNotRepeatFundsTimingsQuestion() {
		// FUNDS_SHORTLY_ACK's message used to end with the exact same sentence FUNDS_TIMING
		// asks next, and both get joined into one bubble - so the question rendered twice.
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-NO-DUPE", Map.of());
		EngineTurnResult afterMenu = engine.reply(started.sessionId(), "2");

		long occurrences = afterMenu.steps().stream()
				.filter(s -> s.message() != null && s.message().contains("When do you expect the funds?"))
				.count();
		assertEquals(1, occurrences, "the question should render exactly once, not once per node");
	}

	@Test
	void fundsShortlyThreeDaysOptionSetsReminderDate() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-REMIND-3D", Map.of());
		engine.reply(started.sessionId(), "2");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "1");

		assertEquals("END_FUNDS_REMINDER", afterChoice.currentNodeCode());
		// END_FUNDS_REMINDER's message (poster-faithful) doesn't echo the date back to the
		// customer - verify the computed value landed in session context instead.
		String expectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(3).toString();
		assertEquals(expectedDate, reminderDate(started.sessionId()));
	}

	@Test
	void fundsShortlyFifteenDaysOptionSetsReminderDate() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-REMIND-15D", Map.of());
		engine.reply(started.sessionId(), "2");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "3");

		assertEquals("END_FUNDS_REMINDER", afterChoice.currentNodeCode());
		String expectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(15).toString();
		assertEquals(expectedDate, reminderDate(started.sessionId()));
	}

	private String reminderDate(String sessionId) {
		return (String) workflowSessionRepository.findById(sessionId).orElseThrow().getContext().get("reminder_date");
	}

	@Test
	void fundsShortlyFailureLoopsBackToMenuAndClearsFlagAfterOneUse() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-REMIND-FAIL",
				Map.of("simulate_failure", true));
		engine.reply(started.sessionId(), "2");
		EngineTurnResult afterFailedSchedule = engine.reply(started.sessionId(), "1");
		assertEquals("AMB_MENU", afterFailedSchedule.currentNodeCode(),
				"failed reminder scheduling should loop back to AMB_MENU");
		assertEquals(SessionStatus.ACTIVE, afterFailedSchedule.status());

		// flag was consumed by the first failure - retrying the whole branch should now succeed
		engine.reply(started.sessionId(), "2");
		EngineTurnResult afterRetry = engine.reply(started.sessionId(), "1");
		assertEquals("END_FUNDS_REMINDER", afterRetry.currentNodeCode());
	}

	@Test
	void cashFlowSpeakToExecutiveReachesHandoffMessage() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CASHFLOW-EXEC", Map.of());
		engine.reply(started.sessionId(), "3");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "2"); // "Speak to an executive"

		assertEquals("END_EXECUTIVE_HANDOFF", afterChoice.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterChoice.status());
	}

	@Test
	void cashFlowUnderstandChargesRedirectsWithoutAnyAction() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CASHFLOW-CHARGES", Map.of());
		engine.reply(started.sessionId(), "3");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "3"); // "Understand application charges"

		assertEquals("CHARGES_INFO_REDIRECT", afterChoice.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterChoice.status());
	}

	@Test
	void cashFlowRemindMeLaterRoutesToFundsTimingLikeBranch2() {
		// "Remind me later" (option 1) should ask "when" the same way Branch 2 does, instead of
		// silently scheduling an undated reminder.
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CASHFLOW-REMIND", Map.of());
		engine.reply(started.sessionId(), "3");
		EngineTurnResult afterChoice = engine.reply(started.sessionId(), "1"); // "Remind me later"
		assertEquals("FUNDS_TIMING", afterChoice.currentNodeCode());

		EngineTurnResult afterTiming = engine.reply(started.sessionId(), "2"); // "Within 7 days"
		assertEquals("END_FUNDS_REMINDER", afterTiming.currentNodeCode());
		String expectedDate = LocalDate.now(ZoneOffset.UTC).plusDays(7).toString();
		assertEquals(expectedDate, reminderDate(started.sessionId()));
	}

	@Test
	void unawareAmbChargesReachesWebsiteRedirect() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-UNAWARE-CHARGES", Map.of());
		engine.reply(started.sessionId(), "4");
		EngineTurnResult afterInfo = engine.reply(started.sessionId(), "1"); // "AMB charges"

		assertEquals("INFO_WEBSITE_REDIRECT", afterInfo.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterInfo.status());
	}

	@Test
	void unawareUpgradeBenefitsReachesPlaceholderJourney() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-UNAWARE-UPGRADE", Map.of());
		engine.reply(started.sessionId(), "4");
		EngineTurnResult afterInfo = engine.reply(started.sessionId(), "3"); // "Upgrade benefits..."

		assertEquals("ACCOUNT_UPGRADE_JOURNEY", afterInfo.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterInfo.status());
	}

	@Test
	void churnSalaryMovedElsewhereRoutesToSalaryAccountOffer() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CHURN-SALARY", Map.of());
		EngineTurnResult afterMenu = engine.reply(started.sessionId(), "5");
		assertEquals("CHURN_REASON_MENU", afterMenu.currentNodeCode());
		assertEquals(5, afterMenu.options().size());

		EngineTurnResult afterReason = engine.reply(started.sessionId(), "1"); // "Salary moved elsewhere"
		assertEquals("END_SALARY_ACCOUNT_OFFER", afterReason.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterReason.status());
	}

	@Test
	void churnAccountNoLongerNeededEndsWithVisitBranchMessage() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CHURN-NO-NEED", Map.of());
		engine.reply(started.sessionId(), "5");
		EngineTurnResult afterReason = engine.reply(started.sessionId(), "3"); // "Account no longer needed"

		assertEquals("END_VISIT_BRANCH", afterReason.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterReason.status());
	}

	@Test
	void churnServiceConcernOffersCallbackButtonThenLogsIt() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CHURN-SERVICE", Map.of());
		engine.reply(started.sessionId(), "5");
		EngineTurnResult afterReason = engine.reply(started.sessionId(), "4"); // "Service concern"
		assertEquals("SERVICE_CONCERN_CALLBACK", afterReason.currentNodeCode());
		assertEquals(1, afterReason.options().size(), "only the callback button should be offered");

		EngineTurnResult afterCallback = engine.reply(started.sessionId(), "1"); // "Request a callback"
		assertEquals("END_CALLBACK_LOGGED", afterCallback.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterCallback.status());

		assertEquals("CALLBACK_REQUESTED", workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getConclusionCode());
		assertEquals("CHURN_RISK", workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getEntryReasonCode());
	}

	@Test
	void churnOtherReasonGoesThroughInputNodeThenCallback() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-CHURN-OTHER", Map.of());
		engine.reply(started.sessionId(), "5");
		EngineTurnResult afterOther = engine.reply(started.sessionId(), "5"); // "Other (Please specify)"
		assertEquals("CHURN_REASON_OTHER", afterOther.currentNodeCode());

		EngineTurnResult afterFreeText = engine.reply(started.sessionId(), "Moving abroad");
		assertEquals("OTHER_CONCERN_CALLBACK", afterFreeText.currentNodeCode());

		EngineTurnResult afterCallback = engine.reply(started.sessionId(), "1"); // "Request a callback"
		assertEquals("END_CALLBACK_LOGGED", afterCallback.currentNodeCode());
		assertEquals(SessionStatus.COMPLETED, afterCallback.status());
		assertEquals("Moving abroad", workflowSessionRepository.findById(started.sessionId())
				.orElseThrow().getContext().get("reason"));
	}

	@Test
	void unmatchedReplyReShowsSameQuestionWithoutAdvancing() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-INVALID", Map.of());

		EngineTurnResult afterBadReply = engine.reply(started.sessionId(), "BANANA");
		assertEquals("AMB_MENU", afterBadReply.currentNodeCode());
		assertEquals(SessionStatus.ACTIVE, afterBadReply.status());
		assertTrue(afterBadReply.steps().get(0).message().startsWith("Sorry, that wasn't one of the options."));

		// session must still be sitting at AMB_MENU, ready to accept a real option
		EngineTurnResult afterValidReply = engine.reply(started.sessionId(), "1");
		assertEquals("FUND_TODAY_ACK", afterValidReply.currentNodeCode());
	}

	@Test
	void outOfRangeOptionIndexIsInvalidNotAnException() {
		EngineTurnResult started = engine.start("AMB_SHORTFALL_Q2", "CUST-OUT-OF-RANGE", Map.of());

		// AMB_MENU only has options 1-5 - "9" must be rejected like any other unmatched reply,
		// not throw (guards against the OPTION+index model silently accepting anything numeric).
		EngineTurnResult afterBadReply = engine.reply(started.sessionId(), "9");
		assertEquals("AMB_MENU", afterBadReply.currentNodeCode());
		assertEquals(SessionStatus.ACTIVE, afterBadReply.status());
	}
}
