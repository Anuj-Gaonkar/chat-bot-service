package in.bank.hdfc.chat_bot_service.entity;

/**
 * {@code OPTION} is generic - which numbered option a transition represents lives in
 * {@link WorkflowTransition#getOptionIndex()}, not in the enum. This keeps a QUESTION node's
 * option count unbounded (previously capped at OPTION_1..OPTION_3) without ever touching this
 * enum or the DB CHECK constraint again for any future workflow.
 */
public enum EventCode {
	AUTO,
	OPTION,
	YES,
	NO,
	SUCCESS,
	FAILURE,
	TIMEOUT,
	INVALID_INPUT
}
