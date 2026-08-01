package in.bank.hdfc.chat_bot_service.graph;

import java.util.List;

public record WorkflowGraphResponse(String workflow, String entryPoint, List<NodeDto> nodes) {
}
