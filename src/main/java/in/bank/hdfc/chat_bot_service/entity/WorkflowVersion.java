package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workflow_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "workflow_version_id")
	private Long workflowVersionId;

	@Column(name = "workflow_id", nullable = false)
	private Long workflowId;

	@Column(name = "version_number", nullable = false)
	private Integer versionNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private WorkflowVersionStatus status;

	// plain Long, not a JPA relationship - see build context doc section 2:
	// nodes point back at this version, so a @ManyToOne here would create a
	// circular insert dependency for no benefit.
	@Column(name = "start_node_id")
	private Long startNodeId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;
}
