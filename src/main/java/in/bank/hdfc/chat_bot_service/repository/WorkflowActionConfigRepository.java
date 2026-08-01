package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowActionConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowActionConfigRepository extends JpaRepository<WorkflowActionConfig, Long> {
}
