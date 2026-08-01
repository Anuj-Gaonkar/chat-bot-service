package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowNode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, Long> {

	List<WorkflowNode> findByWorkflowVersionIdOrderByNodeIdAsc(Long workflowVersionId);
}
