package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowSessionRepository extends JpaRepository<WorkflowSession, String> {
}
