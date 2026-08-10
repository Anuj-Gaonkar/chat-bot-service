package in.bank.hdfc.chat_bot_service.engine;

import in.bank.hdfc.chat_bot_service.entity.EventCode;

/**
 * {@code optionIndex} is null for YES/NO transitions - the channel echoes back "YES"/"NO"
 * literally for those, and the plain option number (e.g. "3") for OPTION transitions.
 */
public record OptionView(EventCode eventCode, Integer optionIndex, String optionLabel) {
}
