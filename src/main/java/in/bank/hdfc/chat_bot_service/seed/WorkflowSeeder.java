package in.bank.hdfc.chat_bot_service.seed;

import in.bank.hdfc.chat_bot_service.entity.EventCode;
import in.bank.hdfc.chat_bot_service.entity.NodeType;
import in.bank.hdfc.chat_bot_service.entity.Workflow;
import in.bank.hdfc.chat_bot_service.entity.WorkflowActionConfig;
import in.bank.hdfc.chat_bot_service.entity.WorkflowEntryPoint;
import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import in.bank.hdfc.chat_bot_service.entity.WorkflowTransition;
import in.bank.hdfc.chat_bot_service.entity.WorkflowVersion;
import in.bank.hdfc.chat_bot_service.entity.WorkflowVersionStatus;
import in.bank.hdfc.chat_bot_service.repository.WorkflowActionConfigRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowEntryPointRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowNodeRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowTransitionRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowVersionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the AMB shortfall outreach flow exactly once. Idempotent - skips entirely if any
 * {@code workflow} row already exists, so it's safe to run on every startup.
 *
 * <p>This is the full 5-branch journey from the AMB non-maintenance WhatsApp poster (see
 * AMB_FULL_JOURNEY_DB_MIGRATION_PLAN.md at the repo root) - not the earlier 3-branch cut. Node
 * IDs are grouped by branch (1xx intro/menu, 2xx fund-today, 3xx funds-shortly, 4xx cash-flow,
 * 5xx unaware-of-requirement, 6xx no-longer-using) and gapped by 10 within each branch, same
 * convention as before, to leave room for later inserts.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSeeder implements ApplicationRunner {

	private final WorkflowRepository workflowRepository;
	private final WorkflowVersionRepository workflowVersionRepository;
	private final WorkflowNodeRepository workflowNodeRepository;
	private final WorkflowTransitionRepository workflowTransitionRepository;
	private final WorkflowActionConfigRepository workflowActionConfigRepository;
	private final WorkflowEntryPointRepository workflowEntryPointRepository;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (workflowRepository.count() > 0) {
			return;
		}

		Workflow workflow = workflowRepository.save(
				Workflow.builder().name("AMB shortfall outreach").build());

		Instant now = Instant.now();
		WorkflowVersion version = workflowVersionRepository.save(WorkflowVersion.builder()
				.workflowId(workflow.getWorkflowId())
				.versionNumber(1)
				.status(WorkflowVersionStatus.PUBLISHED)
				.startNodeId(100L)
				.createdAt(now)
				.publishedAt(now)
				.build());

		Long versionId = version.getWorkflowVersionId();
		seedNodes(versionId);
		seedTransitions();
		seedActionConfigs();
		seedEntryPoint(versionId);
	}

	private void seedNodes(Long versionId) {
		List<WorkflowNode> nodes = new ArrayList<>();

		nodes.add(node(versionId, 100L, "START", NodeType.START, "Start",
				"System entry - triggered by the AMB-shortfall batch job", false, true, false));
		nodes.add(node(versionId, 110L, "INTRO", NodeType.MESSAGE, "Intro",
				"""
				You are on the HDFC Bank WhatsApp Chat. We need to share some urgent and important information about AMB maintenance in your Savings Account.

				Your balance maintenance in your Savings Account is currently low.

				To help you continue enjoying all account benefits and avoid AMB non-maintenance charges in future, we would want you to fund your account appropriately. Please let us know how you would like to proceed.""",
				false, true, false));
		nodes.add(node(versionId, 120L, "AMB_MENU", NodeType.QUESTION, "AMB menu",
				"What would you like to do?", true, true, true));

		// Branch 1: fund now. GENERATE_FUND_LINK creates a secured funding link (same
		// simulate-instantly pattern every other ACTION node here uses), then FUND_TODAY_ACK
		// shows it and ends the turn - no live "funded/not funded" branch, since the engine has
		// no real async wait and a genuine later recheck can't show its result in the same turn.
		// CHECK_FUNDING_STATUS/END_FUNDED/END_FUND_PENDING are kept below but intentionally
		// unreachable from here - a template for whatever future batch job sends a real
		// follow-up check (e.g. via a second entry point straight into CHECK_FUNDING_STATUS).
		nodes.add(node(versionId, 200L, "GENERATE_FUND_LINK", NodeType.ACTION, "Generate fund link",
				"Calls the payment gateway to create a secured funding link (simulated)", false, true, false));
		nodes.add(node(versionId, 205L, "FUND_TODAY_ACK", NodeType.END, "Fund today ack",
				"Thank you! Please fund your account by clicking on the below attached secured link. This will help you avoid AMB non-maintenance charges. {{payment_link}}",
				false, false, false));
		nodes.add(node(versionId, 210L, "CHECK_FUNDING_STATUS", NodeType.ACTION, "Check funding status",
				"Rechecks whether the required AMB balance has been received (simulated 24-hour recheck)",
				false, true, false));
		nodes.add(node(versionId, 220L, "END_FUNDED", NodeType.END, "Funded",
				"Thank you! We have received the required balance. Your account benefits continue as usual.",
				false, false, false));
		nodes.add(node(versionId, 230L, "END_FUND_PENDING", NodeType.END, "Fund pending",
				"We haven't received the required balance yet. We will remind you again in a few days. Thank you for your time.",
				false, false, false));

		// Branch 2: expect funds shortly. Only the 3 fixed windows now - no free-text custom
		// date. FUNDS_SHORTLY_ACK's message deliberately doesn't repeat FUNDS_TIMING's question -
		// both get joined into one bubble per turn, so repeating it here would show it twice.
		nodes.add(node(versionId, 300L, "FUNDS_SHORTLY_ACK", NodeType.MESSAGE, "Funds shortly ack",
				"Thank you for letting us know.", false, true, true));
		nodes.add(node(versionId, 310L, "FUNDS_TIMING", NodeType.QUESTION, "Funds timing",
				"When do you expect the funds?", true, true, true));
		nodes.add(node(versionId, 340L, "SCHEDULE_FUNDS_REMINDER", NodeType.ACTION, "Schedule funds reminder",
				"Creates a CRM follow-up reminder for the expected funding date (simulated)", false, true, false));
		nodes.add(node(versionId, 350L, "END_FUNDS_REMINDER", NodeType.END, "Funds reminder set",
				"We will send you a reminder one day before the expected date. Thank you!", false, false, false));

		// Branch 3: temporary cash flow constraints (message doesn't repeat CASH_FLOW_MENU's
		// question - same reasoning as FUNDS_SHORTLY_ACK above)
		nodes.add(node(versionId, 400L, "CASH_FLOW_ACK", NodeType.MESSAGE, "Cash flow ack",
				"We understand situations can be challenging.",
				false, true, true));
		nodes.add(node(versionId, 410L, "CASH_FLOW_MENU", NodeType.QUESTION, "Cash flow assistance",
				"Please choose how we can assist you.", true, true, true));
		nodes.add(node(versionId, 420L, "ROUTE_TO_EXECUTIVE", NodeType.ACTION, "Route to executive",
				"Connects the customer with an executive for assistance (simulated CRM handoff)", false, true, false));
		nodes.add(node(versionId, 425L, "CHARGES_INFO_REDIRECT", NodeType.END, "Charges info redirect",
				"You can learn more about applicable charges on our website or at your nearest branch.",
				false, false, false));
		nodes.add(node(versionId, 430L, "END_EXECUTIVE_HANDOFF", NodeType.END, "Executive handoff",
				"Thank you! We're connecting you with an executive who will assist you shortly.",
				false, false, false));

		// Branch 4: wasn't aware of the balance requirement (message doesn't repeat INFO_MENU's
		// question - same reasoning as FUNDS_SHORTLY_ACK above). Every sub-option now ends
		// directly in an informational redirect - no "would you like to maintain now?"
		// follow-up anymore.
		nodes.add(node(versionId, 500L, "UNAWARE_ACK", NodeType.MESSAGE, "Unaware ack",
				"No worries. Let us help you understand.",
				false, true, true));
		nodes.add(node(versionId, 510L, "INFO_MENU", NodeType.QUESTION, "AMB info menu",
				"You can choose to know more about:", true, true, true));
		nodes.add(node(versionId, 515L, "INFO_WEBSITE_REDIRECT", NodeType.END, "Info website redirect",
				"You can learn more about this on our website or by visiting your nearest branch.",
				false, false, false));
		// Reserved for a real multi-step account-upgrade journey later - a plain placeholder
		// end message for now, kept as its own node/code so that future flow can be slotted in
		// without renumbering anything around it.
		nodes.add(node(versionId, 520L, "ACCOUNT_UPGRADE_JOURNEY", NodeType.END, "Account upgrade journey",
				"Thank you for your interest - our team will reach out separately to help you explore account upgrade options for this account.",
				false, false, false));

		// Branch 5: no longer actively uses this account (message doesn't repeat
		// CHURN_REASON_MENU's question - same reasoning as FUNDS_SHORTLY_ACK above). Every
		// reason now ends in its own response - no shared "close account? yes/no" question.
		nodes.add(node(versionId, 600L, "CHURN_ACK", NodeType.MESSAGE, "Churn ack",
				"We're sorry to hear that.", false, true, true));
		nodes.add(node(versionId, 610L, "CHURN_REASON_MENU", NodeType.QUESTION, "Reason for leaving",
				"May we know the reason?", true, true, true));
		nodes.add(node(versionId, 620L, "CHURN_REASON_OTHER", NodeType.INPUT, "Other reason",
				"Please tell us more.", true, true, true));
		nodes.add(node(versionId, 630L, "CONVERT_SALARY_ACCOUNT", NodeType.ACTION, "Convert to salary account",
				"Notifies an executive to help convert this into a Salary Account (simulated)", false, true, false));
		nodes.add(node(versionId, 631L, "END_SALARY_ACCOUNT_OFFER", NodeType.END, "Salary account offer",
				"Thank you! We're connecting you with an executive to help convert this into a Salary Account.",
				false, false, false));
		nodes.add(node(versionId, 632L, "END_RETENTION_APPEAL", NodeType.END, "Retention appeal",
				"Thank you for your response. We would still like to serve you - please maintain your AMB balance to avoid any charges.",
				false, false, false));
		nodes.add(node(versionId, 634L, "END_VISIT_BRANCH", NodeType.END, "Visit branch",
				"Thank you for your response. Please visit the branch for any other details.",
				false, false, false));
		nodes.add(node(versionId, 636L, "SERVICE_CONCERN_CALLBACK", NodeType.QUESTION, "Service concern callback",
				"Thank you for your response. Request a callback from the branch.", true, true, true));
		nodes.add(node(versionId, 637L, "OTHER_CONCERN_CALLBACK", NodeType.QUESTION, "Other concern callback",
				"Thank you for your response. Request a callback for any concern.", true, true, true));
		nodes.add(node(versionId, 638L, "LOG_CALLBACK_REQUEST", NodeType.ACTION, "Log callback request",
				"Logs a callback request for the branch to action (simulated CRM call)", false, true, false));
		nodes.add(node(versionId, 639L, "END_CALLBACK_LOGGED", NodeType.END, "Callback logged",
				"Thank you, we've logged your request. Someone will call you back shortly.",
				false, false, false));

		tagConclusions(nodes);
		workflowNodeRepository.saveAll(nodes);
	}

	/**
	 * Tags every END node with a business-outcome taxonomy code, later frozen onto
	 * {@code WorkflowSession.conclusionCode} by {@link in.bank.hdfc.chat_bot_service.engine.WorkflowEngine}
	 * when a session reaches it - see the session-conclusions migration for the full design note.
	 * Non-END nodes are left untagged (null).
	 */
	private void tagConclusions(List<WorkflowNode> nodes) {
		Map<Long, String> conclusions = Map.ofEntries(
				Map.entry(205L, "FUND_LINK_SENT"),
				Map.entry(220L, "FUNDED"),
				Map.entry(230L, "FUND_PENDING"),
				Map.entry(350L, "REMINDER_SET"),
				Map.entry(425L, "INFO_REDIRECT"),
				Map.entry(430L, "ESCALATED_TO_EXECUTIVE"),
				Map.entry(515L, "INFO_REDIRECT"),
				Map.entry(520L, "UPGRADE_INTEREST"),
				Map.entry(631L, "SALARY_ACCOUNT_OFFERED"),
				Map.entry(632L, "RETENTION_APPEAL"),
				Map.entry(634L, "VISIT_BRANCH"),
				Map.entry(639L, "CALLBACK_REQUESTED"));
		nodes.forEach(n -> n.setConclusionCode(conclusions.get(n.getNodeId())));
	}

	private WorkflowNode node(Long versionId, Long nodeId, String code, NodeType type, String title,
			String message, boolean back, boolean exit, boolean home) {
		return WorkflowNode.builder()
				.nodeId(nodeId)
				.workflowVersionId(versionId)
				.nodeCode(code)
				.nodeType(type)
				.title(title)
				.message(message)
				.backAllowed(back)
				.exitAllowed(exit)
				.homeAllowed(home)
				.build();
	}

	private void seedTransitions() {
		List<WorkflowTransition> transitions = new ArrayList<>();

		transitions.add(transition(100L, EventCode.AUTO, null, null, 110L, 1));
		transitions.add(transition(110L, EventCode.AUTO, null, null, 120L, 1));

		transitions.add(transition(120L, EventCode.OPTION, 1, "I will fund my account now", 200L, 1));
		transitions.add(transition(120L, EventCode.OPTION, 2, "I expect funds shortly", 300L, 2));
		transitions.add(transition(120L, EventCode.OPTION, 3, "I'm facing temporary cash flow constraints", 400L, 3));
		transitions.add(transition(120L, EventCode.OPTION, 4, "I wasn't aware of the balance requirement", 500L, 4));
		transitions.add(transition(120L, EventCode.OPTION, 5, "I no longer actively use this account", 600L, 5));

		// Branch 1. GENERATE_FUND_LINK creates the link, then FUND_TODAY_ACK shows it and ends
		// the turn. 210/220/230 are intentionally left unreachable (see the node comment above).
		transitions.add(transition(200L, EventCode.SUCCESS, null, null, 205L, 1));
		transitions.add(transition(200L, EventCode.FAILURE, null, null, 120L, 2));

		// Branch 2
		transitions.add(transition(300L, EventCode.AUTO, null, null, 310L, 1));
		transitions.add(transition(310L, EventCode.OPTION, 1, "Within 3 days", 340L, 1));
		transitions.add(transition(310L, EventCode.OPTION, 2, "Within 7 days", 340L, 2));
		transitions.add(transition(310L, EventCode.OPTION, 3, "Within 15 days", 340L, 3));
		transitions.add(transition(340L, EventCode.SUCCESS, null, null, 350L, 1));
		transitions.add(transition(340L, EventCode.FAILURE, null, null, 120L, 2));

		// Branch 3. "Remind me later" (option 1) routes into FUNDS_TIMING (310) - same
		// 3/7/15-day menu as Branch 2 - instead of a generic undated reminder.
		transitions.add(transition(400L, EventCode.AUTO, null, null, 410L, 1));
		transitions.add(transition(410L, EventCode.OPTION, 1, "Remind me later", 310L, 1));
		transitions.add(transition(410L, EventCode.OPTION, 2, "Speak to an executive", 420L, 2));
		transitions.add(transition(410L, EventCode.OPTION, 3, "Understand application charges", 425L, 3));
		transitions.add(transition(420L, EventCode.SUCCESS, null, null, 430L, 1));
		transitions.add(transition(420L, EventCode.FAILURE, null, null, 120L, 2));

		// Branch 4. Every option ends directly in an informational redirect - no follow-up
		// question. Options 1 and 2 share the same generic website-redirect end node.
		transitions.add(transition(500L, EventCode.AUTO, null, null, 510L, 1));
		transitions.add(transition(510L, EventCode.OPTION, 1, "AMB charges", 515L, 1));
		transitions.add(transition(510L, EventCode.OPTION, 2, "Easy ways to maintain balance", 515L, 2));
		transitions.add(transition(510L, EventCode.OPTION, 3,
				"Upgrade benefits in case this is your secondary account", 520L, 3));

		// Branch 5. Every reason ends in its own response - no shared closure/retention question.
		// "Service concern" and "Other" both end in a callback-request button (options 4 and
		// 5's own QUESTION nodes each carry a single OPTION) that shares one action/end pair.
		transitions.add(transition(600L, EventCode.AUTO, null, null, 610L, 1));
		transitions.add(transition(610L, EventCode.OPTION, 1, "Salary moved elsewhere", 630L, 1));
		transitions.add(transition(610L, EventCode.OPTION, 2, "Better offer elsewhere", 632L, 2));
		transitions.add(transition(610L, EventCode.OPTION, 3, "Account no longer needed", 634L, 3));
		transitions.add(transition(610L, EventCode.OPTION, 4, "Service concern", 636L, 4));
		transitions.add(transition(610L, EventCode.OPTION, 5, "Other (Please specify)", 620L, 5));
		transitions.add(transition(620L, EventCode.AUTO, null, null, 637L, 1));
		transitions.add(transition(630L, EventCode.SUCCESS, null, null, 631L, 1));
		transitions.add(transition(630L, EventCode.FAILURE, null, null, 120L, 2));
		transitions.add(transition(636L, EventCode.OPTION, 1, "Request a callback", 638L, 1));
		transitions.add(transition(637L, EventCode.OPTION, 1, "Request a callback", 638L, 1));
		transitions.add(transition(638L, EventCode.SUCCESS, null, null, 639L, 1));
		transitions.add(transition(638L, EventCode.FAILURE, null, null, 120L, 2));

		workflowTransitionRepository.saveAll(transitions);
	}

	private WorkflowTransition transition(Long fromNodeId, EventCode eventCode, Integer optionIndex,
			String optionLabel, Long toNodeId, int displayOrder) {
		return WorkflowTransition.builder()
				.fromNodeId(fromNodeId)
				.eventCode(eventCode)
				.optionIndex(optionIndex)
				.optionLabel(optionLabel)
				.toNodeId(toNodeId)
				.displayOrder(displayOrder)
				.build();
	}

	private void seedActionConfigs() {
		List<WorkflowActionConfig> configs = new ArrayList<>();
		configs.add(actionConfig(200L, "/payments/links",
				"{customer_id}", "{payment_link: $.link}"));
		configs.add(actionConfig(210L, "/accounts/amb-check",
				"{customer_id}", "{balance_ok: $.sufficientBalance}"));
		configs.add(actionConfig(340L, "/crm/reminders",
				"{customer_id, remind_on: reminder_date}", "{reminder_id: $.id}"));
		configs.add(actionConfig(420L, "/crm/executive-handoff",
				"{customer_id, assistance_type}", "{handoff_id: $.id}"));
		configs.add(actionConfig(630L, "/crm/salary-account-conversion",
				"{customer_id}", "{request_id: $.id}"));
		configs.add(actionConfig(638L, "/crm/callback-requests",
				"{customer_id, reason}", "{callback_id: $.id}"));
		workflowActionConfigRepository.saveAll(configs);
	}

	private WorkflowActionConfig actionConfig(Long nodeId, String endpoint, String requestTemplate,
			String responseMapping) {
		return WorkflowActionConfig.builder()
				.nodeId(nodeId)
				.endpoint(endpoint)
				.httpMethod("POST")
				.requestTemplate(requestTemplate)
				.responseMapping(responseMapping)
				.timeoutMs(5000)
				.onSuccessEvent(EventCode.SUCCESS)
				.onFailureEvent(EventCode.FAILURE)
				.build();
	}

	private void seedEntryPoint(Long versionId) {
		workflowEntryPointRepository.save(WorkflowEntryPoint.builder()
				.entryCode("AMB_SHORTFALL_Q2")
				.workflowVersionId(versionId)
				.startNodeId(100L)
				.validFrom(LocalDate.of(2026, 7, 1))
				.validTo(LocalDate.of(2026, 9, 30))
				.build());
	}
}
