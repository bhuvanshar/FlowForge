package com.flowforge.application.ai;

/**
 * @param content      the assistant's text (may be blank)
 * @param refusal      set when the provider reported a refusal instead of content
 * @param finishReason {@code stop}, {@code length}, ... as reported
 * @param model        the model the provider says it used (may differ from the requested alias)
 */
public record ChatResult(String content, String refusal, String finishReason, String model, Usage usage) {
}
