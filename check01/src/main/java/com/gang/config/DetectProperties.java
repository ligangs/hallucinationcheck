package com.gang.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 批量幻觉检测配置，对应 application.yml 中 hallucination.detect 前缀。
 */
@Component
@ConfigurationProperties(prefix = "hallucination.detect")
public class DetectProperties {

    /** 检测所用模型：qwen 或 deepseek */
    private String model = "qwen";

    /** 待检测样本文件（支持绝对路径，或相对仓库根目录的路径） */
    private String sampleFile = "task4_replies.json";

    /** 人工标注基准文件，缺失时跳过评估指标计算 */
    private String groundTruthFile = "task4_ground_truth.json";

    /** 检测结果输出目录（相对仓库根目录） */
    private String outputDir = "output";

    /** 批量检测并发度 */
    private int concurrency = 4;

    /** 单条样本失败后的最大重试次数 */
    private int maxRetries = 1;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSampleFile() {
        return sampleFile;
    }

    public void setSampleFile(String sampleFile) {
        this.sampleFile = sampleFile;
    }

    public String getGroundTruthFile() {
        return groundTruthFile;
    }

    public void setGroundTruthFile(String groundTruthFile) {
        this.groundTruthFile = groundTruthFile;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
