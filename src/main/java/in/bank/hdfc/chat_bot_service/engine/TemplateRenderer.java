package in.bank.hdfc.chat_bot_service.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Substitutes {@code {{placeholder}}} tokens from session context; missing keys are left literal. */
public final class TemplateRenderer {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

	private TemplateRenderer() {
	}

	public static String render(String template, Map<String, Object> context) {
		if (template == null) {
			return null;
		}
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			Object value = context.get(matcher.group(1));
			String replacement = value != null ? String.valueOf(value) : matcher.group(0);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}
}
