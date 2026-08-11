package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowSessionRepository extends JpaRepository<WorkflowSession, String> {

	// A customerId can have multiple sessions over time (repeat test runs, re-engagement) - the
	// frame feature demos against whichever one is most recent.
	Optional<WorkflowSession> findFirstByCustomerIdOrderByStartedAtDesc(String customerId);
}
