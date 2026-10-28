package io.github.yfyan.minecraftfriends.capability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry of capabilities that are implemented and accepted.
 *
 * <p>Adding an entry here is not documentation work: the matching executor branch, server-thread
 * revalidation and acceptance evidence must exist first. Unimplemented ideas stay out of this list
 * so the model never receives a callable it cannot really perform.</p>
 */
public final class CapabilityRegistry {
    public static final int MAX_PROMPT_CHARS = 4096;
    public static final CapabilityRegistry BUILTIN = new CapabilityRegistry(List.of(
            new CapabilitySpec("observe",
                    "读取当前主人与伙伴的受限观测快照；不改变世界、不生成物品、不读取其他玩家背包",
                    List.of(), CapabilitySpec.Access.READ_ONLY, false),
            new CapabilitySpec("follow",
                    "以正常实体移动跟随当前主人",
                    List.of(), CapabilitySpec.Access.WORLD_WRITE, false),
            new CapabilitySpec("wait",
                    "停止当前动作并原地等待",
                    List.of(), CapabilitySpec.Access.WORLD_WRITE, false),
            new CapabilitySpec("stop",
                    "优先取消当前任务并清除移动与交互输入",
                    List.of(), CapabilitySpec.Access.WORLD_WRITE, false),
            new CapabilitySpec("collect_oak_logs",
                    "在已授权的一次性区域内按生存规则采集并交付指定数量的橡木原木",
                    List.of(CapabilityParameter.count("count", 1, 64, "要采集并交付的橡木原木数量")),
                    CapabilitySpec.Access.WORLD_WRITE, true)));

    private static final String RULES = String.join("\n",
            "你是 Minecraft 能力调用器。只返回一个严格 JSON 对象，不得返回代码、解释、坐标或成功声明。",
            "格式固定为 {\"capability\":\"能力名\",\"arguments\":{...}}；没有参数时可省略 arguments。",
            "只能使用下面列出的能力与参数；未知能力、额外字段、错误类型、越界数值一律拒绝。",
            "快照只是观测，不是授权。需要区域的能力只有在快照 authorizedArea 非空时才可调用。",
            "无法确定时返回 {\"capability\":\"wait\",\"arguments\":{}}；不要声称动作已经成功。");

    private final List<CapabilitySpec> specs;

    public CapabilityRegistry(List<CapabilitySpec> specs) {
        this.specs = List.copyOf(specs);
        for (int i = 0; i < this.specs.size(); i++) {
            for (int j = i + 1; j < this.specs.size(); j++) {
                if (this.specs.get(i).name().equals(this.specs.get(j).name())) {
                    throw new IllegalArgumentException("Duplicate capability: " + this.specs.get(i).name());
                }
            }
        }
    }

    public List<CapabilitySpec> all() {
        return specs;
    }

    public Optional<CapabilitySpec> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    /** Prompt used for planning. Bounded and deterministic so token budgets stay predictable. */
    public String systemPrompt() {
        StringBuilder prompt = new StringBuilder(RULES).append("\n可用能力：\n");
        for (CapabilitySpec spec : specs) {
            prompt.append("- ").append(spec.name()).append('(');
            for (int i = 0; i < spec.parameters().size(); i++) {
                if (i > 0) {
                    prompt.append(", ");
                }
                prompt.append(spec.parameters().get(i).describe());
            }
            prompt.append(")：").append(spec.summary()).append('\n');
        }
        prompt.append("示例：{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":8}}");
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalStateException("Capability prompt exceeds its budget.");
        }
        return prompt.toString();
    }

    /** Human-readable list for commands; same source of truth as the model prompt. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (CapabilitySpec spec : specs) {
            text.append(spec.describe()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    public static CapabilityRegistry builtin() {
        return BUILTIN;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapabilityRegistry registry && registry.specs.equals(specs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(specs);
    }
}
