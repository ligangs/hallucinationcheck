package com.gang.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * task4_replies.json 中的单条待检测样本。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSample(
        @JsonProperty("id") String id,
        @JsonProperty("user_question") String userQuestion,
        @JsonProperty("system_reply") String systemReply,
        @JsonProperty("knowledge_base") String knowledgeBase) {
}
