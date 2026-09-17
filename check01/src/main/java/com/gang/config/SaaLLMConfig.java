package com.gang.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaaLLMConfig {


    @Value("${spring.ai.dashscope.api-key}")
    private  String apiKey;

    private String QWEN = "qwen-plus";
    private String DEEPSEEK = "deepseek-v4-pro-0813";

    @Bean("qwen")
    public ChatModel qwen() {
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder().apiKey(apiKey).build())
                .defaultOptions(DashScopeChatOptions.builder().withModel(QWEN).build()).build();
    }

    @Bean("deepseek")
    public ChatModel deepseek() {
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder().apiKey(apiKey).build())
                .defaultOptions(DashScopeChatOptions.builder().withModel(DEEPSEEK).build()).build();
    }

    @Bean("qwenChatClient")
    public ChatClient qwenChatClient(@Qualifier("qwen") ChatModel qwenChatModel) {
        return ChatClient
                .builder(qwenChatModel)
                .defaultOptions(ChatOptions.builder().model(QWEN).build()).build();
    }

    @Bean("deepseekChatClient")
    public ChatClient deepseekChatClient(@Qualifier("deepseek") ChatModel deepseekChatModel) {
        return ChatClient
                .builder(deepseekChatModel)
                .defaultOptions(ChatOptions.builder().model(DEEPSEEK).build()).build();
    }

}
