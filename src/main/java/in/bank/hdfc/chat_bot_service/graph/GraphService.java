package in.bank.hdfc.chat_bot_service.graph;

import in.bank.hdfc.chat_bot_service.engine.TemplateRenderer;
import in.bank.hdfc.chat_bot_service.entity.Workflow;
import in.bank.hdfc.chat_bot_service.entity.WorkflowEntryPoint;
import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import in.bank.hdfc.chat_bot_service.entity.WorkflowTransition;
import in.bank.hdfc.chat_bot_service.entity.WorkflowVersion;
import in.bank.hdfc.chat_bot_service.entity.WorkflowVersionStatus;
import in.bank.hdfc.chat_bot_service.repository.WorkflowEntryPointRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowNodeRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowTransitionRepository;
import in.bank.hdfc.chat_bot_service.repository.WorkflowVersionRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GraphService {

	private final WorkflowVersionRepository workflowVersionRepository;
	private final WorkflowRepository workflowRepository;
	private final WorkflowEntryPointRepository workflowEntryPointRepository;
	private final WorkflowNodeRepository workflowNodeRepository;
	private final WorkflowTransitionRepository workflowTransitionRepository;

	@Transactional(readOnly = true)
	public WorkflowGraphResponse getGraph() {
		WorkflowVersion version = workflowVersionRepository.findFirstByStatus(WorkflowVersionStatus.PUBLISHED)
				.orElseThrow(() -> new IllegalStateException("No PUBLISHED workflow_version found"));
		Workflow workflow = workflowRepository.findById(version.getWorkflowId())
				.orElseThrow(() -> new IllegalStateException("Workflow " + version.getWorkflowId() + " not found"));
		WorkflowEntryPoint entryPoint = workflowEntryPointRepository
				.findFirstByWorkflowVersionId(version.getWorkflowVersionId())
				.orElseThrow(() -> new IllegalStateException(
						"No entry point for workflow_version " + version.getWorkflowVersionId()));

		List<WorkflowNode> nodes = workflowNodeRepository
				.findByWorkflowVersionIdOrderByNodeIdAsc(version.getWorkflowVersionId());
		Map<Long, String> nodeCodeById = nodes.stream()
				.collect(Collectors.toMap(WorkflowNode::getNodeId, WorkflowNode::getNodeCode,
						(a, b) -> a, LinkedHashMap::new));

		List<Long> nodeIds = nodes.stream().map(WorkflowNode::getNodeId).toList();
		List<WorkflowTransition> transitions = workflowTransitionRepository
				.findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc(nodeIds);
		Map<Long, List<WorkflowTransition>> transitionsByFromNodeId = transitions.stream()
				.collect(Collectors.groupingBy(WorkflowTransition::getFromNodeId, LinkedHashMap::new,
						Collectors.toList()));

		List<NodeDto> nodeDtos = nodes.stream()
				.map(node -> toNodeDto(node, transitionsByFromNodeId.getOrDefault(node.getNodeId(), List.of()),
						nodeCodeById))
				.toList();

		return new WorkflowGraphResponse(workflow.getName(), entryPoint.getEntryCode(), nodeDtos);
	}

	private NodeDto toNodeDto(WorkflowNode node, List<WorkflowTransition> outgoing, Map<Long, String> nodeCodeById) {
		List<TransitionDto> transitionDtos = outgoing.stream()
				.map(t -> new TransitionDto(t.getEventCode(), t.getOptionLabel(), nodeCodeById.get(t.getToNodeId())))
				.toList();
		return new NodeDto(node.getNodeId(), node.getNodeCode(), node.getNodeType(), node.getMessage(),
				transitionDtos);
	}

	/**
	 * Same box-drawing tree walk as chatbot-webapp's GraphService.getAsciiDiagram -
	 * reimplemented here against this project's flat from/to node ids instead of JPA
	 * relations. Nodes are indented one level per hop and tagged with their event code;
	 * a node reached a second time (the graph has cycles - e.g. AMB_MENU) is not
	 * re-expanded, it's just marked "(already shown above)", or naive recursion would
	 * never terminate.
	 */
	@Transactional(readOnly = true)
	public String getAsciiDiagram() {
		WorkflowVersion version = workflowVersionRepository.findFirstByStatus(WorkflowVersionStatus.PUBLISHED)
				.orElseThrow(() -> new IllegalStateException("No PUBLISHED workflow_version found"));
		Workflow workflow = workflowRepository.findById(version.getWorkflowId())
				.orElseThrow(() -> new IllegalStateException("Workflow " + version.getWorkflowId() + " not found"));
		WorkflowEntryPoint entryPoint = workflowEntryPointRepository
				.findFirstByWorkflowVersionId(version.getWorkflowVersionId())
				.orElseThrow(() -> new IllegalStateException(
						"No entry point for workflow_version " + version.getWorkflowVersionId()));

		List<WorkflowNode> nodes = workflowNodeRepository
				.findByWorkflowVersionIdOrderByNodeIdAsc(version.getWorkflowVersionId());
		Map<Long, WorkflowNode> nodeById = nodes.stream()
				.collect(Collectors.toMap(WorkflowNode::getNodeId, n -> n, (a, b) -> a, LinkedHashMap::new));

		List<Long> nodeIds = nodes.stream().map(WorkflowNode::getNodeId).toList();
		Map<Long, List<WorkflowTransition>> transitionsByFromNodeId = workflowTransitionRepository
				.findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc(nodeIds).stream()
				.collect(Collectors.groupingBy(WorkflowTransition::getFromNodeId, LinkedHashMap::new,
						Collectors.toList()));

		WorkflowNode start = nodeById.get(entryPoint.getStartNodeId());

		StringBuilder out = new StringBuilder();
		out.append("Workflow: ").append(workflow.getName())
				.append("  (entry point: ").append(entryPoint.getEntryCode()).append(")\n\n");
		out.append(nodeLabel(start)).append(messageSuffix(start)).append('\n');

		Set<Long> printed = new HashSet<>();
		printed.add(start.getNodeId());
		appendChildren(start, "", printed, nodeById, transitionsByFromNodeId, out);
		return out.toString();
	}

	private void appendChildren(WorkflowNode node, String prefix, Set<Long> printed, Map<Long, WorkflowNode> nodeById,
			Map<Long, List<WorkflowTransition>> transitionsByFromNodeId, StringBuilder out) {
		List<WorkflowTransition> outgoing = transitionsByFromNodeId.getOrDefault(node.getNodeId(), List.of());

		for (int i = 0; i < outgoing.size(); i++) {
			WorkflowTransition transition = outgoing.get(i);
			boolean lastEdge = i == outgoing.size() - 1;
			WorkflowNode target = nodeById.get(transition.getToNodeId());

			String label = transition.getEventCode()
					+ (transition.getOptionLabel() != null ? " \"" + transition.getOptionLabel() + "\"" : "");

			boolean firstVisit = printed.add(target.getNodeId());
			String suffix = firstVisit ? messageSuffix(target) : "  (already shown above)";

			out.append(prefix).append(lastEdge ? "\\--- " : "+--- ")
					.append(label).append(" --> ").append(nodeLabel(target)).append(suffix).append('\n');

			if (firstVisit) {
				String childPrefix = prefix + (lastEdge ? "     " : "|    ");
				appendChildren(target, childPrefix, printed, nodeById, transitionsByFromNodeId, out);
			}
		}
	}

	private String nodeLabel(WorkflowNode node) {
		return node.getNodeId() + " " + node.getNodeCode() + " [" + node.getNodeType() + "]";
	}

	private String messageSuffix(WorkflowNode node) {
		String rendered = TemplateRenderer.render(node.getMessage(), DEMO_CONTEXT);
		return rendered == null || rendered.isBlank() ? "" : "  \"" + rendered + "\"";
	}

	private static final Map<String, Object> DEMO_CONTEXT = buildDemoContext();

	private static Map<String, Object> buildDemoContext() {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("customer_name", "Anuj");
		context.put("amb_required", 10000);
		context.put("shortfall_amount", 4500);
		context.put("amb_charge", 600);
		return context;
	}
}
