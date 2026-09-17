package com.gang.service;

import com.gang.model.BatchReport;
import com.gang.model.DetectionResult;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 将批量检测报告渲染为 Markdown 文本，便于人工阅读、评审与交付。
 */
final class MarkdownReportRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MarkdownReportRenderer() {
    }

    static String render(BatchReport report) {
        StringBuilder md = new StringBuilder(4096);
        md.append("# 幻觉检测报告\n\n");

        md.append("## 检测概览\n\n");
        md.append("| 项目 | 内容 |\n| --- | --- |\n");
        md.append("| 生成时间 | ").append(TIME_FORMAT.format(report.generatedAt().atZone(ZoneId.systemDefault()))).append(" |\n");
        md.append("| 检测模型 | ").append(safe(report.model())).append(" |\n");
        md.append("| 样本文件 | `").append(safe(report.sampleFile())).append("` |\n");
        md.append("| 样本总数 | ").append(report.total()).append(" |\n");
        md.append("| 检出幻觉 | ").append(report.hallucinationCount()).append(" |\n");
        md.append("| 调用失败 | ").append(report.errorCount()).append(" |\n");
        md.append("| 总耗时 | ").append(String.format("%.1f", report.durationMs() / 1000.0)).append(" 秒 |\n\n");

        appendMetrics(md, report);

        md.append("## 逐条检测结果\n\n");
        md.append("| ID | 用户提问 | 是否幻觉 | 类型 | 风险等级 | 判定依据 |\n");
        md.append("| --- | --- | --- | --- | --- | --- |\n");
        for (DetectionResult result : report.results()) {
            md.append("| ").append(safe(result.id()))
                    .append(" | ").append(cell(result.userQuestion()))
                    .append(" | ").append(verdict(result))
                    .append(" | ").append(safe(result.hallucinationType()))
                    .append(" | ").append(safe(result.riskLevel()))
                    .append(" | ").append(cell(result.reason() != null ? result.reason() : result.errorMessage()))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## 输出文件\n\n");
        md.append("- 检测报告（Markdown）：`").append(safe(report.reportFile())).append("`\n\n");

        md.append("> 由智能客服幻觉检测工具自动生成。\n");
        return md.toString();
    }

    private static void appendMetrics(StringBuilder md, BatchReport report) {
        md.append("## 评估指标（对比人工标注）\n\n");
        BatchReport.Metrics metrics = report.metrics();
        if (metrics == null) {
            md.append("未提供人工标注基准文件，本次跳过指标计算。\n\n");
            return;
        }
        if (report.groundTruthFile() != null) {
            md.append("对比基准：`").append(safe(report.groundTruthFile())).append("`\n\n");
        }
        md.append("| 指标 | 数值 |\n| --- | --- |\n");
        md.append("| 精确率 Precision | ").append(ratio(metrics.precision())).append(" |\n");
        md.append("| 召回率 Recall | ").append(ratio(metrics.recall())).append(" |\n");
        md.append("| F1 | ").append(ratio(metrics.f1())).append(" |\n");
        md.append("| 准确率 Accuracy | ").append(ratio(metrics.accuracy())).append(" |\n");
        md.append("| 类型完全匹配 | ").append(typeRatio(metrics.typeExactMatch(), metrics.typeEvaluated())).append(" |\n");
        md.append("| 类型语义匹配 | ").append(typeRatio(metrics.typeSemanticMatch(), metrics.typeEvaluated())).append(" |\n\n");
        md.append("混淆矩阵：TP=").append(metrics.truePositive())
                .append("，FP=").append(metrics.falsePositive())
                .append("，FN=").append(metrics.falseNegative())
                .append("，TN=").append(metrics.trueNegative()).append("\n\n");
    }

    private static String verdict(DetectionResult result) {
        if (DetectionResult.STATUS_ERROR.equals(result.status())) {
            return "检测失败";
        }
        if (Boolean.TRUE.equals(result.isHallucination())) {
            return "是";
        }
        if (Boolean.FALSE.equals(result.isHallucination())) {
            return "否";
        }
        return "-";
    }

    private static String ratio(Double value) {
        return value == null ? "-" : String.format("%.2f%%", value * 100);
    }

    private static String typeRatio(int matched, int evaluated) {
        return evaluated == 0 ? "-" : matched + "/" + evaluated;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : cell(value);
    }

    /** Markdown 表格单元格转义：转义竖线、折叠换行，避免破坏表格结构 */
    private static String cell(String value) {
        return (value == null ? "-" : value).replace("|", "\\|").replace("\r", " ").replace("\n", " ").trim();
    }
}
