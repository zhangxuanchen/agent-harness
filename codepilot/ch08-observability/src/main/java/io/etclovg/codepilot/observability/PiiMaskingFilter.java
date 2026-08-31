package io.etclovg.codepilot.observability;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PII 脱敏过滤器——观测数据写入前自动脱敏。
 * <p>
 * 对应书中 Ch8 §8.2.2 旁路观测模式中的隐私保护。
 * <p>支持：手机号、邮箱、身份证号、银行卡号、密钥/Token。
 */
@Component
public class PiiMaskingFilter {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern CARD_NO = Pattern.compile("\\d{16,19}");
    private static final Pattern API_KEY = Pattern.compile(
            "(api[_-]?key|secret|token|password)\\s*[:=]\\s*[\\w-]{10,}", Pattern.CASE_INSENSITIVE);

    public String mask(String input) {
        if (input == null) return null;
        String masked = PHONE.matcher(input).replaceAll("1xx****xxxx");
        masked = maskEmail(masked);
        masked = ID_CARD.matcher(masked).replaceAll("xxxxxxxxxxxxxxxx");
        masked = CARD_NO.matcher(masked).replaceAll("****-****-****-****");
        masked = API_KEY.matcher(masked).replaceAll("$1: ****");
        return masked;
    }

    private String maskEmail(String input) {
        Matcher matcher = EMAIL.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String[] parts = matcher.group().split("@");
            String maskedUser = parts[0].substring(0, Math.min(3, parts[0].length())) + "***";
            matcher.appendReplacement(sb, maskedUser + "@" + parts[1]);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public EventLogRecorder.EventRecord maskRecord(EventLogRecorder.EventRecord record) {
        return new EventLogRecorder.EventRecord(
                record.eventId(),
                record.eventType(),
                record.timestamp(),
                mask(record.content()),
                record.metadata().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> mask(e.getValue())))
        );
    }
}
