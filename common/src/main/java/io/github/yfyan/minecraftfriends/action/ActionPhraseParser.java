package io.github.yfyan.minecraftfriends.action;

import java.util.Optional;

/** Exact, deterministic Chinese phrases. Longer conversational text is left to chat. */
public final class ActionPhraseParser {
    private ActionPhraseParser() {
    }

    public static Optional<ActionPlan> parse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String compact = message.strip().replaceAll("\\s+", "");
        var collect = java.util.regex.Pattern.compile("帮我收集([1-9][0-9]?)个橡木原木并交给我").matcher(compact);
        if (collect.matches()) {
            int count=Integer.parseInt(collect.group(1));
            return count<=64 ? Optional.of(new ActionPlan(ActionType.COLLECT_OAK_LOGS,count)) : Optional.empty();
        }
        return switch (compact) {
            case "跟随", "跟随我", "跟着我" -> Optional.of(new ActionPlan(ActionType.FOLLOW, 0));
            case "等待", "等一下", "等我", "在这里等我" -> Optional.of(new ActionPlan(ActionType.WAIT, 0));
            case "停止", "停下", "别动", "停下来" -> Optional.of(new ActionPlan(ActionType.STOP, 0));
            case "帮我收集8个橡木原木并交给我" ->
                    Optional.of(new ActionPlan(ActionType.COLLECT_OAK_LOGS, 8));
            default -> Optional.empty();
        };
    }
    public static boolean needsClarification(String message) {
        if(message==null || parse(message).isPresent())return false;
        String text=message.strip();
        return text.startsWith("帮我收集") || text.startsWith("帮我采集") || text.startsWith("请收集") || text.startsWith("请采集");
    }
}
