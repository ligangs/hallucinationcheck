package com.gang.controller;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatHelloController {

    @Resource(name = "qwen")
    private ChatModel qwenChatModel;

    @Resource(name = "deepseek")
    private ChatModel deepseekChatModel;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenChatClient;

    @Resource(name = "deepseekChatClient")
    private ChatClient deepseekChatClient;

    @Value("classpath:systemPrompt.txt")
    private org.springframework.core.io.Resource systemPrompt;


    @GetMapping("/qwen/doChatStream")
    public Flux<String> doChatStream(@RequestParam(name = "msg", defaultValue = "你是谁") String msg) {
        return qwenChatClient.prompt()
                // 直接传入Resource，ChatClient内部自动读取
                .system(systemPrompt)
                .user(msg)
                .stream()
                .content();
    }

}
