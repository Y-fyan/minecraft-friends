package io.github.yfyan.minecraftfriends.model;

import java.util.Objects;

/** A safe, user-facing model failure. It deliberately retains no HTTP body, endpoint or cause. */
public final class ModelCallException extends RuntimeException {
    private final Category category;

    public ModelCallException(Category category) {
        super(Objects.requireNonNull(category, "category").userMessage());
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public String userMessage() {
        return category.userMessage();
    }

    public enum Category {
        CONFIGURATION("模型服务配置无效，请检查本地模型配置。"),
        DISABLED("模型功能当前已关闭。"),
        OVERLOADED("模型请求过多，请稍后再试。"),
        AUTHENTICATION("模型服务认证失败，请检查指定环境变量中的凭据。"),
        RATE_LIMITED("模型服务当前限流，请稍后重试。"),
        NETWORK("无法连接模型服务，请检查端点和网络。"),
        TIMEOUT("模型请求超时。"),
        INVALID_RESPONSE("模型服务返回了无法识别的响应。"),
        RESPONSE_TOO_LARGE("模型服务响应超过本地大小上限。"),
        REQUEST_TOO_LARGE("模型请求超过本地上下文或大小上限。"),
        SERVICE_UNAVAILABLE("模型服务暂时不可用。"),
        SERVICE_REJECTED("模型服务拒绝了请求。"),
        CANCELLED("模型请求已取消。");

        private final String userMessage;

        Category(String userMessage) {
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }
}
