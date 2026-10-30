package in.bank.hdfc.chat_bot_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Link token minted when the CTA WhatsApp message is sent, mapped to the recipient's phone number.
 * chat-ui makes the customer type their number and checks it against the one stored here, so a
 * forwarded link is useless to anyone who doesn't know the number it was issued for.
 */
@Entity
@Table(name = "chat_access_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatAccessToken {

	@Id
	@Column(name = "token", length = 64)
	private String token;

	// Normalized: digits only, last 10 (see ChatAccessTokenService.normalize).
	@Column(name = "phone_number", nullable = false, length = 10)
	private String phoneNumber;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
}
