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
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the AMB shortfall outreach flow exactly once. Idempotent - skips entirely if
 * any {@code workflow} row already exists, so it's safe to run on every startup.
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
				"System entry - triggered by the AMB-shortfall batch job", false, false, true));
		nodes.add(node(versionId, 110L, "WELCOME", NodeType.MESSAGE, "Welcome",
				"Hi {{customer_name}}, this is HDFC Bank on WhatsApp.", false, false, true));
		nodes.add(node(versionId, 120L, "AGENDA", NodeType.MESSAGE, "Agenda",
				"Your AMB this quarter is below the required Rs.{{amb_required}}. Let's sort this out - under a minute.",
				false, false, true));
		nodes.add(node(versionId, 130L, "AMB_MENU", NodeType.QUESTION, "AMB menu",
				"What would you like to do?", true, true, true));
		nodes.add(node(versionId, 140L, "CONFIRM_TOPUP", NodeType.QUESTION, "Confirm top-up",
				"Transfer Rs.{{shortfall_amount}} now to meet your AMB requirement?", true, true, true));
		nodes.add(node(versionId, 150L, "GENERATE_LINK", NodeType.ACTION, "Generate pay link",
				"Calls the payment gateway to create a top-up link", false, false, true));
		nodes.add(node(versionId, 160L, "PAYMENT_LINK_SENT", NodeType.MESSAGE, "Payment link sent",
				"Tap below to complete your Rs.{{shortfall_amount}} transfer: {{payment_link}}",
				false, false, true));
		nodes.add(node(versionId, 170L, "LINK_FAILED", NodeType.MESSAGE, "Link failed",
				"Something went wrong generating your payment link. Please try again from the HDFC app.",
				false, true, true));
		nodes.add(node(versionId, 180L, "END_TOPUP", NodeType.END, "Done",
				"Thanks! Once it reflects, your AMB requirement is met.", false, false, false));
		nodes.add(node(versionId, 190L, "REMIND_WHEN", NodeType.QUESTION, "Reminder timing",
				"When should we remind you?", true, true, true));
		nodes.add(node(versionId, 200L, "SET_REMINDER", NodeType.ACTION, "Schedule reminder",
				"Creates a CRM follow-up task for the chosen date", false, false, true));
		nodes.add(node(versionId, 210L, "REMINDER_SET", NodeType.MESSAGE, "Reminder set",
				"Sure, we'll check back with you on {{reminder_date}}.", false, false, true));
		nodes.add(node(versionId, 220L, "END_REMINDER", NodeType.END, "Done",
				"No problem, talk soon!", false, false, false));
		nodes.add(node(versionId, 230L, "AMB_CHARGES_INFO", NodeType.MESSAGE, "Charges info",
				"If AMB isn't maintained, a non-maintenance charge of Rs.{{amb_charge}} applies each quarter.",
				false, true, true));
		nodes.add(node(versionId, 240L, "CONFIRM_OPT_OUT", NodeType.QUESTION, "Confirm opt-out",
				"Do you want to proceed without maintaining AMB?", true, true, true));
		nodes.add(node(versionId, 250L, "RECORD_OPT_OUT", NodeType.ACTION, "Record preference",
				"Updates the CRM preference flag for this customer", false, false, true));
		nodes.add(node(versionId, 260L, "OPT_OUT_CONFIRMED", NodeType.MESSAGE, "Opt-out confirmed",
				"Noted. The applicable charges will reflect in your next statement.", false, false, true));
		nodes.add(node(versionId, 270L, "END_OPT_OUT", NodeType.END, "Done",
				"Thanks for your time.", false, false, false));
		workflowNodeRepository.saveAll(nodes);
	}

	private WorkflowNode node(Long versionId, Long nodeId, String code, NodeType type, String title,
			String message, boolean back, boolean home, boolean exit) {
		return WorkflowNode.builder()
				.nodeId(nodeId)
				.workflowVersionId(versionId)
				.nodeCode(code)
				.nodeType(type)
				.title(title)
				.message(message)
				.backAllowed(back)
				.homeAllowed(home)
				.exitAllowed(exit)
				.build();
	}

	private void seedTransitions() {
		List<WorkflowTransition> transitions = new ArrayList<>();
		transitions.add(transition(100L, EventCode.AUTO, null, 110L, 1));
		transitions.add(transition(110L, EventCode.AUTO, null, 120L, 1));
		transitions.add(transition(120L, EventCode.AUTO, null, 130L, 1));
		transitions.add(transition(130L, EventCode.OPTION_1, "Add Rs.4,500 now", 140L, 1));
		transitions.add(transition(130L, EventCode.OPTION_2, "Remind me later", 190L, 2));
		transitions.add(transition(130L, EventCode.OPTION_3, "Don't maintain AMB", 230L, 3));
		transitions.add(transition(140L, EventCode.YES, null, 150L, 1));
		transitions.add(transition(140L, EventCode.NO, null, 130L, 2));
		transitions.add(transition(150L, EventCode.SUCCESS, null, 160L, 1));
		transitions.add(transition(150L, EventCode.FAILURE, null, 170L, 2));
		transitions.add(transition(160L, EventCode.AUTO, null, 180L, 1));
		transitions.add(transition(170L, EventCode.AUTO, null, 130L, 1));
		transitions.add(transition(190L, EventCode.OPTION_1, "In 3 days", 200L, 1));
		transitions.add(transition(190L, EventCode.OPTION_2, "Next week", 200L, 2));
		transitions.add(transition(200L, EventCode.SUCCESS, null, 210L, 1));
		transitions.add(transition(200L, EventCode.FAILURE, null, 130L, 2));
		transitions.add(transition(210L, EventCode.AUTO, null, 220L, 1));
		transitions.add(transition(230L, EventCode.AUTO, null, 240L, 1));
		transitions.add(transition(240L, EventCode.YES, null, 250L, 1));
		transitions.add(transition(240L, EventCode.NO, null, 130L, 2));
		transitions.add(transition(250L, EventCode.SUCCESS, null, 260L, 1));
		transitions.add(transition(250L, EventCode.FAILURE, null, 130L, 2));
		transitions.add(transition(260L, EventCode.AUTO, null, 270L, 1));
		workflowTransitionRepository.saveAll(transitions);
	}

	private WorkflowTransition transition(Long fromNodeId, EventCode eventCode, String optionLabel,
			Long toNodeId, int displayOrder) {
		return WorkflowTransition.builder()
				.fromNodeId(fromNodeId)
				.eventCode(eventCode)
				.optionLabel(optionLabel)
				.toNodeId(toNodeId)
				.displayOrder(displayOrder)
				.build();
	}

	private void seedActionConfigs() {
		List<WorkflowActionConfig> configs = new ArrayList<>();
		configs.add(actionConfig(150L, "/payments/links",
				"{amount: shortfall_amount, customer_id}", "{payment_link: $.link}"));
		configs.add(actionConfig(200L, "/crm/reminders",
				"{customer_id, remind_on: reminder_date}", "{reminder_id: $.id}"));
		configs.add(actionConfig(250L, "/crm/preferences",
				"{customer_id, preference: 'AMB_OPT_OUT'}", "{}"));
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
