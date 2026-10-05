package dev.firstagent.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryRecorderTest {

    @Test void recordsSpansInOrderWithAttributes() {
        TelemetryRecorder r = new TelemetryRecorder();
        r.record("turn.start", Map.of());
        r.record("tool.start", Map.of("tool", "echo"));
        r.record("agent.end", Map.of("ok", "true"));

        assertEquals(List.of("turn.start", "tool.start", "agent.end"), r.spanNames());
        TelemetrySpan tool = r.spans().get(1);
        assertEquals("tool.start", tool.name());
        assertEquals("echo", tool.attributes().get("tool"));
        assertTrue(tool.durationNanos() >= 0);
    }
}
