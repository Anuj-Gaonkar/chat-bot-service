package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Renamed from {@code workflow_edge} / {@code WorkflowEdge} per the build context doc -
 * table, entity, repository, everywhere.
 */
@Entity
@Table(name = "workflow_transition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTransition {

	// Plain auto-increment - transitions aren't inserted "between" other
	// transitions the way nodes are, so the node_id gapping doesn't apply here.
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "transition_id")
	private Long transitionId;

	@Column(name = "from_node_id", nullable = false)
	private Long fromNodeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_code", nullable = false, length = 20)
	private EventCode eventCode;

	// 1-based position among an OPTION transition's siblings from the same from_node_id - null
	// for AUTO/YES/NO/SUCCESS/FAILURE/TIMEOUT/INVALID_INPUT, which are single-shot per node.
	// This is what lets a QUESTION node offer any number of options (see EventCode).
	@Column(name = "option_index")
	private Integer optionIndex;

	@Column(name = "option_label")
	private String optionLabel;

	@Column(name = "to_node_id", nullable = false)
	private Long toNodeId;

	@Column(name = "display_order", nullable = false)
	private Integer displayOrder;
}
