package in.bank.hdfc.chat_bot_service.access;

import lombok.Getter;

/** Token check failed - {@code code} is the machine-readable reason surfaced in the API error. */
@Getter
public class AccessDeniedException extends RuntimeException {

	private final String code;

	public AccessDeniedException(String code, String message) {
		super(message);
		this.code = code;
	}
}
