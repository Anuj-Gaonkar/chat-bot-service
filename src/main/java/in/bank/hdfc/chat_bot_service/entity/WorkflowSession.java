package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workflow_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSession {

	@Id
	@Column(name = "session_id", length = 50)
	private String sessionId;

	// Pins the session to the exact version it started on.
	@Column(name = "workflow_version_id", nullable = false)
	private Long workflowVersionId;

	@Column(name = "customer_id", nullable = false)
	private String customerId;

	@Column(name = "current_node_id", nullable = false)
	private Long currentNodeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SessionStatus status;

	// Real Postgres jsonb via a Jackson-backed AttributeConverter - not @Lob.
	@Convert(converter = JsonbMapConverter.class)
	@Column(name = "context", columnDefinition = "jsonb", nullable = false)
	@Builder.Default
	private Map<String, Object> context = new LinkedHashMap<>();

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "last_interaction_at", nullable = false)
	private Instant lastInteractionAt;

	@Column(name = "ended_at")
	private Instant endedAt;
}
