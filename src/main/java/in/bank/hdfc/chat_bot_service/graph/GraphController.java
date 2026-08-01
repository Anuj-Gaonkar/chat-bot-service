package in.bank.hdfc.chat_bot_service.graph;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Graph", description = "Read-only access to the seeded workflow graph")
public class GraphController {

	private final GraphService graphService;

	@GetMapping("/api/graph")
	@Operation(summary = "Get the AMB shortfall flow's node/transition graph")
	public WorkflowGraphResponse getGraph() {
		return graphService.getGraph();
	}
}
