package in.bank.hdfc.chat_bot_service.graph;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
}
