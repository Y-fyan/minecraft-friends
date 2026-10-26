package io.github.yfyan.minecraftfriends.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionPlanParserTest {
    @Test
    void acceptsOnlyTypedAllowlistedActions() {
        assertEquals(new ActionPlan(ActionType.FOLLOW, 0),
                ActionPlanParser.parse("{\"action\":\"follow\"}"));
        assertEquals(new ActionPlan(ActionType.STOP, 0),
                ActionPlanParser.parse("{\"action\":\"stop\"}"));
        assertEquals(new ActionPlan(ActionType.COLLECT_OAK_LOGS, 8),
                ActionPlanParser.parse("{\"action\":\"collect_oak_logs\",\"count\":8}"));
    }

    @Test
    void rejectsUnknownFieldsActionsAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"follow\",\"command\":\"/op\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"execute_code\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"wait\",\"action\":\"stop\"}"));
    }

    @Test
    void rejectsInvalidCountsAndTrailingContent() {
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"collect_oak_logs\",\"count\":65}"));
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"follow\",\"count\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> ActionPlanParser.parse("{\"action\":\"follow\"} {}"));
    }
}
