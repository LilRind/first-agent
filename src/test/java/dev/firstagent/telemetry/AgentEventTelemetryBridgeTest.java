package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;
import dev.firstagent.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTelemetryBridgeTest {

    @Test void mapsEventsToSpanSequence() {
        TelemetryRecorder r = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(r);

        bridge.accept(new AgentEvent.TurnStarted());
        bridge.accept(new AgentEvent.ToolStarted(new ToolCall("c1", "echo", "{}")));
        bridge.accept(new AgentEvent.TurnEnded());
        bridge.accept(new AgentEvent.AgentEnded(List.of()));

        assertEquals(List.of("turn.start", "tool.start", "turn.end", "agent.end"), r.spanNames());
        // 工具 span 带 tool 属性
        assertEquals("echo", r.spans().get(1).attributes().get("tool"));
    }
}
