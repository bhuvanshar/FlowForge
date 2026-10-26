package com.flowforge.application.ai;

import java.util.List;

/**
 * Provider-neutral chat request.
 *
 * @param temperature null = provider default
 * @param maxTokens   null = provider default
 * @param jsonObject  ask the provider for a JSON object reply (e.g. OpenAI {@code response_format: json_object})
 */
public record ChatRequest(String baseUrl, String model, List<ChatMessage> messages, Double temperature,
                          Integer maxTokens, boolean jsonObject) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }
}
