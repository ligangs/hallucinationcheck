package com.gang.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gang.config.DetectProperties;
import com.gang.model.BatchReport;
import com.gang.model.DetectionResult;
import com.gang.model.GroundTruthEntry;
import com.gang.model.TaskSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量幻觉检测：读取样本文件 -> 并发调用大模型逐条检测 -> 对比人工标注计算指标 -> 输出 Markdown 报告。
 */
@Service
public class BatchDetectionService {

    private static final Logger log = LoggerFactory.getLogger(BatchDetectionService.class);

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String LATEST_REPORT_NAME = "detection_result_latest.md";

    private final HallucinationDetectionService detectionService;
    private final DetectProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Path latestReportFile;

    /**
     * 批量检测进度回调。
     */
    public interface ProgressListener {
        default void onProgress(int completed, int total, DetectionResult result) {
        }

        default void onComplete(BatchReport report) {
        }
    }

    public BatchDetectionService(HallucinationDetectionService detectionService,
                                 DetectProperties properties,
                                 ObjectMapper objectMapper) {
        this.detectionService = detectionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 最近一次批量检测生成的 Markdown 报告文件 */
    public Path latestReportFile() {
        return latestReportFile;
    }

    /**
     * 保存并校验前端上传的待检测样本文件（结构同 task4_replies.json 的 JSON 数组）。
     *
     * @return 包含 storedFile（保存后的绝对路径）、sampleCount、originalName 的结果
     */
    public Map<String, Object> storeUploadedSamples(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空，请选择待检测数据文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
        List<TaskSample> samples = parseSamples(bytes, "上传文件 " + file.getOriginalFilename());
        Path uploadDir = resolveOutputDir(properties.getOutputDir()).resolve("uploads");
        Path target;
        try {
            Files.createDirectories(uploadDir);
            target = uploadDir.resolve("samples_" + LocalDateTime.now().format(FILE_TIMESTAMP)
                    + "_" + UUID.randomUUID().toString().substring(0, 8) + ".json");
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败", e);
        }
        log.info("已保存上传样本文件: {}（{} 条样本）", target.toAbsolutePath(), samples.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storedFile", target.toAbsolutePath().toString());
        result.put("sampleCount", samples.size());
        result.put("originalName", file.getOriginalFilename());
        return result;
    }

    public BatchReport run(ProgressListener listener) {
        return run(listener, null);
    }

    /**
     * 执行一次批量检测。
     *
     * @param listener         进度回调，可为 null
     * @param sampleFileOverride 临时指定样本文件（相对仓库根目录或绝对路径），为 null 时使用配置
     */
    public BatchReport run(ProgressListener listener, String sampleFileOverride) {
        ProgressListener progress = listener != null ? listener : new ProgressListener() {
        };
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有批量检测任务正在执行，请等待完成后再试");
        }
        long start = System.currentTimeMillis();
        try {
            String sampleFile = sampleFileOverride != null && !sampleFileOverride.isBlank()
                    ? sampleFileOverride : properties.getSampleFile();
            Path samplePath = resolveRequiredFile(sampleFile, "样本文件");
            List<TaskSample> samples = readSamples(samplePath);
            if (samples.isEmpty()) {
                throw new IllegalStateException("样本文件内容为空: " + samplePath);
            }
            Map<String, GroundTruthEntry> groundTruth = readGroundTruth();

            int concurrency = Math.max(1, Math.min(properties.getConcurrency(), samples.size()));
            log.info("开始批量检测: {} 条样本, 并发 {}, 模型 {}", samples.size(), concurrency, detectionService.currentModel());

            List<DetectionResult> results = execute(samples, concurrency, progress);

            long duration = System.currentTimeMillis() - start;
            long hallucinationCount = results.stream()
                    .filter(result -> Boolean.TRUE.equals(result.isHallucination())).count();
            long errorCount = results.stream()
                    .filter(result -> DetectionResult.STATUS_ERROR.equals(result.status())).count();

            Path outputDir = resolveOutputDir(properties.getOutputDir());
            Files.createDirectories(outputDir);
            String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            Path reportFile = outputDir.resolve("detection_result_" + timestamp + ".md");
            Path latestFile = outputDir.resolve(LATEST_REPORT_NAME);

            BatchReport report = new BatchReport(Instant.now(), detectionService.currentModel(),
                    samplePath.toString(), reportFile.toAbsolutePath().toString(), latestFile.toAbsolutePath().toString(),
                    results.size(), hallucinationCount, errorCount, duration,
                    evaluate(results, groundTruth), results);

            String markdown = MarkdownReportRenderer.render(report);
            Files.writeString(reportFile, markdown, StandardCharsets.UTF_8);
            Files.writeString(latestFile, markdown, StandardCharsets.UTF_8);
            latestReportFile = latestFile;

            logSummary(report);
            progress.onComplete(report);
            return report;
        } catch (IOException e) {
            throw new UncheckedIOException("批量检测结果文件写入失败", e);
        } finally {
            running.set(false);
        }
    }

    private List<DetectionResult> execute(List<TaskSample> samples, int concurrency, ProgressListener progress) {
        AtomicInteger completed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "hallucination-detect");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<DetectionResult>> futures = samples.stream()
                    .map(sample -> CompletableFuture.supplyAsync(() -> detectWithRetry(sample), pool))
                    .toList();
            for (CompletableFuture<DetectionResult> future : futures) {
                future.thenAccept(result -> {
                    int done = completed.incrementAndGet();
                    log.info("[{}/{}] {} -> {}", done, samples.size(), result.id(), describe(result));
                    progress.onProgress(done, samples.size(), result);
                });
            }
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
    }

    private DetectionResult detectWithRetry(TaskSample sample) {
        int attempts = Math.max(0, properties.getMaxRetries()) + 1;
        DetectionResult result = null;
        for (int i = 1; i <= attempts; i++) {
            result = detectionService.detect(sample);
            if (DetectionResult.STATUS_SUCCESS.equals(result.status())) {
                return result;
            }
            if (i < attempts) {
                log.warn("样本 {} 第 {} 次检测失败（{}），准备重试", sample.id(), i, result.errorMessage());
                try {
                    Thread.sleep(1000L * i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result;
    }

    private static String describe(DetectionResult result) {
        if (DetectionResult.STATUS_ERROR.equals(result.status())) {
            return "检测失败: " + result.errorMessage();
        }
        return Boolean.TRUE.equals(result.isHallucination()) ? "幻觉" : "正常";
    }

    private BatchReport.Metrics evaluate(List<DetectionResult> results, Map<String, GroundTruthEntry> groundTruth) {
        if (groundTruth.isEmpty()) {
            return null;
        }
        int truePositive = 0;
        int falsePositive = 0;
        int falseNegative = 0;
        int trueNegative = 0;
        int typeExactMatch = 0;
        int typeSemanticMatch = 0;
        int typeEvaluated = 0;
        for (DetectionResult result : results) {
            GroundTruthEntry expected = groundTruth.get(result.id());
            if (expected == null || expected.isHallucination() == null) {
                continue;
            }
            boolean expectedHallucination = expected.isHallucination();
            boolean detectedHallucination = Boolean.TRUE.equals(result.isHallucination());
            if (expectedHallucination && detectedHallucination) {
                truePositive++;
            } else if (!expectedHallucination && detectedHallucination) {
                falsePositive++;
            } else if (expectedHallucination) {
                falseNegative++;
            } else {
                trueNegative++;
            }
            if (expectedHallucination && detectedHallucination) {
                typeEvaluated++;
                if (Objects.equals(expected.hallucinationType(), result.hallucinationType())) {
                    typeExactMatch++;
                }
                if (Objects.equals(canonicalType(expected.hallucinationType()), canonicalType(result.hallucinationType()))) {
                    typeSemanticMatch++;
                }
            }
        }
        Double precision = ratio(truePositive, truePositive + falsePositive);
        Double recall = ratio(truePositive, truePositive + falseNegative);
        Double f1 = (precision != null && recall != null && precision + recall > 0)
                ? round(2 * precision * recall / (precision + recall)) : null;
        Double accuracy = ratio(truePositive + trueNegative,
                truePositive + falsePositive + falseNegative + trueNegative);
        return new BatchReport.Metrics(truePositive, falsePositive, falseNegative, trueNegative,
                precision, recall, f1, accuracy, typeExactMatch, typeSemanticMatch, typeEvaluated);
    }

    /** 将不同表述的类型标签归一化到可比较的类别，用于语义级类型匹配统计 */
    private static String canonicalType(String type) {
        if (type == null) {
            return null;
        }
        String value = type.trim();
        if (value.contains("遗漏") || value.contains("缺失")) {
            return "信息遗漏";
        }
        if (value.contains("安全")) {
            return "安全误导";
        }
        if (value.contains("能力")) {
            return "能力越界";
        }
        if (value.contains("优惠") || value.contains("政策")) {
            return "政策类";
        }
        if (value.contains("参数")) {
            return "参数编造";
        }
        if (value.contains("信息") || value.contains("业务")) {
            return "信息编造";
        }
        return value;
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private List<TaskSample> readSamples(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parseSamples(in.readAllBytes(), "样本文件 " + path);
        } catch (IOException e) {
            throw new IllegalStateException("读取样本文件失败: " + path, e);
        }
    }

    /** 解析样本 JSON 数组（忽略缺少 id 的条目），本地样本文件与上传文件共用 */
    private List<TaskSample> parseSamples(byte[] bytes, String source) {
        List<TaskSample> samples;
        try {
            samples = objectMapper.readValue(bytes, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalArgumentException(source + "格式错误：需为与 task4_replies.json 相同结构的 JSON 数组");
        }
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException(source + "内容为空，请检查文件");
        }
        List<TaskSample> valid = new ArrayList<>(samples.size());
        for (TaskSample sample : samples) {
            if (sample.id() != null && !sample.id().isBlank()) {
                valid.add(sample);
            } else {
                log.warn("忽略缺少 id 的样本: {}", sample);
            }
        }
        if (valid.isEmpty()) {
            throw new IllegalArgumentException(source + "中缺少 id 字段，请检查文件格式");
        }
        return valid;
    }

    private Map<String, GroundTruthEntry> readGroundTruth() {
        Path path = resolveOptionalFile(properties.getGroundTruthFile());
        if (path == null) {
            log.warn("未找到基准文件 {}，跳过评估指标计算", properties.getGroundTruthFile());
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(path)) {
            List<GroundTruthEntry> entries = objectMapper.readValue(in, new TypeReference<>() {
            });
            Map<String, GroundTruthEntry> map = new LinkedHashMap<>();
            for (GroundTruthEntry entry : entries) {
                if (entry.id() != null) {
                    map.putIfAbsent(entry.id(), entry);
                }
            }
            return map;
        } catch (IOException e) {
            log.warn("读取基准文件失败 {}: {}，跳过评估指标计算", path, e.getMessage());
            return Map.of();
        }
    }

    private Path resolveRequiredFile(String configured, String label) {
        Path path = resolveOptionalFile(configured);
        if (path == null) {
            throw new IllegalStateException(label + "不存在: " + configured
                    + "（可使用绝对路径，或在 application.yml 的 hallucination.detect 中配置路径）");
        }
        return path;
    }

    private Path resolveOptionalFile(String configured) {
        for (Path candidate : candidatePaths(configured)) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private List<Path> candidatePaths(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        Path value = Paths.get(configured.trim());
        if (value.isAbsolute()) {
            return List.of(value);
        }
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path repoRoot = locateRepoRoot(userDir);
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(repoRoot.resolve(value).normalize());
        candidates.add(userDir.resolve(value).normalize());
        candidates.add(userDir.resolve("check01").resolve(value).normalize());
        return new ArrayList<>(candidates);
    }

    private Path resolveOutputDir(String configured) {
        Path value = Paths.get(configured == null || configured.isBlank() ? "output" : configured.trim());
        if (value.isAbsolute()) {
            return value;
        }
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return locateRepoRoot(userDir).resolve(value).normalize();
    }

    /** 从工作目录向上查找仓库根目录（同时包含 pom.xml 与 check01 子目录的目录） */
    private static Path locateRepoRoot(Path userDir) {
        Path dir = userDir;
        for (int i = 0; i < 3 && dir != null; i++) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("check01"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return userDir;
    }

    private void logSummary(BatchReport report) {
        log.info("批量检测完成: 共 {} 条, 检出幻觉 {} 条, 失败 {} 条, 耗时 {} ms",
                report.total(), report.hallucinationCount(), report.errorCount(), report.durationMs());
        BatchReport.Metrics metrics = report.metrics();
        if (metrics != null) {
            log.info("对比人工标注: TP={} FP={} FN={} TN={}, 精确率={}, 召回率={}, F1={}, 准确率={}",
                    metrics.truePositive(), metrics.falsePositive(), metrics.falseNegative(), metrics.trueNegative(),
                    formatRatio(metrics.precision()), formatRatio(metrics.recall()),
                    formatRatio(metrics.f1()), formatRatio(metrics.accuracy()));
        } else {
            log.info("未提供基准文件，跳过指标计算");
        }
        log.info("报告文件: {}", report.reportFile());
        log.info("最新报告文件: {}", report.latestFile());
    }

    private static String formatRatio(Double value) {
        return value == null ? "N/A" : String.format("%.2f%%", value * 100);
    }
}
