package io.github.yfyan.minecraftfriends.model;

import java.util.concurrent.CompletionStage;

public interface ModelProvider extends AutoCloseable {
    String id();

    CompletionStage<ModelResponse> complete(ModelRequest request);

    @Override
    default void close() {
    }
}
