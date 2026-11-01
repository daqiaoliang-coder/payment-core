package com.example.paymentcore.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 敏感数据脱敏：审计明细与日志不得保留完整卡号、密钥或令牌。
 * 这里只覆盖演示所需模式；真实实现应使用结构化日志与字段级白名单。
 */
@Component
public class SensitiveDataSanitizer {

    static final String PAN_MASKED = "<PAN_MASKED>";
    static final String REDACTED = "<REDACTED>";

    /** 16 位卡号，允许 4 位一组的分隔符。 */
    private static final Pattern PAN = Pattern.compile("\\b(\\d{4}[ -]?){3}\\d{4}\\b");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)([^\\s,;]+)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:secret|token|password|passwd|api[_-]?key|access[_-]?key)\\s*[=:]\\s*)([^\\s,;&]+)");

    public String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = PAN.matcher(raw).replaceAll(PAN_MASKED);
        value = BEARER.matcher(value).replaceAll("$1" + REDACTED);
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1" + REDACTED);
        return value;
    }
}
