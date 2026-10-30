package in.bank.hdfc.chat_bot_service;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatBotServiceApplication {

	public static void main(String[] args) {
		// pgjdbc sends the JVM's default timezone as a connection startup parameter;
		// on some Windows machines this resolves to the legacy alias "Asia/Calcutta",
		// which Postgres's tzdata rejects outright.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(ChatBotServiceApplication.class, args);
	}

}
