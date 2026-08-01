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
@Table(name = "workflow_action_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowActionConfig {

	// 1:1 with an ACTION-typed node - PK is also the FK, not generated.
	@Id
	@Column(name = "node_id")
	private Long nodeId;

	@Column(name = "endpoint", nullable = false)
	private String endpoint;

	@Column(name = "http_method", nullable = false, length = 10)
	private String httpMethod;

	@Column(name = "request_template", columnDefinition = "text")
	private String requestTemplate;

	@Column(name = "response_mapping", columnDefinition = "text")
	private String responseMapping;

	@Column(name = "timeout_ms", nullable = false)
	private Integer timeoutMs;

	@Enumerated(EnumType.STRING)
	@Column(name = "on_success_event", nullable = false, length = 20)
	private EventCode onSuccessEvent;

	@Enumerated(EnumType.STRING)
	@Column(name = "on_failure_event", nullable = false, length = 20)
	private EventCode onFailureEvent;
}
