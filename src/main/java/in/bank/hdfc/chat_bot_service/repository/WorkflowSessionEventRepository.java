package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.WorkflowSessionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowSessionEventRepository extends JpaRepository<WorkflowSessionEvent, Long> {

	// event_id (plain auto-increment) orders exact chronological hop order without relying on
	// timestamp precision, which two fast automated hops could otherwise tie on.
	List<WorkflowSessionEvent> findBySessionIdOrderByEventIdAsc(String sessionId);
}
