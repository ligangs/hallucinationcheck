package com.gang.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * task4_ground_truth.json 中的人工标注条目，用于批量检测后的指标评估。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroundTruthEntry(
        @JsonProperty("id") String id,
        @JsonProperty("is_hallucination") Boolean isHallucination,
        @JsonProperty("hallucination_type") String hallucinationType,
        @JsonProperty("detail") String detail) {
}
