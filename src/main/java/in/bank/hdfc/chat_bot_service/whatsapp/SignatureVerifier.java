package in.bank.hdfc.chat_bot_service.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies Meta's {@code X-Hub-Signature-256} header on every inbound webhook call - HMAC-SHA256
 * of the raw request body, keyed with the Meta App Secret ({@link WhatsAppProperties#appSecret()})
 * - so the webhook only accepts calls that actually came from Meta, not a forged POST from
 * anyone who's guessed the URL.
 */
@Slf4j
final class SignatureVerifier {

	private static final String PREFIX = "sha256=";

	private SignatureVerifier() {
	}

	static boolean isValid(String rawBody, String signatureHeader, String appSecret) {
		if (appSecret == null || appSecret.isBlank()) {
			// Not configured yet - demo/local convenience only (see application.yaml). Never leave
			// unset on anything reachable from the public internet.
			log.warn("whatsapp.app-secret is not set - skipping webhook signature verification");
			return true;
		}
		if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
			return false;
		}

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
			String expected = HexFormat.of().formatHex(computed);
			String actual = signatureHeader.substring(PREFIX.length());
			return MessageDigest.isEqual(
					expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			log.error("Failed to verify WhatsApp webhook signature", e);
			return false;
		}
	}
}
