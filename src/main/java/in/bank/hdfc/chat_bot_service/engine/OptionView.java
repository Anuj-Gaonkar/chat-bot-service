package in.bank.hdfc.chat_bot_service.engine;

import in.bank.hdfc.chat_bot_service.entity.EventCode;

public record OptionView(EventCode eventCode, String optionLabel) {
}
