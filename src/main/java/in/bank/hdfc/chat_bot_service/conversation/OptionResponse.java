package in.bank.hdfc.chat_bot_service.conversation;

import in.bank.hdfc.chat_bot_service.engine.OptionView;
import in.bank.hdfc.chat_bot_service.entity.EventCode;

public record OptionResponse(EventCode eventCode, Integer optionIndex, String optionLabel) {

	static OptionResponse from(OptionView option) {
		return new OptionResponse(option.eventCode(), option.optionIndex(), option.optionLabel());
	}
}
