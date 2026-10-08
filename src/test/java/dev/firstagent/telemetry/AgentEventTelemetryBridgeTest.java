package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;
import dev.firstagent.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 保持现有旧测试；新增 plan 事件断言

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

    // 新增：plan 事件 → plan.*  span
    @Test void planEventsBecomeSpans() {
        TelemetryRecorder r = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(r);

        bridge.accept(new AgentEvent.PlanStarted("q", List.of("步骤1", "步骤2")));
        bridge.accept(new AgentEvent.PlanStepStarted(0, "步骤1"));
        bridge.accept(new AgentEvent.PlanStepEnded(0, "步骤1", "结果1"));
        bridge.accept(new AgentEvent.PlanStepStarted(1, "步骤2"));
        bridge.accept(new AgentEvent.PlanStepEnded(1, "步骤2", "结果2"));

        assertEquals(List.of("plan.start", "plan.step.start", "plan.step.end",
                "plan.step.start", "plan.step.end"), r.spanNames());
        assertEquals("2", r.spans().get(0).attributes().get("steps"));
    }
}
