package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowSessionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowSessionEventRepository extends JpaRepository<WorkflowSessionEvent, Long> {
}
