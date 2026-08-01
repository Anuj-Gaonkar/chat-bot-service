package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workflow_entry_point")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowEntryPoint {

	@Id
	@Column(name = "entry_code", length = 100)
	private String entryCode;

	@Column(name = "workflow_version_id", nullable = false)
	private Long workflowVersionId;

	// May differ from the version's own default start_node_id.
	@Column(name = "start_node_id", nullable = false)
	private Long startNodeId;

	@Column(name = "valid_from", nullable = false)
	private LocalDate validFrom;

	@Column(name = "valid_to", nullable = false)
	private LocalDate validTo;
}
