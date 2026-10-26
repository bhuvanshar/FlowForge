package com.flowforge.application.ai;

/**
 * A chat-completion backend. Implementations perform one HTTP attempt and translate everything that can go wrong into
 * {@link ChatProviderException}; retries, JSON handling and limits are the caller's ({@link AiChatService}).
 *
 * <p>Adding a provider (a non-OpenAI wire format) means implementing this interface as a Spring bean with a new
 * {@link #id()} and adding the id to the node's {@code provider} enum in the shared schemas.
 */
public interface ChatProvider {

    /** Value of the node's {@code provider} setting, e.g. {@code openaiCompatible}. */
    String id();

    /**
     * @throws ChatProviderException the attempt failed (see its kind for retryability)
     * @throws InterruptedException  the thread was interrupted or the call was cancelled
     */
    ChatResult chat(ChatRequest request, ChatCall call) throws InterruptedException;
}
