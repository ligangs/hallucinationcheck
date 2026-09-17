package com.gang.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gang.model.BatchReport;
import com.gang.model.DetectionResult;
import com.gang.service.BatchDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 批量幻觉检测接口：
 * - POST     /api/detect/upload         上传待检测样本文件，校验后保存并返回路径
 * - GET/POST /api/detect/batch          同步执行批量检测，返回汇总报告
 * - GET      /api/detect/batch/stream   SSE 流式执行，实时推送检测进度
 * - GET      /api/detect/result/latest  下载最近一次批量检测生成的 Markdown 报告
 */
@RestController
@RequestMapping("/api/detect")
public class DetectionController {

    private static final Logger log = LoggerFactory.getLogger(DetectionController.class);

    private final BatchDetectionService batchDetectionService;
    private final ObjectMapper objectMapper;

    public DetectionController(BatchDetectionService batchDetectionService, ObjectMapper objectMapper) {
        this.batchDetectionService = batchDetectionService;
        this.objectMapper = objectMapper;
    }

    /** 上传待检测样本文件（JSON 数组，结构同 task4_replies.json），校验后保存并返回可用路径 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        return batchDetectionService.storeUploadedSamples(file);
    }

    /** 同步批量检测：全部样本检测完成后返回汇总报告（Markdown 报告同时写入 output 目录） */
    @RequestMapping(value = "/batch", method = {RequestMethod.GET, RequestMethod.POST})
    public BatchReport batch(@RequestParam(name = "sampleFile", required = false) String sampleFile) {
        return batchDetectionService.run(null, sampleFile);
    }

    /** 流式批量检测：SSE 实时推送每条样本的检测进度与最终汇总报告 */
    @GetMapping(value = "/batch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> batchStream(@RequestParam(name = "sampleFile", required = false) String sampleFile) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Thread worker = new Thread(() -> {
            try {
                batchDetectionService.run(new BatchDetectionService.ProgressListener() {
                    @Override
                    public void onProgress(int completed, int total, DetectionResult result) {
                        Map<String, Object> data = baseEvent("progress");
                        data.put("completed", completed);
                        data.put("total", total);
                        data.put("result", result);
                        emit(sink, data);
                    }

                    @Override
                    public void onComplete(BatchReport report) {
                        Map<String, Object> data = baseEvent("done");
                        data.put("report", report);
                        emit(sink, data);
                    }
                }, sampleFile);
            } catch (Exception e) {
                log.error("流式批量检测执行失败", e);
                Map<String, Object> data = baseEvent("error");
                data.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                emit(sink, data);
            } finally {
                sink.tryEmitComplete();
            }
        }, "batch-detect-sse");
        worker.setDaemon(true);
        worker.start();
        return sink.asFlux();
    }

    /** 下载最近一次批量检测生成的 Markdown 报告 */
    @GetMapping("/result/latest")
    public ResponseEntity<Resource> latestResult() {
        Path latest = batchDetectionService.latestReportFile();
        if (latest == null || !Files.isRegularFile(latest)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + latest.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(new FileSystemResource(latest));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        String message = e.getMessage() == null ? "请求处理失败" : e.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求参数不合法" : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "上传文件过大，请控制在 10MB 以内"));
    }

    private static Map<String, Object> baseEvent(String type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        return data;
    }

    private void emit(Sinks.Many<String> sink, Map<String, Object> data) {
        try {
            sink.tryEmitNext(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            log.warn("序列化流式事件失败: {}", e.getMessage());
        }
    }
}
