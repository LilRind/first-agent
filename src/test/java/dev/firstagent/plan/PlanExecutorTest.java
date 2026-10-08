package dev.firstagent.plan;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecutorTest {

    @Test void executesEachStepAndReturnsLastResult() {
        // 计划 2 步：步1 无工具返回"步骤1结果"；步2 发起工具后收尾
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("步骤1结果", List.of(), "end_turn"),
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{\"m\":1}")), "tool_use"),
                new AssistantReply("2+2=4", List.of(), "end_turn"));
        PlanExecutor ex = new PlanExecutor(llm,
                new ToolRegistry().register(new EchoTool()).register(new CalculatorTool()), 5, e -> { });

        String answer = ex.execute("问题", List.of("第一步", "第二步"));

        assertEquals("2+2=4", answer);
        assertEquals(3, llm.calls());   // 步1 ×1 + 步2 ×2
    }

    @Test void singleStepNoToolsReturnsThatStepText() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("单步答案", List.of(), "end_turn"));
        PlanExecutor ex = new PlanExecutor(llm, new ToolRegistry(), 5, e -> { });
        assertEquals("单步答案", ex.execute("q", List.of("only")));
    }

    @Test void emitsStepEventsInOrder() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("r1", List.of(), "end_turn"),
                new AssistantReply("r2", List.of(), "end_turn"));
        TelemetryRecorder rec = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(rec);
        PlanExecutor ex = new PlanExecutor(llm, new ToolRegistry(), 5, bridge);

        ex.execute("q", List.of("s1", "s2"));

        assertTrue(rec.spanNames().contains("plan.start"));
        assertTrue(rec.spanNames().contains("plan.step.start"));
        assertTrue(rec.spanNames().contains("plan.step.end"));
    }
}