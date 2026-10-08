package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParadigmsDemoTest {

    @Test void reactDemoPrintsFullLoop() {
        String out = new ReActDemo().runWith(MockLlm.scripted(
                new AssistantReply("我要先算一下。", List.of(new ToolCall("c1", "calculate", "{\"expression\":\"2+2\"}")), "tool_use"),
                new AssistantReply("答案: 4", List.of(), "end_turn")),
                new ToolRegistry().register(new CalculatorTool()));
        assertTrue(out.contains("Thought"), "应含 Thought: " + out);
        assertTrue(out.contains("calculate"), "应含工具名: " + out);
        assertTrue(out.contains("答案: 4"), "应含答案: " + out);
    }

    @Test void planSolveDemoRuns() {
        String out = new PlanSolveDemo().runWith(MockLlm.scripted(
                new AssistantReply("[\"计算2+2\"]", List.of(), "end_turn"),
                new AssistantReply("4", List.of(), "end_turn")),
                new ToolRegistry().register(new CalculatorTool()));
        assertTrue(out.contains("计划"), "应含计划: " + out);
        assertTrue(out.contains("4"), "应含结果: " + out);
    }
}