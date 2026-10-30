package in.bank.hdfc.chat_bot_service.repository;

import in.bank.hdfc.chat_bot_service.entity.ChatAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAccessTokenRepository extends JpaRepository<ChatAccessToken, String> {
}
