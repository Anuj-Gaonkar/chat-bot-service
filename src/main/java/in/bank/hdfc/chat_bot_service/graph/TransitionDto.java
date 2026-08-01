package in.bank.hdfc.chat_bot_service.graph;

import in.bank.hdfc.chat_bot_service.entity.EventCode;

public record TransitionDto(EventCode eventCode, String optionLabel, String toNodeCode) {
}
