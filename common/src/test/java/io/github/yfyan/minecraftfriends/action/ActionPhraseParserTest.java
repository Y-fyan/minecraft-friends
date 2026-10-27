package io.github.yfyan.minecraftfriends.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPhraseParserTest {
    @Test
    void acceptsOnlyExplicitShortActionPhrases() {
        assertEquals(new ActionPlan(ActionType.FOLLOW, 0), ActionPhraseParser.parse("  跟着我  ").orElseThrow());
        assertEquals(new ActionPlan(ActionType.WAIT, 0), ActionPhraseParser.parse("在这里等我").orElseThrow());
        assertEquals(new ActionPlan(ActionType.STOP, 0), ActionPhraseParser.parse("停下来").orElseThrow());
        assertEquals(new ActionPlan(ActionType.COLLECT_OAK_LOGS, 8),
                ActionPhraseParser.parse("帮我收集8个橡木原木并交给我").orElseThrow());
        assertTrue(ActionPhraseParser.parse("不要跟着我").isEmpty());
        assertTrue(ActionPhraseParser.parse("我们以后再聊跟随的事").isEmpty());
        assertTrue(ActionPhraseParser.parse(null).isEmpty());
    }
    @org.junit.jupiter.api.Test void quantityAndWhitespaceAreExplicitAndBounded() {
        org.junit.jupiter.api.Assertions.assertEquals(8,ActionPhraseParser.parse("帮我收集 8 个橡木原木并交给我").orElseThrow().count());
        org.junit.jupiter.api.Assertions.assertEquals(12,ActionPhraseParser.parse("帮我收集12个橡木原木并交给我").orElseThrow().count());
        org.junit.jupiter.api.Assertions.assertTrue(ActionPhraseParser.parse("帮我收集65个橡木原木并交给我").isEmpty());
    }
}
