package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowTransition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Long> {

	List<WorkflowTransition> findByFromNodeIdOrderByDisplayOrderAsc(Long fromNodeId);

	List<WorkflowTransition> findByFromNodeIdInOrderByFromNodeIdAscDisplayOrderAsc(Collection<Long> fromNodeIds);
}
