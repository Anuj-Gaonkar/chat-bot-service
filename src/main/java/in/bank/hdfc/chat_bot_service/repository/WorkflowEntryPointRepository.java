package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowEntryPoint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEntryPointRepository extends JpaRepository<WorkflowEntryPoint, String> {

	Optional<WorkflowEntryPoint> findFirstByWorkflowVersionId(Long workflowVersionId);
}
