package io.github.yfyan.minecraftfriends.model;

import io.github.yfyan.minecraftfriends.config.ModConfig;
import io.github.yfyan.minecraftfriends.config.ProviderMode;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Creates configured providers without making a network request. */
public final class ModelProviders {
    private ModelProviders() {
    }

    public static ModelProvider create(ModConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.enabled() || config.provider() == ProviderMode.DISABLED) {
            return new DisabledProvider();
        }
        return switch (config.provider()) {
            case MOCK -> new MockModelProvider();
            case CHAT_COMPLETIONS -> new ChatCompletionsModelProvider(config);
            case DISABLED -> new DisabledProvider();
        };
    }

    private static final class DisabledProvider implements ModelProvider {
        @Override
        public String id() {
            return "disabled";
        }

        @Override
        public CompletionStage<ModelResponse> complete(ModelRequest request) {
            Objects.requireNonNull(request, "request");
            return CompletableFuture.failedFuture(new ModelCallException(ModelCallException.Category.DISABLED));
        }
    }
}
