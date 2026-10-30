package in.bank.hdfc.chat_bot_service.whatsapp;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper over the WhatsApp Business Cloud API's send-message endpoint
 * ({@code POST /{phone-number-id}/messages}). {@link WhatsAppMessageMapper} builds the payload
 * shape (text/buttons/list); this class only authenticates and posts it.
 */
@Component
@Slf4j
class WhatsAppClient {

	private final RestClient restClient;
	private final WhatsAppProperties properties;

	WhatsAppClient(RestClient.Builder builder, WhatsAppProperties properties) {
		this.properties = properties;
		this.restClient = builder
				.baseUrl("https://graph.facebook.com/" + properties.apiVersion())
				.defaultHeader("Authorization", "Bearer " + properties.accessToken())
				.build();
	}

	void send(Map<String, Object> payload) {
		try {
			String response = restClient.post()
					.uri("/{phoneNumberId}/messages", properties.phoneNumberId())
					.contentType(MediaType.APPLICATION_JSON)
					.body(payload)
					.retrieve()
					.body(String.class);
			log.info("WhatsApp message sent to {}: {}", payload.get("to"), response);
		} catch (RestClientResponseException e) {
			// Common causes: expired temporary access token (401), recipient not in the verified
			// test-number list (400, code 131030) - see WHATSAPP_LAYER0_INTEGRATION_PLAN.md.
			log.error("WhatsApp send failed ({}): {}", e.getStatusCode(), e.getResponseBodyAsString());
			throw e;
		}
	}
}
