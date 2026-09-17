package com.gang.service;

import com.gang.config.DetectProperties;
import com.gang.config.SaaLLMConfig;
import com.gang.model.DetectionResult;
import com.gang.model.TaskSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 单条样本幻觉检测：填充 systemPrompt.txt 模板占位符，调用大模型并解析结构化结果。
 */
@Service
public class HallucinationDetectionService {

    private static final Logger log = LoggerFactory.getLogger(HallucinationDetectionService.class);

    private static final String PLACEHOLDER_USER_QUESTION = "{user_question}";
    private static final String PLACEHOLDER_KNOWLEDGE_BASE = "{knowledge_base}";
    private static final String PLACEHOLDER_SYSTEM_REPLY = "{system_reply}";

    private final DetectProperties properties;
    private final ChatClient qwenChatClient;
    private final ChatClient deepseekChatClient;
    private final Resource systemPromptResource;

    private volatile String promptTemplate;

    public HallucinationDetectionService(DetectProperties properties,
                                         @Qualifier("qwenChatClient") ChatClient qwenChatClient,
                                         @Qualifier("deepseekChatClient") ChatClient deepseekChatClient,
                                         @Value("classpath:systemPrompt.txt") Resource systemPromptResource) {
        this.properties = properties;
        this.qwenChatClient = qwenChatClient;
        this.deepseekChatClient = deepseekChatClient;
        this.systemPromptResource = systemPromptResource;
    }

    /** 当前检测使用的模型标识，用于结果报告 */
    public String currentModel() {
        return isDeepseek() ? SaaLLMConfig.DEEPSEEK_MODEL : SaaLLMConfig.QWEN_MODEL;
    }

    /**
     * 检测单条样本。
     * 使用原始 Prompt/UserMessage 构造请求，避免 ChatClient 将文本当作模板二次渲染。
     */
    public DetectionResult detect(TaskSample sample) {
        long start = System.currentTimeMillis();
        String raw = null;
        try {
            String prompt = buildPrompt(sample);
            raw = selectClient().prompt(new Prompt(List.of(new UserMessage(prompt)))).call().content();
            return DetectionResultParser.parse(sample.id(), sample.userQuestion(), raw, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("样本 {} 检测调用异常: {}", sample.id(), e.getMessage());
            return DetectionResult.error(sample.id(), sample.userQuestion(), System.currentTimeMillis() - start, e.getMessage(), raw);
        }
    }

    private String buildPrompt(TaskSample sample) {
        return loadTemplate()
                .replace(PLACEHOLDER_USER_QUESTION, safe(sample.userQuestion()))
                .replace(PLACEHOLDER_KNOWLEDGE_BASE, safe(sample.knowledgeBase()))
                .replace(PLACEHOLDER_SYSTEM_REPLY, safe(sample.systemReply()));
    }

    private ChatClient selectClient() {
        return isDeepseek() ? deepseekChatClient : qwenChatClient;
    }

    private boolean isDeepseek() {
        return "deepseek".equalsIgnoreCase(properties.getModel());
    }

    private String loadTemplate() {
        String template = promptTemplate;
        if (template == null) {
            synchronized (this) {
                if (promptTemplate == null) {
                    try (InputStream in = systemPromptResource.getInputStream()) {
                        promptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 systemPrompt.txt 失败", e);
                    }
                }
                template = promptTemplate;
            }
        }
        return template;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }
}
