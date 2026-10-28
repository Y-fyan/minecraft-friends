package io.github.yfyan.minecraftfriends.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCallParserTest {
    private final CapabilityRegistry registry = CapabilityRegistry.builtin();

    @Test void parsesOnlyRegisteredCapabilitiesWithTypedArguments() {
        assertEquals(new CapabilityCall("follow", Map.of()),
                CapabilityCallParser.parse("{\"capability\":\"follow\"}", registry));
        assertEquals(new CapabilityCall("wait", Map.of()),
                CapabilityCallParser.parse("{\"capability\":\"wait\",\"arguments\":{}}", registry));
        assertEquals(new CapabilityCall("collect_oak_logs", Map.of("count", 8)),
                CapabilityCallParser.parse("{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":8}}", registry));
        assertEquals(Map.of("count", 64), CapabilityCallParser.parse(
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":64}}", registry).arguments());
    }

    @Test void rejectsUnknownCapabilitiesAndExtraFields() {
        assertCode(CapabilityException.Code.UNKNOWN_CAPABILITY, "{\"capability\":\"execute_code\"}");
        assertCode(CapabilityException.Code.UNKNOWN_CAPABILITY, "{\"capability\":\"FOLLOW\"}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT, "{\"capability\":\"follow\",\"success\":true}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT, "{\"capability\":\"follow\",\"arguments\":{\"count\":1}}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT, "{\"capability\":\"observe\",\"arguments\":{\"radius\":3}}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT, "{\"capability\":\"follow\",\"capability\":\"stop\"}");
    }

    @Test void rejectsMalformedJsonAndDuplicateArguments() {
        for (String json : List.of("[]", "null", "true", "\"follow\"", "{}", "{\"capability\":1}",
                "{\"capability\":\"follow\"} {}", "{\"capability\":\"follow\",\"arguments\":[]}",
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":1,\"count\":2}}",
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":true}}",
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":\"8\"}}",
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":null}}")) {
            assertThrows(CapabilityException.class, () -> CapabilityCallParser.parse(json, registry), json);
        }
        assertCode(CapabilityException.Code.BAD_JSON, "null");
        assertCode(CapabilityException.Code.BAD_JSON, "{\"capability\":\"follow\"} {}");
    }

    @Test void rejectsOutOfRangeAndFractionalCounts() {
        assertCode(CapabilityException.Code.OUT_OF_RANGE,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":0}}");
        assertCode(CapabilityException.Code.OUT_OF_RANGE,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":65}}");
        assertCode(CapabilityException.Code.OUT_OF_RANGE,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":-1}}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":1.5}}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{\"count\":2147483648}}");
        assertCode(CapabilityException.Code.BAD_ARGUMENT,
                "{\"capability\":\"collect_oak_logs\",\"arguments\":{}}");
    }

    @Test void enforcesTheJsonSizeLimit() {
        assertCode(CapabilityException.Code.BAD_JSON, "{\"capability\":\"follow\"}" + " ".repeat(
                CapabilityCallParser.MAX_JSON_CHARS));
        assertThrows(CapabilityException.class,
                () -> CapabilityCallParser.parse("x".repeat(CapabilityCallParser.MAX_JSON_CHARS + 1), registry));
    }

    private void assertCode(CapabilityException.Code code, String json) {
        CapabilityException failure = assertThrows(CapabilityException.class,
                () -> CapabilityCallParser.parse(json, registry), json);
        assertEquals(code, failure.code(), json + " -> " + failure.getMessage());
    }
}
