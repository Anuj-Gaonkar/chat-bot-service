package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.entity.NodeType;
import in.bank.hdfc.chat_bot_service.engine.RenderedStep;

public record StepResponse(String nodeCode, NodeType nodeType, String message) {

	static StepResponse from(RenderedStep step) {
		return new StepResponse(step.nodeCode(), step.nodeType(), step.message());
	}
}
