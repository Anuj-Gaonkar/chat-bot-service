package in.bank.hdfc.chat_bot_service.engine;

import in.bank.hdfc.chat_bot_service.entity.NodeType;

/** One MESSAGE/ACTION/QUESTION/INPUT/END node rendered during a single engine turn. */
public record RenderedStep(String nodeCode, NodeType nodeType, String message) {
}
