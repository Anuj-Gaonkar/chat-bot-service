package in.bank.hdfc.chat_bot_service.access;

import in.bank.hdfc.chat_bot_service.entity.ChatAccessToken;
import in.bank.hdfc.chat_bot_service.repository.ChatAccessTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatAccessTokenService {

	static final Duration TTL = Duration.ofHours(12);
	private static final int PHONE_DIGITS = 10;

	private final ChatAccessTokenRepository chatAccessTokenRepository;
	private final SecureRandom secureRandom = new SecureRandom();

	public ChatAccessToken issue(String phoneNumber) {
		String normalized = normalize(phoneNumber);
		if (normalized == null) {
			throw new IllegalArgumentException("Phone number must have at least " + PHONE_DIGITS + " digits");
		}
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		Instant now = Instant.now();
		return chatAccessTokenRepository.save(ChatAccessToken.builder()
				.token(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
				.phoneNumber(normalized)
				.createdAt(now)
				.expiresAt(now.plus(TTL))
				.build());
	}

	/** Reusable until it expires - verifying does not consume the token. */
	public void verify(String token, String phoneNumber) {
		ChatAccessToken stored = chatAccessTokenRepository.findById(token)
				.orElseThrow(() -> new AccessDeniedException("INVALID_TOKEN", "This link is not valid."));
		if (stored.getExpiresAt().isBefore(Instant.now())) {
			throw new AccessDeniedException("TOKEN_EXPIRED", "This link has expired. Please request a new one.");
		}
		if (!stored.getPhoneNumber().equals(normalize(phoneNumber))) {
			throw new AccessDeniedException("PHONE_MISMATCH",
					"This phone number doesn't match the one this link was sent to.");
		}
	}

	/** Digits only, last 10 - so "+91 98765 43210", "919876543210" and "9876543210" all match.
	 * Null if there aren't enough digits to compare. */
	static String normalize(String phoneNumber) {
		if (phoneNumber == null) {
			return null;
		}
		String digits = phoneNumber.replaceAll("\\D", "");
		return digits.length() < PHONE_DIGITS ? null : digits.substring(digits.length() - PHONE_DIGITS);
	}
}
