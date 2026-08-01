package in.bank.hdfc.chat_bot_service.graph;

import in.bank.hdfc.chat_bot_service.entity.NodeType;
import java.util.List;

public record NodeDto(Long nodeId, String nodeCode, NodeType nodeType, String message,
		List<TransitionDto> transitions) {
}
