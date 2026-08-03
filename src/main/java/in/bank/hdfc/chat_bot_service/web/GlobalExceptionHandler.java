package in.bank.hdfc.chat_bot_service.web;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the engine's plain exceptions (build context doc section 6 - it deliberately throws
 * {@link IllegalArgumentException}/{@link IllegalStateException} rather than depending on a
 * web-layer exception type) onto proper HTTP status codes for the REST controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	// Engine throws this for "unknown entry point" / "unknown session id" - both are lookups
	// against an identifier that doesn't exist.
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiError handleNotFound(IllegalArgumentException e) {
		return new ApiError("NOT_FOUND", e.getMessage());
	}

	// Engine throws this when a session isn't waiting for input (e.g. already ended), plus a
	// handful of internal invariant violations - all are "request conflicts with current state".
	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiError handleConflict(IllegalStateException e) {
		return new ApiError("CONFLICT", e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiError handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> fe.getField() + " " + fe.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return new ApiError("VALIDATION_FAILED", message);
	}
}
