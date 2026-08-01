package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowVersion;
import in.bank.hdfc.chat_bot_service.entity.WorkflowVersionStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {

	Optional<WorkflowVersion> findFirstByStatus(WorkflowVersionStatus status);
}
