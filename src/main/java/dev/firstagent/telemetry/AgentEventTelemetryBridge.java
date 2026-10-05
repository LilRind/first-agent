package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 把 AgentEvent 事件流映射成 telemetry span。作为 Consumer<AgentEvent> 传给
 * AgentLoop.execute(input, emit) —— 引擎只管发事件，桥只管录 span（可观测与执行解耦）。
 */
public final class AgentEventTelemetryBridge implements Consumer<AgentEvent> {
    private final TelemetryRecorder rec;

    public AgentEventTelemetryBridge(TelemetryRecorder rec) { this.rec = rec; }

    @Override
    public void accept(AgentEvent e) {
        if (e instanceof AgentEvent.TurnStarted) rec.record("turn.start", Map.of());
        else if (e instanceof AgentEvent.MessageStarted m)
            rec.record("message.start", Map.of("role", m.message().role().name()));
        else if (e instanceof AgentEvent.MessageEnded) rec.record("message.end", Map.of());
        else if (e instanceof AgentEvent.ToolStarted t)
            rec.record("tool.start", Map.of("tool", t.call().name()));
        else if (e instanceof AgentEvent.ToolEnded t)
            rec.record("tool.end", Map.of("tool", t.call().name()));
        else if (e instanceof AgentEvent.TurnEnded) rec.record("turn.end", Map.of());
        else if (e instanceof AgentEvent.AgentEnded) rec.record("agent.end", Map.of());
    }
}
