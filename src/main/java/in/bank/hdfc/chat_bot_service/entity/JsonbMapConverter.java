package in.bank.hdfc.chat_bot_service.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;

@Converter
public class JsonbMapConverter implements AttributeConverter<Map<String, Object>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

	@Override
	public String convertToDatabaseColumn(Map<String, Object> attribute) {
		if (attribute == null) {
			return null;
		}
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize session context to JSON", e);
		}
	}

	@Override
	public Map<String, Object> convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return new LinkedHashMap<>();
		}
		try {
			return MAPPER.readValue(dbData, MAP_TYPE);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to deserialize session context from JSON", e);
		}
	}
}
