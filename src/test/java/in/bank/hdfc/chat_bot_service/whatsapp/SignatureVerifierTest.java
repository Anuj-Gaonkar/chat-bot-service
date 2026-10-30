package in.bank.hdfc.chat_bot_service.whatsapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

	private static final String SECRET = "test-app-secret";
	private static final String BODY = "{\"entry\":[]}";

	@Test
	void validSignatureIsAccepted() throws Exception {
		String signature = "sha256=" + hmacHex(BODY, SECRET);
		assertTrue(SignatureVerifier.isValid(BODY, signature, SECRET));
	}

	@Test
	void tamperedBodyIsRejected() throws Exception {
		String signature = "sha256=" + hmacHex(BODY, SECRET);
		assertFalse(SignatureVerifier.isValid(BODY + "tampered", signature, SECRET));
	}

	@Test
	void wrongSecretIsRejected() throws Exception {
		String signature = "sha256=" + hmacHex(BODY, "a-different-secret");
		assertFalse(SignatureVerifier.isValid(BODY, signature, SECRET));
	}

	@Test
	void missingSignatureIsRejectedWhenSecretIsConfigured() {
		assertFalse(SignatureVerifier.isValid(BODY, null, SECRET));
	}

	@Test
	void blankSecretSkipsVerification() {
		// Local/demo convenience while whatsapp.app-secret isn't configured yet - see
		// SignatureVerifier's own doc comment. Never true on anything internet-facing.
		assertTrue(SignatureVerifier.isValid(BODY, "sha256=not-even-real", ""));
	}

	private static String hmacHex(String body, String secret) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
	}
}
