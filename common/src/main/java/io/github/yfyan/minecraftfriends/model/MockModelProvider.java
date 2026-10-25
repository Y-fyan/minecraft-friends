package io.github.yfyan.minecraftfriends.model;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MockModelProvider implements ModelProvider {
    @Override
    public String id() {
        return "mock";
    }

    @Override
    public CompletionStage<ModelResponse> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(new ModelResponse("Mock response"));
    }
}
