package in.bank.hdfc.chat_bot_service.engine;

import in.bank.hdfc.chat_bot_service.entity.SessionStatus;
import java.util.List;

/**
 * Everything that happened automatically during one {@code start()}/{@code reply()} turn,
 * ending on the QUESTION/INPUT/END node where the engine stopped and needs input again
 * (or terminated). Deliberately channel-agnostic - not a REST DTO.
 */
public record EngineTurnResult(String sessionId, SessionStatus status, List<RenderedStep> steps,
		String currentNodeCode, List<OptionView> options) {
}
