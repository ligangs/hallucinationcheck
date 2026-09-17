package com.gang.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 批量检测汇总报告（用于接口返回与 Markdown 报告渲染）。
 */
public record BatchReport(
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("model") String model,
        @JsonProperty("sample_file") String sampleFile,
        @JsonProperty("report_file") String reportFile,
        @JsonProperty("latest_file") String latestFile,
        @JsonProperty("total") int total,
        @JsonProperty("hallucination_count") long hallucinationCount,
        @JsonProperty("error_count") long errorCount,
        @JsonProperty("duration_ms") long durationMs,
        @JsonProperty("metrics") Metrics metrics,
        @JsonProperty("results") List<DetectionResult> results) {

    /**
     * 与人工标注对比后的评估指标，缺少基准文件时为 null。
     */
    public record Metrics(
            @JsonProperty("true_positive") int truePositive,
            @JsonProperty("false_positive") int falsePositive,
            @JsonProperty("false_negative") int falseNegative,
            @JsonProperty("true_negative") int trueNegative,
            @JsonProperty("precision") Double precision,
            @JsonProperty("recall") Double recall,
            @JsonProperty("f1") Double f1,
            @JsonProperty("accuracy") Double accuracy,
            @JsonProperty("type_exact_match") int typeExactMatch,
            @JsonProperty("type_semantic_match") int typeSemanticMatch,
            @JsonProperty("type_evaluated") int typeEvaluated) {
    }
}
