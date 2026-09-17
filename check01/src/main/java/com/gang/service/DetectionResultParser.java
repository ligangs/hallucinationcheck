package com.gang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gang.model.DetectionResult;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析大模型返回的检测结果，兼容三种常见输出形式：
 * 1. 纯键值行（is_hallucination: true ...）；
 * 2. Markdown 装饰的键值行（**is_hallucination**: true ...）；
 * 3. ```json 代码块包裹的 JSON。
 */
final class DetectionResultParser {

    private DetectionResultParser() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);

    private static final Pattern IS_HALLUCINATION_PATTERN = Pattern.compile(
            "(?im)^[\\s>*#`'\"-]*is_hallucination[\\s*`'\"]*[:：]\\s*[`*\"']*(true|false|是|否)");

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?im)^[\\s>*#`'\"-]*hallucination_type[\\s*`'\"]*[:：]\\s*[`*\"']*(.+)$");

    private static final Pattern RISK_PATTERN = Pattern.compile(
            "(?im)^[\\s>*#`'\"-]*risk_level[\\s*`'\"]*[:：]\\s*[`*\"']*(p[0-2])");

    private static final Pattern REASON_PATTERN = Pattern.compile(
            "(?im)^[\\s>*#`'\"-]*reason[\\s*`'\"]*[:：]\\s*[`*\"']*(.+)$");

    private static final Set<String> NULL_VALUES = Set.of(
            "", "-", "--", "无", "null", "none", "n/a", "na", "无幻觉", "非幻觉",
            "不存在", "不适用", "对应二级子类", "二级子类");

    static DetectionResult parse(String id, String userQuestion, String raw, long elapsedMs) {
        if (raw == null || raw.isBlank()) {
            return DetectionResult.error(id, userQuestion, elapsedMs, "模型返回内容为空", raw);
        }
        Boolean isHallucination = null;
        String type = null;
        String risk = null;
        String reason = null;

        JsonNode node = readJsonNode(raw);
        if (node != null) {
            isHallucination = readBoolean(node, "is_hallucination", "isHallucination");
            type = readText(node, "hallucination_type", "hallucinationType", "type");
            risk = normalizeRisk(readText(node, "risk_level", "riskLevel"));
            reason = readText(node, "reason");
        }
        if (isHallucination == null) {
            isHallucination = readBoolean(raw);
        }
        if (type == null) {
            type = readText(TYPE_PATTERN, raw);
        }
        if (risk == null) {
            risk = normalizeRisk(readText(RISK_PATTERN, raw));
        }
        if (reason == null || reason.isBlank()) {
            reason = readText(REASON_PATTERN, raw);
        }

        if (isHallucination == null) {
            return DetectionResult.error(id, userQuestion, elapsedMs, "无法解析 is_hallucination 字段", raw);
        }
        if (!isHallucination) {
            type = null;
            risk = null;
        }
        return DetectionResult.success(id, userQuestion, isHallucination, type, risk, reason, elapsedMs, raw);
    }

    private static JsonNode readJsonNode(String raw) {
        String candidate = null;
        Matcher fence = JSON_FENCE.matcher(raw);
        if (fence.find()) {
            candidate = fence.group(1);
        } else {
            Matcher object = JSON_OBJECT.matcher(raw);
            while (object.find()) {
                String text = object.group();
                if (text.contains("is_hallucination") || text.contains("isHallucination")) {
                    candidate = text;
                    break;
                }
            }
            if (candidate == null && raw.strip().startsWith("{")) {
                candidate = raw.strip();
            }
        }
        if (candidate == null) {
            return null;
        }
        try {
            return MAPPER.readTree(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean readBoolean(JsonNode node, String... fields) {
        JsonNode value = field(node, fields);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "是".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text) || "否".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String readText(JsonNode node, String... fields) {
        JsonNode value = field(node, fields);
        return value == null || value.isNull() ? null : cleanValue(value.asText());
    }

    private static JsonNode field(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Boolean readBoolean(String raw) {
        Matcher matcher = IS_HALLUCINATION_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).toLowerCase(Locale.ROOT);
        return "true".equals(value) || "是".equals(value) ? Boolean.TRUE : Boolean.FALSE;
    }

    private static String readText(Pattern pattern, String raw) {
        Matcher matcher = pattern.matcher(raw);
        return matcher.find() ? cleanValue(matcher.group(1)) : null;
    }

    private static String normalizeRisk(String risk) {
        if (risk == null) {
            return null;
        }
        String value = risk.trim().toUpperCase(Locale.ROOT);
        return Pattern.matches("P[0-2]", value) ? value : null;
    }

    private static String cleanValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("[\\s*`\"',]+$", "").trim();
        if (cleaned.isEmpty() || NULL_VALUES.contains(cleaned)
                || NULL_VALUES.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return cleaned;
    }
}
