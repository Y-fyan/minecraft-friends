package io.github.yfyan.minecraftfriends.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryTest {
    @Test void exposesOnlyImplementedCapabilities() {
        CapabilityRegistry registry = CapabilityRegistry.builtin();
        assertEquals(List.of("observe", "follow", "wait", "stop", "collect_oak_logs"),
                registry.all().stream().map(CapabilitySpec::name).toList());
        assertTrue(registry.find("observe").orElseThrow().access() == CapabilitySpec.Access.READ_ONLY);
        assertTrue(registry.find("collect_oak_logs").orElseThrow().requiresAreaAuthorization());
        assertFalse(registry.find("follow").orElseThrow().requiresAreaAuthorization());
        assertTrue(registry.find("give").isEmpty());
    }

    @Test void promptIsBoundedAndDescribesEveryCapability() {
        String prompt = CapabilityRegistry.builtin().systemPrompt();
        assertTrue(prompt.length() <= CapabilityRegistry.MAX_PROMPT_CHARS);
        for (CapabilitySpec spec : CapabilityRegistry.builtin().all()) {
            assertTrue(prompt.contains(spec.name()), spec.name());
        }
        assertTrue(prompt.contains("count(必需 整数 1-64)"));
        assertTrue(prompt.contains("未知能力、额外字段"));
    }

    @Test void humanDescriptionUsesTheSameRegistry() {
        String described = CapabilityRegistry.builtin().describe();
        for (CapabilitySpec spec : CapabilityRegistry.builtin().all()) {
            assertTrue(described.contains(spec.name()));
        }
        assertFalse(described.isBlank());
    }

    @Test void rejectsDuplicateNamesAndContradictorySpecs() {
        CapabilitySpec follow = new CapabilitySpec("follow", "跟随", List.of(),
                CapabilitySpec.Access.WORLD_WRITE, false);
        assertThrows(IllegalArgumentException.class, () -> new CapabilityRegistry(List.of(follow, follow)));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySpec("observe", "读取", List.of(),
                CapabilitySpec.Access.READ_ONLY, true));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySpec("Bad Name", "x", List.of(),
                CapabilitySpec.Access.WORLD_WRITE, false));
        assertThrows(IllegalArgumentException.class, () -> CapabilityParameter.count("count", 64, 1, "倒置范围"));
    }
}
