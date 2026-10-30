package in.bank.hdfc.chat_bot_service.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta WhatsApp Business Cloud API credentials/config - sourced entirely from env vars via
 * application.yaml (see the {@code whatsapp:} block there) so nothing secret ever lands in
 * source control. Obtained from the Meta App Dashboard once the WhatsApp product is added to a
 * Business-type app - see WHATSAPP_LAYER0_INTEGRATION_PLAN.md for the exact steps.
 */
@ConfigurationProperties(prefix = "whatsapp")
public record WhatsAppProperties(
		// Graph API version, e.g. "v21.0" - bump this if Meta deprecates the pinned version.
		String apiVersion,
		// The numeric Phone Number ID from API Setup - NOT the phone number itself.
		String phoneNumberId,
		// Bearer token for the Messages API. The temporary one from API Setup expires in 24h;
		// swap for a System User token (Business Settings) if the demo needs to survive longer.
		String accessToken,
		// Arbitrary shared secret we invent ourselves and paste into the webhook config screen -
		// just needs to match what's typed there for the GET verify handshake to succeed.
		String verifyToken,
		// Meta App Secret (App Dashboard > Settings > Basic) - HMAC-signs every inbound webhook
		// call so SignatureVerifier can reject anything not actually sent by Meta.
		String appSecret) {
}
