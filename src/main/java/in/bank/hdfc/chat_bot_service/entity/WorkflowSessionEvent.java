package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only audit log - one row per node visited and event fired. Deliberately
 * separate from the mutable {@link WorkflowSession} row and not the source for a
 * customer-facing chat transcript (see build context doc section 2).
 */
@Entity
@Table(name = "workflow_session_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSessionEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "event_id")
	private Long eventId;

	@Column(name = "session_id", nullable = false, length = 50)
	private String sessionId;

	@Column(name = "node_id", nullable = false)
	private Long nodeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_code", nullable = false, length = 20)
	private EventCode eventCode;

	@Convert(converter = JsonbMapConverter.class)
	@Column(name = "payload", columnDefinition = "jsonb")
	private Map<String, Object> payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
