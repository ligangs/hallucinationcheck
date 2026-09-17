package com.gang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单条样本的幻觉检测结果。
 */
public record DetectionResult(
        @JsonProperty("id") String id,
        @JsonProperty("user_question") String userQuestion,
        @JsonProperty("is_hallucination") Boolean isHallucination,
        @JsonProperty("hallucination_type") String hallucinationType,
        @JsonProperty("risk_level") String riskLevel,
        @JsonProperty("reason") String reason,
        @JsonProperty("status") String status,
        @JsonProperty("elapsed_ms") long elapsedMs,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("raw_response") String rawResponse) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";

    public static DetectionResult success(String id, String userQuestion, boolean isHallucination,
                                          String hallucinationType, String riskLevel, String reason,
                                          long elapsedMs, String rawResponse) {
        return new DetectionResult(id, userQuestion, isHallucination, hallucinationType, riskLevel, reason,
                STATUS_SUCCESS, elapsedMs, null, rawResponse);
    }

    public static DetectionResult error(String id, String userQuestion, long elapsedMs, String errorMessage, String rawResponse) {
        return new DetectionResult(id, userQuestion, null, null, null, null,
                STATUS_ERROR, elapsedMs, errorMessage, rawResponse);
    }
}
