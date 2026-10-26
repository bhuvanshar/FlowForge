package com.flowforge.application.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The providers available, by id. */
@Component
public class ChatProviderRegistry {

    private final Map<String, ChatProvider> providers = new HashMap<>();

    public ChatProviderRegistry(List<ChatProvider> all) {
        for (ChatProvider provider : all) {
            if (providers.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalStateException("Two chat providers with id " + provider.id());
            }
        }
    }

    public Optional<ChatProvider> find(String id) {
        return Optional.ofNullable(providers.get(id));
    }
}
