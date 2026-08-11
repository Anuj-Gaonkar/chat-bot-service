package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workflow_node")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowNode {

	// Manually assigned, gapped by 10 (100, 110, 120, ...) - NOT @GeneratedValue.
	// Leaves room to insert a node into an existing flow later without renumbering.
	@Id
	@Column(name = "node_id")
	private Long nodeId;

	@Column(name = "workflow_version_id", nullable = false)
	private Long workflowVersionId;

	@Column(name = "node_code", nullable = false, length = 100)
	private String nodeCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "node_type", nullable = false, length = 20)
	private NodeType nodeType;

	@Column(name = "title")
	private String title;

	@Column(name = "message", columnDefinition = "text")
	private String message;

	// Set only on END-type nodes - a static tag declaring what business outcome landing here
	// represents (e.g. "FUNDED", "ESCALATED_TO_EXECUTIVE"). Null for every non-END node. Copied
	// onto WorkflowSession.conclusionCode the moment a session reaches this node - see
	// WorkflowEngine's END case.
	@Column(name = "conclusion_code", length = 50)
	private String conclusionCode;

	@Column(name = "back_allowed", nullable = false)
	private boolean backAllowed;

	@Column(name = "home_allowed", nullable = false)
	private boolean homeAllowed;

	@Column(name = "exit_allowed", nullable = false)
	private boolean exitAllowed;
}
